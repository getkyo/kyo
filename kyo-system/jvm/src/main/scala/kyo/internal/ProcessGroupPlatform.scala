package kyo.internal

import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle
import java.lang.invoke.VarHandle
import java.util.concurrent.CompletableFuture
import kyo.*

/** Starts a program as the leader of a new process group, and signals, observes and reaps it, through the C library over
  * `java.lang.foreign`.
  *
  * `java.lang.ProcessBuilder` cannot start a child in a new group, and a `setpgid` from the parent after `start()` fails with EACCES because
  * the child has already exec'd, so the program is started with `posix_spawnp` and `POSIX_SPAWN_SETPGROUP`. Its exit is observed through
  * `ProcessHandle.onExit`, which waits with `WNOWAIT` for a process the JDK did not start (`ProcessHandleImpl.completion(pid, false)`): the
  * leader stays unreaped, holding its pid and so its group's id, until [[reap]], which runs after the group is killed.
  *
  * The child sees no descriptor it was not given, as with the JDK's own spawn: `POSIX_SPAWN_CLOEXEC_DEFAULT` on macOS, where the standard
  * streams it inherits are named with `addinherit_np`, and `addclosefrom_np` on glibc. musl has neither, so there the child also sees a
  * descriptor this process holds without `FD_CLOEXEC`; the JDK opens every descriptor with it, and this spawn's pipes carry it.
  *
  * The pipe ends kept here are opened as JDK file streams on `/dev/fd/<n>` and the descriptors `pipe` returned are closed, so a read, a write
  * and a close behave as on the JDK's own process pipes.
  *
  * Windows has no process groups: [[spawn]] panics there, so nothing reaches the others.
  */
private[kyo] object ProcessGroupPlatform:

    import GroupProcessUnsafe.Spawned

    // The same on every POSIX host.
    val SigKill: Int = 9
    val SigTerm: Int = 15

    // The C headers' values that differ between macOS and Linux; glibc and musl agree on every one.
    private val Mac               = Platform.isMac
    private val SetPgroup         = 0x02
    private val SetSigmask        = 0x08
    private val CloexecDefault    = 0x4000
    private val WaitNoHang        = 1
    private val WaitExited        = 4
    private val WaitNoWait        = if Mac then 0x20 else 0x01000000
    private val IdPid             = 1
    private val CodeOffset        = 8L
    private val StatusOffset      = if Mac then 20L else 24L
    private val CodeExited        = 1
    private val OpenWrite         = 1
    private val OpenCreate        = if Mac then 0x200 else 0x40
    private val OpenTruncate      = if Mac then 0x400 else 0x200
    private val OpenAppend        = if Mac then 0x8 else 0x400
    private val OpenCloexec       = if Mac then 0x1000000 else 0x80000
    private val SetDescriptor     = 2
    private val DescriptorCloexec = 1
    private val ErrnoPermission   = 1
    private val ErrnoNoEntry      = 2
    private val ErrnoInterrupted  = 4
    private val ErrnoNoExec       = 8
    private val ErrnoAccess       = 13
    private val ErrnoIsFolder     = 21
    private val FileMode          = 420 // 0644
    // posix_spawnattr_t and posix_spawn_file_actions_t are a pointer on macOS and at most 336 bytes on glibc and musl; sigset_t is 4 and
    // 128 bytes; siginfo_t is 104 and 128.
    private val OpaqueSize = 512L

    def spawn(
        args: Chunk[String],
        workDir: Maybe[kyo.Path],
        environment: Map[String, String],
        stdin: Process.Input,
        stdout: StdioSink,
        stderr: StdioSink,
        redirectError: Boolean
    )(using Frame): Result[CommandException, Spawned] =
        if Platform.isWindows then
            Result.panic(new UnsupportedOperationException(s"processGroup needs a POSIX host: ${args.mkString(" ")}"))
        else
            val program = args.headMaybe.getOrElse("")
            val arena   = Arena.ofConfined()
            // Every descriptor opened here; each is closed or handed to a stream before the spawn returns.
            val opened = scala.collection.mutable.ArrayBuffer.empty[Int]
            try
                val input  = Maybe.when(stdin != Process.Input.Inherit)(pipe(arena, opened))
                val output = Maybe.when(stdout == StdioSink.Pipe)(pipe(arena, opened))
                val error  = Maybe.when(!redirectError && stderr == StdioSink.Pipe)(pipe(arena, opened))
                val status = start(arena, program, args, workDir, environment, input, output, error, stdout, stderr, redirectError)
                // The child holds its ends now, or nobody does if the spawn failed.
                input.foreach((read, _) => close(read, opened))
                output.foreach((_, write) => close(write, opened))
                error.foreach((_, write) => close(write, opened))
                status match
                    case Result.Success(pid) =>
                        Result.succeed(Spawned(
                            pid,
                            input.map((_, write) => writer(write, opened)).getOrElse(closedWriter()),
                            output.map((read, _) => reader(read, opened)).getOrElse(InputStream.nullInputStream()),
                            error.map((read, _) => reader(read, opened)).getOrElse(InputStream.nullInputStream())
                        ))
                    case Result.Failure(ErrnoNoEntry) => Result.fail(ProgramNotFoundException(program))
                    case Result.Failure(ErrnoAccess | ErrnoPermission | ErrnoNoExec | ErrnoIsFolder) =>
                        Result.fail(PermissionDeniedException(program))
                    case Result.Failure(errno) => Result.panic(new IOException(s"posix_spawnp of $program failed with errno $errno"))
                    case Result.Panic(cause)   => Result.panic(cause)
                end match
            catch
                case e: IOException => Result.panic(e)
            finally
                opened.toList.foreach(fd => close(fd, opened))
                arena.close()
            end try
        end if
    end spawn

    /** Completes with the program's exit code at its exit, leaving it unreaped.
      *
      * macOS's `waitid` reports a child that is only stopped as if it had exited, `WEXITED` notwithstanding (si_code `CLD_STOPPED`,
      * status 17), and `ProcessHandle.onExit` completes on it too; destroyTree stops a tree before killing it, so that would read a stop as
      * the exit and reap nothing. On macOS the exit is observed through kqueue's `NOTE_EXIT`, which fires only at the exit. Linux's
      * `waitid` reports exits only.
      */
    def awaitExit(pid: Long)(using AllowUnsafe): CompletableFuture[ExitCode] =
        if Mac then ExitWatch.watch(pid)
        else
            val handle = ProcessHandle.of(pid)
            // An unreaped child is always found; an absent handle means its exit was already collected.
            if handle.isPresent then handle.get.onExit().thenApply(_ => exitCode(pid))
            else CompletableFuture.completedFuture(exitCode(pid))
    end awaitExit

    def signal(pid: Long, number: Int): Unit = discard(kill(pid, number))

    def signalGroup(group: Long, number: Int): Unit = discard(kill(-group, number))

    def groupExists(group: Long): Boolean =
        val (status, errno) = kill(-group, 0)
        status == 0 || errno == ErrnoPermission

    /** Collects the exited program, releasing its pid; false while it is still running. */
    def reap(pid: Long): Boolean =
        val arena = Arena.ofConfined()
        try
            val state  = arena.allocate(C.stateLayout)
            val status = arena.allocate(JAVA_INT)
            @scala.annotation.tailrec
            def loop(): Boolean =
                val reaped: Int = C.waitpid.invokeExact(state, pid.toInt, status, WaitNoHang)
                if reaped == -1 && errnoOf(state) == ErrnoInterrupted then loop() else reaped == pid.toInt
            end loop
            loop()
        finally arena.close()
        end try
    end reap

    // posix_spawnp's own errno on failure, which it returns rather than setting.
    private def start(
        arena: Arena,
        program: String,
        args: Chunk[String],
        workDir: Maybe[kyo.Path],
        environment: Map[String, String],
        input: Maybe[(Int, Int)],
        output: Maybe[(Int, Int)],
        error: Maybe[(Int, Int)],
        stdout: StdioSink,
        stderr: StdioSink,
        redirectError: Boolean
    ): Result[Int, Long] =
        val attr    = arena.allocate(OpaqueSize, 16)
        val actions = arena.allocate(OpaqueSize, 16)
        val mask    = arena.allocate(OpaqueSize, 16)
        check("posix_spawnattr_init", C.attrInit.invokeExact(attr))
        try
            check("posix_spawn_file_actions_init", C.actionsInit.invokeExact(actions))
            try
                val flags = SetPgroup | SetSigmask | (if Mac then CloexecDefault else 0)
                check("posix_spawnattr_setflags", C.attrSetflags.invokeExact(attr, flags.toShort))
                check("posix_spawnattr_setpgroup", C.attrSetpgroup.invokeExact(attr, 0))
                check("sigemptyset", C.sigemptyset.invokeExact(mask))
                check("posix_spawnattr_setsigmask", C.attrSetsigmask.invokeExact(attr, mask))
                input match
                    case Present((read, _)) => check("adddup2", C.addDup2.invokeExact(actions, read, 0))
                    case Absent             => inherit(actions, 0)
                route(arena, actions, 1, stdout, output)
                if redirectError then check("adddup2", C.addDup2.invokeExact(actions, 1, 2))
                else route(arena, actions, 2, stderr, error)
                workDir.foreach(path => check("addchdir_np", C.addChdir.invokeExact(actions, arena.allocateFrom(path.toString))))
                // Last: before the dup2s above, these closes would take the pipe ends they read.
                C.addClosefrom.foreach(handle => check("addclosefrom_np", handle.invokeExact(actions, 3)))
                val pid         = arena.allocate(JAVA_INT)
                val argv        = strings(arena, args)
                val envp        = strings(arena, Chunk.from(environment.map((name, value) => s"$name=$value")))
                val status: Int = C.spawnp.invokeExact(pid, arena.allocateFrom(program), actions, attr, argv, envp)
                if status == 0 then Result.succeed(pid.get(JAVA_INT, 0).toLong) else Result.fail(status)
            finally
                val _: Int = C.actionsDestroy.invokeExact(actions)
            end try
        finally
            val _: Int = C.attrDestroy.invokeExact(attr)
        end try
    end start

    private def exitCode(pid: Long): ExitCode =
        val arena = Arena.ofConfined()
        try
            val info   = arena.allocate(OpaqueSize, 16).fill(0.toByte)
            val _: Int = C.waitid.invokeExact(IdPid, pid.toInt, info, WaitExited | WaitNoHang | WaitNoWait)
            val status = info.get(JAVA_INT, StatusOffset)
            if info.get(JAVA_INT, CodeOffset) == CodeExited then ExitCode(status) else ExitCode.Signaled(status)
        finally arena.close()
        end try
    end exitCode

    private def kill(pid: Long, number: Int): (Int, Int) =
        val arena = Arena.ofConfined()
        try
            val state       = arena.allocate(C.stateLayout)
            val status: Int = C.kill.invokeExact(state, pid.toInt, number)
            (status, errnoOf(state))
        finally arena.close()
        end try
    end kill

    private def pipe(arena: Arena, opened: scala.collection.mutable.ArrayBuffer[Int]): (Int, Int) =
        val state       = arena.allocate(C.stateLayout)
        val ends        = arena.allocate(JAVA_INT, 2)
        val status: Int = C.pipe2 match
            case Present(pipe2) => pipe2.invokeExact(state, ends, OpenCloexec)
            case Absent         => C.pipe.invokeExact(state, ends)
        if status != 0 then throw new IOException(s"pipe failed with errno ${errnoOf(state)}")
        val read  = ends.getAtIndex(JAVA_INT, 0)
        val write = ends.getAtIndex(JAVA_INT, 1)
        opened += read
        opened += write
        if C.pipe2.isEmpty then
            check("fcntl", C.fcntl.invokeExact(read, SetDescriptor, DescriptorCloexec))
            check("fcntl", C.fcntl.invokeExact(write, SetDescriptor, DescriptorCloexec))
        (read, write)
    end pipe

    private def route(arena: Arena, actions: MemorySegment, fd: Int, sink: StdioSink, pipe: Maybe[(Int, Int)]): Unit =
        sink match
            case StdioSink.Pipe                 => pipe.foreach((_, write) => check("adddup2", C.addDup2.invokeExact(actions, write, fd)))
            case StdioSink.Inherit              => inherit(actions, fd)
            case StdioSink.ToFile(path, append) =>
                val flags = OpenWrite | OpenCreate | (if append then OpenAppend else OpenTruncate)
                val name  = arena.allocateFrom(path.toString)
                // mode_t is 16 bits on macOS and 32 on Linux.
                val status: Int =
                    if Mac then C.addOpen.invokeExact(actions, fd, name, flags, FileMode.toShort)
                    else C.addOpen.invokeExact(actions, fd, name, flags, FileMode)
                check("addopen", status)

    // Under POSIX_SPAWN_CLOEXEC_DEFAULT a descriptor no file action names is closed in the child, the standard streams included.
    private def inherit(actions: MemorySegment, fd: Int): Unit =
        C.addInherit.foreach(handle => check("addinherit_np", handle.invokeExact(actions, fd)))

    private def strings(arena: Arena, values: Chunk[String]): MemorySegment =
        val array = arena.allocate(ADDRESS, values.size + 1L)
        values.zipWithIndex.foreach((value, index) => array.setAtIndex(ADDRESS, index.toLong, arena.allocateFrom(value)))
        array.setAtIndex(ADDRESS, values.size.toLong, MemorySegment.NULL)
        array
    end strings

    private def reader(fd: Int, opened: scala.collection.mutable.ArrayBuffer[Int]): InputStream =
        val stream = new FileInputStream(s"/dev/fd/$fd")
        close(fd, opened)
        stream
    end reader

    private def writer(fd: Int, opened: scala.collection.mutable.ArrayBuffer[Int]): OutputStream =
        val stream = new FileOutputStream(s"/dev/fd/$fd")
        close(fd, opened)
        stream
    end writer

    // The JDK's stream for a stdin it does not pipe: a write fails as on a closed stream.
    private def closedWriter(): OutputStream =
        val stream = OutputStream.nullOutputStream()
        stream.close()
        stream
    end closedWriter

    private def close(fd: Int, opened: scala.collection.mutable.ArrayBuffer[Int]): Unit =
        if opened.contains(fd) then
            opened -= fd
            val _: Int = C.close.invokeExact(fd)

    private def errnoOf(state: MemorySegment): Int = C.errno.get(state, 0L)

    private def check(call: String, status: Int): Unit =
        if status != 0 then throw new IOException(s"$call failed with $status")

    /** macOS's exit notification: one kqueue watching every unreaped leader for `NOTE_EXIT`, and one daemon thread waiting on it, as the
      * JDK keeps a thread waiting on each process it starts.
      */
    private object ExitWatch:

        // struct kevent on 64-bit macOS: ident (8 bytes), filter (2), flags (2), fflags (4), data (8), udata (8).
        private val EventSize    = 32L
        private val FilterProc   = (-5).toShort
        private val AddOnce      = (0x0001 | 0x0010).toShort
        private val NoteExit     = 0x80000000
        private val ErrnoMissing = 3
        private val Batch        = 16

        private val queue   = C.kqueue.invokeExact(): Int
        private val waiting = new java.util.concurrent.ConcurrentHashMap[Long, CompletableFuture[ExitCode]]

        locally {
            val thread = new Thread(() => loop(), "kyo-process-exit")
            thread.setDaemon(true)
            thread.start()
        }

        def watch(pid: Long): CompletableFuture[ExitCode] =
            val exit = new CompletableFuture[ExitCode]
            discard(waiting.put(pid, exit))
            val arena = Arena.ofConfined()
            try
                val change = arena.allocate(EventSize, 8).fill(0.toByte)
                change.set(JAVA_LONG, 0L, pid)
                change.set(JAVA_SHORT, 8L, FilterProc)
                change.set(JAVA_SHORT, 10L, AddOnce)
                change.set(JAVA_INT, 12L, NoteExit)
                val state       = arena.allocate(C.stateLayout)
                val status: Int = C.kevent.invokeExact(state, queue, change, 1, MemorySegment.NULL, 0, MemorySegment.NULL)
                // A process that exited before the watch is registered is already a zombie, which kqueue no longer watches.
                if status == -1 && errnoOf(state) == ErrnoMissing then complete(pid)
                else if status == -1 then
                    discard(waiting.remove(pid))
                    discard(exit.completeExceptionally(new IOException(s"kevent failed with errno ${errnoOf(state)}")))
                end if
            finally arena.close()
            end try
            exit
        end watch

        private def complete(pid: Long): Unit =
            val exit = waiting.remove(pid)
            if exit != null then discard(exit.complete(exitCode(pid)))

        private def loop(): Unit =
            val events = Arena.global().allocate(EventSize * Batch, 8)
            while true do
                val ready: Int = C.keventWait.invokeExact(queue, MemorySegment.NULL, 0, events, Batch, MemorySegment.NULL)
                (0 until ready).foreach(index => complete(events.get(JAVA_LONG, index * EventSize)))
            end while
        end loop

    end ExitWatch

    // Bound on first use, so a program that never starts a group never binds a native function, which the JDK reports as a
    // restricted-method warning without --enable-native-access.
    private object C:
        private val linker  = Linker.nativeLinker()
        private val lookup  = linker.defaultLookup()
        private val capture = Linker.Option.captureCallState("errno")

        val stateLayout: MemoryLayout = Linker.Option.captureStateLayout()
        val errno: VarHandle          = stateLayout.varHandle(MemoryLayout.PathElement.groupElement("errno"))

        private def find(name: String, descriptor: FunctionDescriptor, options: Linker.Option*): Maybe[MethodHandle] =
            val symbol = lookup.find(name)
            if symbol.isPresent then Present(linker.downcallHandle(symbol.get, descriptor, options*)) else Absent

        private def bind(name: String, descriptor: FunctionDescriptor, options: Linker.Option*): MethodHandle =
            find(name, descriptor, options*).getOrElse(throw new UnsatisfiedLinkError(s"$name is not in the C library"))

        val attrInit       = bind("posix_spawnattr_init", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val attrDestroy    = bind("posix_spawnattr_destroy", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val attrSetflags   = bind("posix_spawnattr_setflags", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_SHORT))
        val attrSetpgroup  = bind("posix_spawnattr_setpgroup", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
        val attrSetsigmask = bind("posix_spawnattr_setsigmask", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        val sigemptyset    = bind("sigemptyset", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val actionsInit    = bind("posix_spawn_file_actions_init", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val actionsDestroy = bind("posix_spawn_file_actions_destroy", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val addDup2        = bind("posix_spawn_file_actions_adddup2", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT))
        val addOpen        = bind(
            "posix_spawn_file_actions_addopen",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, if Mac then JAVA_SHORT else JAVA_INT)
        )
        val addChdir   = bind("posix_spawn_file_actions_addchdir_np", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        val addInherit =
            if Mac then find("posix_spawn_file_actions_addinherit_np", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)) else Absent
        val addClosefrom = find("posix_spawn_file_actions_addclosefrom_np", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
        val spawnp       = bind("posix_spawnp", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
        val pipe         = bind("pipe", FunctionDescriptor.of(JAVA_INT, ADDRESS), capture)
        val pipe2        = find("pipe2", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), capture)
        val fcntl        = bind("fcntl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT), Linker.Option.firstVariadicArg(2))
        val close        = bind("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
        val kill         = bind("kill", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT), capture)
        val waitid       = bind("waitid", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT))
        val waitpid      = bind("waitpid", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), capture)

        // macOS only, bound when ExitWatch is first used.
        private val keventDescriptor = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)
        lazy val kqueue              = bind("kqueue", FunctionDescriptor.of(JAVA_INT))
        lazy val kevent              = bind("kevent", keventDescriptor, capture)
        lazy val keventWait          = bind("kevent", keventDescriptor)
    end C

end ProcessGroupPlatform
