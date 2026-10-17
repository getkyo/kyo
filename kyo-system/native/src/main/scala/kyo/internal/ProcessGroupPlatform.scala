package kyo.internal

import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kyo.*
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.dlfcn
import scala.scalanative.posix.errno.EACCES
import scala.scalanative.posix.errno.EINTR
import scala.scalanative.posix.errno.EISDIR
import scala.scalanative.posix.errno.ENOENT
import scala.scalanative.posix.errno.ENOEXEC
import scala.scalanative.posix.errno.EPERM
import scala.scalanative.posix.errno.ESRCH
import scala.scalanative.posix.errno.errno
import scala.scalanative.posix.fcntl
import scala.scalanative.posix.signal as posixsignal
import scala.scalanative.posix.spawn.*
import scala.scalanative.posix.sys.wait as posixwait
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Starts a program as the leader of a new process group, and signals, observes and reaps it, through the C library.
  *
  * Scala Native's `ProcessBuilder` cannot start a child in a new group, so the program is started with `posix_spawnp` and
  * `POSIX_SPAWN_SETPGROUP`, under [[ProcessSpawnPlatform]]'s exclusion like every other spawn here. Its exit is observed without reaping
  * it, so the leader holds its pid, and so its group's id, until [[reap]], which runs after the group is killed: through kqueue's
  * `NOTE_EXIT` on macOS, whose `waitid` reports a stopped child as exited, and through `waitid` with `WNOWAIT` on Linux.
  *
  * kyo-system ignores SIGPIPE on Native, and an ignored signal survives exec, so the child gets it back at its default. The child sees no
  * descriptor it was not given, though Scala Native opens files without `FD_CLOEXEC`: `POSIX_SPAWN_CLOEXEC_DEFAULT` on macOS,
  * `addclosefrom_np` where the C library has it (glibc), and on musl a close for each descriptor ([[closeEach]]). The pipes kept here
  * carry `FD_CLOEXEC` as well, and they are read and written through their own descriptors, since a file stream Scala Native opens on
  * `/dev/fd/<n>` would drop it.
  *
  * Windows has no process groups: a group spawn fails there with [[ProcessGroupUnsupportedException]] before it reaches this object, so
  * nothing reaches these functions. Each one still tests `LinktimeInfo.isWindows` as
  * its own `if`'s condition, which the linker resolves and so keeps the POSIX calls out of a Windows link; the same test behind a method,
  * even an `inline` one, is a runtime branch and leaves them in.
  */
private[kyo] object ProcessGroupPlatform:

    import GroupProcessUnsafe.Spawned

    // The same on every POSIX host.
    val SigKill: Int = 9
    val SigTerm: Int = 15

    // The C headers' values that differ between macOS and Linux; glibc and musl agree on every one.
    private val CloexecDefault = 0x4000
    private val CodeOffset     = 8
    private val StatusOffset   = if LinktimeInfo.isMac then 20 else 24
    private val CodeExited     = 1
    private val IdPid          = 1
    private val FileMode       = 420 // 0644
    private val SiginfoSize    = 256
    private val SigsetSize     = 128
    // The descriptors other threads may open during a spawn on musl and still have closed in the child (closeEach).
    private val CloseMargin = 1024

    def spawn(
        args: Chunk[String],
        workDir: Maybe[kyo.Path],
        environment: Map[String, String],
        stdin: Process.Input,
        stdout: StdioSink,
        stderr: StdioSink,
        redirectError: Boolean
    )(using Frame): Result[CommandException, Spawned] =
        val program = args.headMaybe.getOrElse("")
        if LinktimeInfo.isWindows then Result.fail(ProcessGroupUnsupportedException(program))
        else
            ProcessSpawnPlatform.exclusive {
                // Every descriptor opened here; each is closed or handed to a stream before the spawn returns.
                val opened = scala.collection.mutable.ArrayBuffer.empty[Int]
                try
                    val input  = Maybe.when(stdin != Process.Input.Inherit)(pipe(opened))
                    val output = Maybe.when(stdout == StdioSink.Pipe)(pipe(opened))
                    val error  = Maybe.when(!redirectError && stderr == StdioSink.Pipe)(pipe(opened))
                    val status = start(program, args, workDir, environment, input, output, error, stdout, stderr, redirectError)
                    // The child holds its ends now, or nobody does if the spawn failed.
                    input.foreach((read, _) => close(read, opened))
                    output.foreach((_, write) => close(write, opened))
                    error.foreach((_, write) => close(write, opened))
                    status match
                        case Result.Success(pid) =>
                            input.foreach((_, write) => discard(opened -= write))
                            output.foreach((read, _) => discard(opened -= read))
                            error.foreach((read, _) => discard(opened -= read))
                            Result.succeed(Spawned(
                                pid,
                                input.map((_, write) => new PipeOutputStream(write)).getOrElse(closedWriter()),
                                output.map((read, _) => new PipeInputStream(read)).getOrElse(InputStream.nullInputStream()),
                                error.map((read, _) => new PipeInputStream(read)).getOrElse(InputStream.nullInputStream())
                            ))
                        case Result.Failure(code) if code == ENOENT => Result.fail(ProgramNotFoundException(program))
                        case Result.Failure(code) if code == EACCES || code == EPERM || code == ENOEXEC || code == EISDIR =>
                            Result.fail(PermissionDeniedException(program))
                        case Result.Failure(code) => Result.panic(new IOException(s"posix_spawnp of $program failed with errno $code"))
                        case Result.Panic(cause)  => Result.panic(cause)
                    end match
                catch
                    case e: IOException => Result.panic(e)
                finally opened.toList.foreach(fd => close(fd, opened))
                end try
            }
        end if
    end spawn

    /** Completes with the program's exit code at its exit, leaving it unreaped. */
    def awaitExit(pid: Long)(using AllowUnsafe): CompletableFuture[ExitCode] =
        if LinktimeInfo.isMac then ExitWatch.watch(pid)
        else if LinktimeInfo.isWindows then
            val exit = new CompletableFuture[ExitCode]
            discard(exit.completeExceptionally(new UnsupportedOperationException(s"process groups need a POSIX host: $pid")))
            exit
        else
            val exit = new CompletableFuture[ExitCode]
            // waitid with WNOWAIT blocks until the exit and leaves the child unreaped; Linux reports exits only. Scala Native's own process
            // watcher reaps any child with waitpid(-1) on a host without pidfd (Linux before 5.3, 32-bit), and a leader it reaps is gone
            // with its exit code.
            val thread = new Thread(
                () =>
                    val info = stackalloc[Byte](SiginfoSize)
                    @scala.annotation.tailrec
                    def await(): Int =
                        val status = LibGroup.waitid(IdPid, pid.toUInt, info, posixwait.WEXITED | posixwait.WNOWAIT)
                        if status == -1 && errno == EINTR then await() else if status == -1 then errno else 0
                    end await
                    await() match
                        case 0    => discard(exit.complete(exitCode(pid)))
                        case code => discard(exit.completeExceptionally(new IOException(s"waitid on $pid failed with errno $code")))
                ,
                s"kyo-process-exit-$pid"
            )
            thread.setDaemon(true)
            thread.start()
            exit
    end awaitExit

    def signal(pid: Long, number: Int): Unit =
        if !LinktimeInfo.isWindows then discard(posixsignal.kill(pid.toInt, number))

    def signalGroup(group: Long, number: Int): Unit =
        if !LinktimeInfo.isWindows then discard(posixsignal.kill(-group.toInt, number))

    def groupExists(group: Long): Boolean =
        if LinktimeInfo.isWindows then false
        else posixsignal.kill(-group.toInt, 0) == 0 || errno == EPERM

    /** Collects the exited program, releasing its pid; false while it is still running. */
    def reap(pid: Long): Boolean =
        if LinktimeInfo.isWindows then true
        else
            val status = stackalloc[CInt]()
            @scala.annotation.tailrec
            def loop(): Boolean =
                val reaped = posixwait.waitpid(pid.toInt, status, posixwait.WNOHANG)
                if reaped == -1 && errno == EINTR then loop() else reaped == pid.toInt
            end loop
            loop()
    end reap

    // posix_spawnp's own errno on failure, which it returns rather than setting.
    private def start(
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
        Zone.acquire { implicit zone =>
            val attr    = stackalloc[posix_spawnattr_t]()
            val actions = stackalloc[posix_spawn_file_actions_t]()
            // posixlib sizes sigset_t as a pointer; glibc's is 128 bytes and macOS's 4, so the sets are allocated at the larger.
            val mask    = stackalloc[Byte](SigsetSize).asInstanceOf[Ptr[posixsignal.sigset_t]]
            val default = stackalloc[Byte](SigsetSize).asInstanceOf[Ptr[posixsignal.sigset_t]]
            check("posix_spawnattr_init", posix_spawnattr_init(attr))
            try
                check("posix_spawn_file_actions_init", posix_spawn_file_actions_init(actions))
                try
                    val flags =
                        POSIX_SPAWN_SETPGROUP | POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETSIGDEF |
                            (if LinktimeInfo.isMac then CloexecDefault else 0)
                    check("posix_spawnattr_setflags", posix_spawnattr_setflags(attr, flags.toShort))
                    check("posix_spawnattr_setpgroup", posix_spawnattr_setpgroup(attr, 0))
                    check("sigemptyset", posixsignal.sigemptyset(mask))
                    check("posix_spawnattr_setsigmask", posix_spawnattr_setsigmask(attr, mask))
                    check("sigemptyset", posixsignal.sigemptyset(default))
                    check("sigaddset", posixsignal.sigaddset(default, posixsignal.SIGPIPE))
                    check("posix_spawnattr_setsigdefault", posix_spawnattr_setsigdefault(attr, default))
                    input match
                        case Present((read, _)) => check("adddup2", posix_spawn_file_actions_adddup2(actions, read, 0))
                        case Absent             => inherit(actions, 0)
                    route(actions, 1, stdout, output)
                    if redirectError then check("adddup2", posix_spawn_file_actions_adddup2(actions, 1, 2))
                    else route(actions, 2, stderr, error)
                    workDir.foreach(path =>
                        check("addchdir_np", LibGroup.posix_spawn_file_actions_addchdir_np(actions, toCString(path.toString)))
                    )
                    // Last: before the dup2s above, these closes would take the pipe ends they read.
                    closeFrom(actions)
                    val pid    = stackalloc[CInt]()
                    val argv   = strings(args)
                    val envp   = strings(Chunk.from(environment.map((name, value) => s"$name=$value")))
                    val status = posix_spawnp(pid, toCString(program), actions, attr, argv, envp)
                    if status == 0 then Result.succeed((!pid).toLong) else Result.fail(status)
                finally discard(posix_spawn_file_actions_destroy(actions))
                end try
            finally discard(posix_spawnattr_destroy(attr))
            end try
        }
    end start

    private def exitCode(pid: Long): ExitCode =
        val info = stackalloc[Byte](SiginfoSize)
        discard(LibGroup.waitid(IdPid, pid.toUInt, info, posixwait.WEXITED | posixwait.WNOHANG | posixwait.WNOWAIT))
        val code   = !(info + CodeOffset).asInstanceOf[Ptr[CInt]]
        val status = !(info + StatusOffset).asInstanceOf[Ptr[CInt]]
        if code == CodeExited then ExitCode(status) else ExitCode.Signaled(status)
    end exitCode

    private def pipe(opened: scala.collection.mutable.ArrayBuffer[Int]): (Int, Int) =
        val ends = stackalloc[CInt](2)
        if unistd.pipe(ends) != 0 then throw new IOException(s"pipe failed with errno $errno")
        val read  = ends(0)
        val write = ends(1)
        opened += read
        opened += write
        check("fcntl", fcntl.fcntl(read, fcntl.F_SETFD, fcntl.FD_CLOEXEC))
        check("fcntl", fcntl.fcntl(write, fcntl.F_SETFD, fcntl.FD_CLOEXEC))
        (read, write)
    end pipe

    private def route(actions: Ptr[posix_spawn_file_actions_t], fd: Int, sink: StdioSink, pipe: Maybe[(Int, Int)])(using Zone): Unit =
        sink match
            case StdioSink.Pipe    => pipe.foreach((_, write) => check("adddup2", posix_spawn_file_actions_adddup2(actions, write, fd)))
            case StdioSink.Inherit => inherit(actions, fd)
            case StdioSink.ToFile(path, append) =>
                val flags = fcntl.O_WRONLY | fcntl.O_CREAT | (if append then fcntl.O_APPEND else fcntl.O_TRUNC)
                check("addopen", posix_spawn_file_actions_addopen(actions, fd, toCString(path.toString), flags, FileMode.toUInt))

    // Under POSIX_SPAWN_CLOEXEC_DEFAULT a descriptor no file action names is closed in the child, the standard streams included.
    private def inherit(actions: Ptr[posix_spawn_file_actions_t], fd: Int): Unit =
        if LinktimeInfo.isMac then check("addinherit_np", LibGroupMac.posix_spawn_file_actions_addinherit_np(actions, fd))

    // glibc exports addclosefrom_np and musl does not, so it is found at run time rather than linked.
    private def closeFrom(actions: Ptr[posix_spawn_file_actions_t])(using Zone): Unit =
        if LinktimeInfo.isLinux then
            val symbol = dlfcn.dlsym(null, toCString("posix_spawn_file_actions_addclosefrom_np"))
            if symbol != null then
                val addClosefrom = CFuncPtr.fromPtr[CFuncPtr2[Ptr[posix_spawn_file_actions_t], CInt, CInt]](symbol)
                check("addclosefrom_np", addClosefrom(actions, 3))
            else closeEach(actions)
            end if
        end if
    end closeFrom

    // A close in the child for each descriptor from 3 to the highest one open now plus CloseMargin. One another thread opens before the
    // spawn takes the lowest free number, so it is under that bound unless more than CloseMargin open in between. musl's child ignores
    // the close of a descriptor that is not open; glibc's refuses one at or above _SC_OPEN_MAX, which bounds the range.
    private def closeEach(actions: Ptr[posix_spawn_file_actions_t]): Unit =
        val highest = Maybe(new java.io.File("/proc/self/fd").list()).fold(2)(_.flatMap(_.toIntOption).maxOption.getOrElse(2))
        val openMax = unistd.sysconf(unistd._SC_OPEN_MAX).toLong
        (3 to math.min(highest + CloseMargin, openMax - 1).toInt).foreach(fd =>
            check("addclose", posix_spawn_file_actions_addclose(actions, fd))
        )
    end closeEach

    private def strings(values: Chunk[String])(using Zone): Ptr[CString] =
        val array = alloc[CString](values.size + 1)
        values.zipWithIndex.foreach((value, index) => array(index) = toCString(value))
        array(values.size) = null
        array
    end strings

    // The JDK's stream for a stdin it does not pipe: a write fails as on a closed stream.
    private def closedWriter(): OutputStream =
        val stream = OutputStream.nullOutputStream()
        stream.close()
        stream
    end closedWriter

    private def close(fd: Int, opened: scala.collection.mutable.ArrayBuffer[Int]): Unit =
        if opened.contains(fd) then
            opened -= fd
            discard(unistd.close(fd))

    private def check(call: String, status: CInt): Unit =
        if status != 0 then throw new IOException(s"$call failed with $status")

    /** A pipe end read through its own descriptor. */
    final private class PipeInputStream(fd: Int) extends InputStream:
        private val closed = new AtomicBoolean(false)

        override def read(): Int =
            val one = new Array[Byte](1)
            if read(one, 0, 1) <= 0 then -1 else one(0) & 0xff

        override def read(bytes: Array[Byte], offset: Int, length: Int): Int =
            if length == 0 then 0
            else if closed.get then throw new IOException("Stream closed")
            else
                @scala.annotation.tailrec
                def loop(): Int =
                    val count = unistd.read(fd, bytes.at(offset), length.toCSize).toInt
                    if count == -1 && errno == EINTR then loop()
                    else if count < 0 then throw new IOException(s"read failed with errno $errno")
                    else if count == 0 then -1
                    else count
                    end if
                end loop
                loop()
        end read

        override def close(): Unit = if closed.compareAndSet(false, true) then discard(unistd.close(fd))
    end PipeInputStream

    /** A pipe end written through its own descriptor. */
    final private class PipeOutputStream(fd: Int) extends OutputStream:
        private val closed = new AtomicBoolean(false)

        override def write(byte: Int): Unit = write(Array(byte.toByte), 0, 1)

        override def write(bytes: Array[Byte], offset: Int, length: Int): Unit =
            if closed.get then throw new IOException("Stream closed")
            @scala.annotation.tailrec
            def loop(written: Int): Unit =
                if written < length then
                    val count = unistd.write(fd, bytes.at(offset + written), (length - written).toCSize).toInt
                    if count == -1 && errno == EINTR then loop(written)
                    else if count < 0 then throw new IOException(s"write failed with errno $errno")
                    else loop(written + count)
            loop(0)
        end write

        override def close(): Unit = if closed.compareAndSet(false, true) then discard(unistd.close(fd))
    end PipeOutputStream

    /** macOS's exit notification: one kqueue watching every unreaped leader for `NOTE_EXIT`, and one daemon thread waiting on it. */
    private object ExitWatch:

        // struct kevent on 64-bit macOS: ident (8 bytes), filter (2), flags (2), fflags (4), data (8), udata (8).
        private val EventSize  = 32
        private val FilterProc = (-5).toShort
        private val AddOnce    = (0x0001 | 0x0010).toShort
        private val NoteExit   = 0x80000000
        private val Batch      = 16

        private val queue   = LibGroupMac.kqueue()
        private val waiting = new java.util.concurrent.ConcurrentHashMap[Long, CompletableFuture[ExitCode]]

        locally {
            val thread = new Thread(() => loop(), "kyo-process-exit")
            thread.setDaemon(true)
            thread.start()
        }

        def watch(pid: Long): CompletableFuture[ExitCode] =
            val exit = new CompletableFuture[ExitCode]
            discard(waiting.put(pid, exit))
            val change = stackalloc[Byte](EventSize)
            !change.asInstanceOf[Ptr[Long]] = pid
            !(change + 8).asInstanceOf[Ptr[CShort]] = FilterProc
            !(change + 10).asInstanceOf[Ptr[CShort]] = AddOnce
            !(change + 12).asInstanceOf[Ptr[CInt]] = NoteExit
            val status = LibGroupMac.kevent(queue, change, 1, null, 0, null)
            // A process that exited before the watch is registered is already a zombie, which kqueue no longer watches.
            if status == -1 && errno == ESRCH then complete(pid)
            else if status == -1 then
                discard(waiting.remove(pid))
                discard(exit.completeExceptionally(new IOException(s"kevent failed with errno $errno")))
            end if
            exit
        end watch

        private def complete(pid: Long): Unit =
            val exit = waiting.remove(pid)
            if exit != null then discard(exit.complete(exitCode(pid)))

        private def loop(): Unit =
            val events = stackalloc[Byte](EventSize * Batch)
            while true do
                val ready = LibGroupMac.keventWait(queue, null, 0, events, Batch, null)
                (0 until ready).foreach(index => complete(!(events + index * EventSize).asInstanceOf[Ptr[Long]]))
            end while
        end loop

    end ExitWatch

end ProcessGroupPlatform

// A call that can block is marked @blocking: Scala Native's GC waits for every thread in managed code to reach a safepoint, and a thread
// parked in an unmarked call never does, so the GC stops the program after 60 seconds.
@extern
private[internal] object LibGroup:
    def posix_spawn_file_actions_addchdir_np(actions: Ptr[posix_spawn_file_actions_t], path: CString): CInt = extern
    @blocking def waitid(idtype: CInt, id: CUnsignedInt, info: Ptr[Byte], options: CInt): CInt              = extern
end LibGroup

// macOS only: every use sits behind LinktimeInfo.isMac, so no other host links these.
@extern
private[internal] object LibGroupMac:
    def posix_spawn_file_actions_addinherit_np(actions: Ptr[posix_spawn_file_actions_t], fd: CInt): CInt                          = extern
    def kqueue(): CInt                                                                                                            = extern
    def kevent(queue: CInt, changes: Ptr[Byte], changeCount: CInt, events: Ptr[Byte], eventCount: CInt, timeout: Ptr[Byte]): CInt =
        extern
    // kevent as the watch loop calls it, waiting for events.
    @blocking @name("kevent")
    def keventWait(queue: CInt, changes: Ptr[Byte], changeCount: CInt, events: Ptr[Byte], eventCount: CInt, timeout: Ptr[Byte]): CInt =
        extern
end LibGroupMac
