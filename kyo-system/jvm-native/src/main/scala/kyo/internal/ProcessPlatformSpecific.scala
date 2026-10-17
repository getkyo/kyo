package kyo.internal

import java.io.*
import java.lang.Process as JProcess
import java.lang.ProcessBuilder as JProcessBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.Path as JPath
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kyo.*
import scala.jdk.CollectionConverters.*

// --- InputFeeds — the stop actions of a process's stdin feeds ---

/** The stop actions of the fibers feeding a process's stdin. Retained so a feed ends with the process rather than running on after it,
  * which is what an unheld feed does.
  */
final private[kyo] class InputFeeds:

    private val stops = new java.util.concurrent.ConcurrentLinkedQueue[() => Unit]()

    def register(stop: () => Unit): Unit = discard(stops.add(stop))

    /** Stops every feed. Idempotent, and safe to call after the process has already exited: a feed can still be parked reading its source
      * even when the child is gone.
      */
    def stopAll(): Unit =
        var stop = stops.poll()
        while stop != null do
            stop()
            stop = stops.poll()
        end while
    end stopAll

end InputFeeds

// --- JvmProcessUnsafe — concrete Process.Unsafe backed by java.lang.Process ---

final private[kyo] class JvmProcessUnsafe(private[internal] val jp: JProcess) extends Process.Unsafe:

    private[internal] val feeds = new InputFeeds

    override def stopInputFeeds()(using AllowUnsafe): Unit = feeds.stopAll()

    def waitFor()(using AllowUnsafe, Frame): Fiber.Unsafe[ExitCode, Any] =
        val p = Promise.Unsafe.init[ExitCode, Any]()
        discard(jp.onExit().whenComplete { (exitedProcess, error) =>
            if error == null then
                p.completeDiscard(Result.succeed(ExitCode(exitedProcess.exitValue())))
            else
                p.completeDiscard(Result.panic(error))
        })
        p
    end waitFor

    def waitFor(timeout: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Maybe[ExitCode], Any] =
        val p = Promise.Unsafe.init[Maybe[ExitCode], Any]()
        discard(jp.onExit()
            .orTimeout(timeout.toMillis, TimeUnit.MILLISECONDS)
            .whenComplete { (exitedProcess, error) =>
                error match
                    case _: java.util.concurrent.TimeoutException =>
                        p.completeDiscard(Result.succeed(Absent))
                    case null =>
                        p.completeDiscard(Result.succeed(Present(ExitCode(exitedProcess.exitValue()))))
                    case other =>
                        p.completeDiscard(Result.panic(other))
            })
        p
    end waitFor

    def exitCode()(using AllowUnsafe): Maybe[ExitCode] =
        if jp.isAlive then Absent else Present(ExitCode(jp.exitValue()))

    def destroy()(using AllowUnsafe): Unit         = jp.destroy()
    def destroyForcibly()(using AllowUnsafe): Unit = discard(jp.destroyForcibly())
    def isAlive()(using AllowUnsafe): Boolean      = jp.isAlive
    def pid()(using AllowUnsafe): Long             = jp.pid()
    def stdoutJava(using AllowUnsafe): InputStream = jp.getInputStream
    def stderrJava(using AllowUnsafe): InputStream = jp.getErrorStream
    def stdinJava(using AllowUnsafe): OutputStream = jp.getOutputStream

end JvmProcessUnsafe

// --- GroupProcessUnsafe — a program started as the leader of its own process group ---

/** A program started as the leader of its own process group, through [[ProcessGroupPlatform]] (see [[Command.processGroup]]).
  *
  * Its exit is observed without reaping it, so its pid, which is its group's id, stays held from the exit until [[reap]], which
  * [[Process.destroyTree]] runs once the group is killed. `isAlive` reads the exit rather than `ProcessHandle.isAlive`, which answers true
  * for the unreaped leader. A signal goes to the pid only before the reap: after it, the pid may name another process.
  */
final private[kyo] class GroupProcessUnsafe(spawned: GroupProcessUnsafe.Spawned, exit: CompletableFuture[ExitCode])
    extends Process.Unsafe:

    private[internal] val feeds = new InputFeeds

    // Read and written under this object's monitor, which a signal also holds, so no signal reaches a pid the reap released. The section
    // is one non-blocking system call.
    private var reaped = false

    override def stopInputFeeds()(using AllowUnsafe): Unit = feeds.stopAll()

    def waitFor()(using AllowUnsafe, Frame): Fiber.Unsafe[ExitCode, Any] =
        val p = Promise.Unsafe.init[ExitCode, Any]()
        discard(exit.whenComplete { (code, error) =>
            if error == null then p.completeDiscard(Result.succeed(code))
            else p.completeDiscard(Result.panic(error))
        })
        p
    end waitFor

    def waitFor(timeout: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Maybe[ExitCode], Any] =
        val p = Promise.Unsafe.init[Maybe[ExitCode], Any]()
        // A copy: orTimeout completes the future it is given, and the exit is shared by every wait.
        discard(exit.copy()
            .orTimeout(timeout.toMillis, TimeUnit.MILLISECONDS)
            .whenComplete { (code, error) =>
                error match
                    case _: java.util.concurrent.TimeoutException => p.completeDiscard(Result.succeed(Absent))
                    case null                                     => p.completeDiscard(Result.succeed(Present(code)))
                    case other                                    => p.completeDiscard(Result.panic(other))
            })
        p
    end waitFor

    def exitCode()(using AllowUnsafe): Maybe[ExitCode] =
        if exit.isDone && !exit.isCompletedExceptionally then Present(exit.join()) else Absent

    def destroy()(using AllowUnsafe): Unit = signal(ProcessGroupPlatform.SigTerm)

    def destroyForcibly()(using AllowUnsafe): Unit = signal(ProcessGroupPlatform.SigKill)

    override private[kyo] def processGroups()(using AllowUnsafe): Chunk[Long] =
        synchronized(if reaped then Chunk.empty else Chunk(spawned.pid))

    override private[kyo] def killGroups()(using AllowUnsafe): Unit =
        synchronized(if !reaped then ProcessGroupPlatform.signalGroup(spawned.pid, ProcessGroupPlatform.SigKill))

    override private[kyo] def reap()(using AllowUnsafe): Unit =
        synchronized(if !reaped then reaped = ProcessGroupPlatform.reap(spawned.pid))

    def isAlive()(using AllowUnsafe): Boolean      = !exit.isDone
    def pid()(using AllowUnsafe): Long             = spawned.pid
    def stdoutJava(using AllowUnsafe): InputStream = spawned.stdout
    def stderrJava(using AllowUnsafe): InputStream = spawned.stderr
    def stdinJava(using AllowUnsafe): OutputStream = spawned.stdin

    private def signal(number: Int): Unit =
        synchronized(if !reaped then ProcessGroupPlatform.signal(spawned.pid, number))

end GroupProcessUnsafe

private[kyo] object GroupProcessUnsafe:

    /** What [[ProcessGroupPlatform.spawn]] started: the leader's pid and the streams of its standard descriptors. */
    final case class Spawned(pid: Long, stdin: OutputStream, stdout: InputStream, stderr: InputStream)

end GroupProcessUnsafe

import Command.EnvMode

// --- StdioSink — destination for stdout / stderr ---

private[kyo] enum StdioSink derives CanEqual:
    case Pipe
    case Inherit
    case ToFile(path: kyo.Path, append: Boolean)
end StdioSink

// --- JvmCommandUnsafe — concrete Command.Unsafe backed by java.lang.ProcessBuilder ---

final private[kyo] class JvmCommandUnsafe(
    val args: Chunk[String],
    val workDir: Maybe[kyo.Path] = Absent,
    val envMode: EnvMode = EnvMode.Inherit,
    val stdinSource: Process.Input = Process.Input.Inherit,
    val stdinStream: Maybe[Stream[Byte, Sync]] = Absent,
    val stdoutSink: StdioSink = StdioSink.Pipe,
    val stderrSink: StdioSink = StdioSink.Pipe,
    val redirectError: Boolean = false,
    val pipeTo: Maybe[Command.Unsafe] = Absent,
    val processGroup: Boolean = false
) extends Command.Unsafe:

    // --- Builder methods — all return new instances ---

    def withCwd(path: kyo.Path): JvmCommandUnsafe =
        copy(workDir = Present(path))

    def withEnvAppend(vars: Map[String, String]): JvmCommandUnsafe =
        val newMode = envMode match
            case EnvMode.Clear                         => EnvMode.ClearThenAppend(vars)
            case EnvMode.ClearThenAppend(prev)         => EnvMode.ClearThenAppend(prev ++ vars)
            case EnvMode.Remove(names)                 => EnvMode.AppendThenRemove(vars -- names, names)
            case EnvMode.AppendThenRemove(prev, names) => EnvMode.AppendThenRemove((prev ++ vars) -- names, names)
            case _                                     => EnvMode.Append(vars)
        copy(envMode = newMode)
    end withEnvAppend

    def withEnvRemove(names: Set[String]): JvmCommandUnsafe =
        val newMode = envMode match
            case EnvMode.Inherit =>
                EnvMode.Remove(names)
            case EnvMode.Append(vars) =>
                EnvMode.AppendThenRemove(vars -- names, names)
            case EnvMode.Remove(prev) =>
                EnvMode.Remove(prev ++ names)
            case EnvMode.AppendThenRemove(vars, prev) =>
                EnvMode.AppendThenRemove(vars -- names, prev ++ names)
            case EnvMode.Replace(vars) =>
                EnvMode.Replace(vars -- names)
            case EnvMode.Clear =>
                EnvMode.Clear
            case EnvMode.ClearThenAppend(vars) =>
                EnvMode.ClearThenAppend(vars -- names)
        copy(envMode = newMode)
    end withEnvRemove

    def withEnvReplace(vars: Map[String, String]): JvmCommandUnsafe =
        copy(envMode = EnvMode.Replace(vars))

    def withEnvClear: JvmCommandUnsafe =
        copy(envMode = EnvMode.Clear)

    def withStdin(input: Process.Input): JvmCommandUnsafe =
        copy(stdinSource = input, stdinStream = Absent)

    def withStdinStream(s: Stream[Byte, Sync]): JvmCommandUnsafe =
        copy(stdinStream = Present(s), stdinSource = Process.Input.Inherit)

    def withInheritStdout(value: Boolean): JvmCommandUnsafe =
        copy(stdoutSink = if value then StdioSink.Inherit else StdioSink.Pipe)

    def withInheritStderr(value: Boolean): JvmCommandUnsafe =
        copy(stderrSink = if value then StdioSink.Inherit else StdioSink.Pipe)

    def withStdoutFile(path: kyo.Path, append: Boolean): JvmCommandUnsafe =
        copy(stdoutSink = StdioSink.ToFile(path, append))

    def withStderrFile(path: kyo.Path, append: Boolean): JvmCommandUnsafe =
        copy(stderrSink = StdioSink.ToFile(path, append))

    def withRedirectErrorStream(value: Boolean): JvmCommandUnsafe =
        copy(redirectError = value)

    def withAndThen(that: Command.Unsafe): JvmCommandUnsafe =
        copy(pipeTo = Present(that))

    def withProcessGroup: JvmCommandUnsafe =
        copy(processGroup = true)

    // --- Internal helpers ---

    /** The child's environment for a process-group spawn: `envMode` applied over this process's. The ProcessBuilder path applies `envMode`
      * to `ProcessBuilder.environment()` instead, whose keys are case-insensitive on Windows, where a group spawn does not run.
      */
    private def environment: Map[String, String] =
        val parent = java.lang.System.getenv().asScala.toMap
        envMode match
            case EnvMode.Inherit                       => parent
            case EnvMode.Append(vars)                  => parent ++ vars
            case EnvMode.Remove(names)                 => parent -- names
            case EnvMode.AppendThenRemove(vars, names) => (parent ++ vars) -- names
            case EnvMode.Replace(vars)                 => vars
            case EnvMode.Clear                         => Map.empty
            case EnvMode.ClearThenAppend(vars)         => vars
        end match
    end environment

    /** The failure for a working directory that does not exist, checked before a spawn. */
    private def missingWorkDir(using Frame): Maybe[CommandException] =
        workDir.flatMap { path =>
            val nio = path.unsafe match
                case n: NioPathUnsafe => n.jpath
                case other            => java.nio.file.Paths.get(other.show)
            if java.nio.file.Files.exists(nio) then Absent else Present(WorkingDirectoryNotFoundException(path))
        }

    /** Starts the feed this command's stdin source needs into the spawned process's stdin, held by `feeds`. */
    private def feedStdin(feeds: InputFeeds, processStdin: OutputStream)(using AllowUnsafe, Frame): Unit =
        stdinStream match
            case Present(s) => feeds.register(feedStream(s, processStdin))
            case Absent     =>
                stdinSource match
                    case Process.Input.FromStream(is)               => feeds.register(feedInputStream(is, processStdin))
                    case Process.Input.Inherit | Process.Input.Pipe => ()
        end match
    end feedStdin

    /** The `sh -c` command line a pipeline runs as, each argument quoted. */
    private def shellCommand(chain: Seq[JvmCommandUnsafe]): String =
        def shellEscape(a: String): String = "'" + a.replace("'", "'\\''") + "'"
        chain.map(_.args.map(shellEscape).mkString(" ")).mkString(" | ")

    /** Flattens the pipeline chain into a sequence of commands in order (head = leftmost). */
    private def pipelineChain: Seq[JvmCommandUnsafe] =
        pipeTo match
            case Absent =>
                Seq(this)
            case Present(next: JvmCommandUnsafe) =>
                this +: next.pipelineChain
            case Present(_) =>
                Seq(this)

    /** Converts this command's configuration into a java.lang.ProcessBuilder.
      *
      * @param pipelineIntermediate
      *   When `true` this builder is a non-first stage in a `startPipeline` call. Java requires that all non-first builders have stdin set
      *   to `PIPE` so the JVM can wire the inter-process pipe; the `stdinSource` / `stdinStream` settings are ignored in that case.
      */
    private def toJProcessBuilder(pipelineIntermediate: Boolean = false)(using
        AllowUnsafe,
        Frame
    ): Result[CommandException, JProcessBuilder] =
        missingWorkDir match
            case Present(err) => Result.fail(err)
            case _            =>
                val pb = new JProcessBuilder(args.toSeq.asJava)

                // Working directory
                workDir.foreach { path =>
                    val nio = path.unsafe match
                        case n: NioPathUnsafe => n.jpath
                        case other            => java.nio.file.Paths.get(other.show)
                    discard(pb.directory(nio.toFile))
                }

                // Environment
                envMode match
                    case EnvMode.Inherit      => () // default: inherit parent env
                    case EnvMode.Append(vars) =>
                        pb.environment().putAll(vars.asJava)
                    case EnvMode.Remove(names) =>
                        names.foreach(pb.environment().remove)
                    case EnvMode.AppendThenRemove(vars, names) =>
                        pb.environment().putAll(vars.asJava)
                        names.foreach(pb.environment().remove)
                    case EnvMode.Replace(vars) =>
                        pb.environment().clear()
                        pb.environment().putAll(vars.asJava)
                    case EnvMode.Clear =>
                        pb.environment().clear()
                    case EnvMode.ClearThenAppend(vars) =>
                        pb.environment().clear()
                        pb.environment().putAll(vars.asJava)
                end match

                // stdin
                // Non-first pipeline stages must use PIPE so startPipeline can wire them.
                if pipelineIntermediate then
                    discard(pb.redirectInput(JProcessBuilder.Redirect.PIPE))
                else
                    stdinStream match
                        case Present(_) =>
                            discard(pb.redirectInput(JProcessBuilder.Redirect.PIPE))
                        case Absent =>
                            stdinSource match
                                case Process.Input.Inherit       => discard(pb.redirectInput(JProcessBuilder.Redirect.INHERIT))
                                case Process.Input.FromStream(_) => discard(pb.redirectInput(JProcessBuilder.Redirect.PIPE))
                                case Process.Input.Pipe          => discard(pb.redirectInput(JProcessBuilder.Redirect.PIPE))
                            end match
                    end match
                end if

                // stdout
                stdoutSink match
                    case StdioSink.Pipe                 => discard(pb.redirectOutput(JProcessBuilder.Redirect.PIPE))
                    case StdioSink.Inherit              => discard(pb.redirectOutput(JProcessBuilder.Redirect.INHERIT))
                    case StdioSink.ToFile(path, append) =>
                        val nio = path.unsafe match
                            case n: NioPathUnsafe => n.jpath
                            case other            => java.nio.file.Paths.get(other.show)
                        val redirect =
                            if append then JProcessBuilder.Redirect.appendTo(nio.toFile)
                            else JProcessBuilder.Redirect.to(nio.toFile)
                        discard(pb.redirectOutput(redirect))
                end match

                // stderr
                if redirectError then
                    discard(pb.redirectErrorStream(true))
                else
                    stderrSink match
                        case StdioSink.Pipe                 => discard(pb.redirectError(JProcessBuilder.Redirect.PIPE))
                        case StdioSink.Inherit              => discard(pb.redirectError(JProcessBuilder.Redirect.INHERIT))
                        case StdioSink.ToFile(path, append) =>
                            val nio = path.unsafe match
                                case n: NioPathUnsafe => n.jpath
                                case other            => java.nio.file.Paths.get(other.show)
                            val redirect =
                                if append then JProcessBuilder.Redirect.appendTo(nio.toFile)
                                else JProcessBuilder.Redirect.to(nio.toFile)
                            discard(pb.redirectError(redirect))
                    end match
                end if

                Result.succeed(pb)
        end match
    end toJProcessBuilder

    /** Closes a stream, ignoring the failure a second close or an already-broken stream reports. */
    private def closeQuietly(closeable: java.io.Closeable): Unit =
        try closeable.close()
        catch case _: IOException => ()

    /** Feeds an InputStream into a process's stdin on a carrier fiber, closing when done.
      *
      * Returns the stop action so the caller can retain it and end the feed. A feed that nothing
      * holds cannot be cancelled: interrupting the fiber that spawned the process would leave it
      * reading its source and writing to the child for as long as the source produced bytes.
      *
      * The read blocks, so the carrier parks in it and the scheduler's `BlockingMonitor` drains
      * that worker's queue to the others, the sanctioned-blocking pattern kyo-net's stdio pumps
      * use. The monitor also dispatches `Thread.interrupt()` to a parked carrier whose fiber was
      * interrupted, so the stop action's interrupt reaches a read already parked, not only the
      * safepoint each loop step leaves between reads. Closing the streams covers a source that
      * ignores the interrupt, and closes what the feed owns even when the fiber ends before its
      * own close runs.
      */
    private def feedInputStream(is: InputStream, processStdin: OutputStream)(using AllowUnsafe, Frame): () => Unit =
        val buf = new Array[Byte](8192)
        // Unsafe: spawns the pump from outside the effect system, where spawn() runs.
        val feed = Fiber.Unsafe.init {
            Loop.foreach {
                Sync.defer {
                    try
                        val n = is.read(buf)
                        if n < 0 then
                            processStdin.close()
                            Loop.done
                        else
                            processStdin.write(buf, 0, n)
                            Loop.continue
                        end if
                    catch
                        // A stop interrupts the carrier and closes the streams underneath the read
                        // and the write, so both exceptions are the ordinary way a feed ends rather
                        // than a failure to report. Catching the interrupt here also keeps it off
                        // the scheduler worker the carrier is mounted on.
                        case _: IOException          => Loop.done
                        case _: InterruptedException => Loop.done
                    end try
                }
            }.andThen(Sync.defer(closeQuietly(is)))
        }
        () =>
            discard(feed.interrupt())
            // Closing both ends releases a read or a write already parked, which no interrupt can
            // reach, and closing the source is what the feed itself does when it ends on its own.
            closeQuietly(is)
            closeQuietly(processStdin)
    end feedInputStream

    /** Drains a Stream[Byte, Sync] into a process's stdin on a carrier fiber, closing when done.
      *
      * The stream is evaluated eagerly (it is Sync-only), then the bytes are written on a carrier fiber so that the caller (spawn) is not
      * blocked by the write. Returns the stop action for the same reason as [[feedInputStream]].
      */
    private def feedStream(stream: Stream[Byte, Sync], processStdin: OutputStream)(using AllowUnsafe, Frame): () => Unit =
        val chunk = Abort.run[Nothing](Sync.Unsafe.run(stream.run)).eval.getOrThrow
        val bytes = chunk.toArray
        // Unsafe: spawns the pump from outside the effect system, where spawn() runs.
        val feed = Fiber.Unsafe.init {
            Sync.defer {
                try
                    processStdin.write(bytes)
                    processStdin.close()
                catch
                    case _: IOException          => ()
                    case _: InterruptedException => ()
            }
        }
        () =>
            discard(feed.interrupt())
            closeQuietly(processStdin)
    end feedStream

    /** Reads all bytes from an InputStream and closes it. */
    private def readAll(is: InputStream): Array[Byte] =
        val baos = new java.io.ByteArrayOutputStream()
        val buf  = new Array[Byte](8192)
        var n    = is.read(buf)
        while n >= 0 do
            baos.write(buf, 0, n)
            n = is.read(buf)
        is.close()
        baos.toByteArray
    end readAll

    /** Validates that the command can be found before attempting to spawn.
      *
      * On Scala Native, ProcessBuilder.start() does not throw IOException for a missing program (unlike the JVM); instead the process exits
      * with code 127. We check for program existence up front so spawn() can return Result.Failure synchronously on all platforms.
      *
      * Returns Present(CommandException) if the program is definitely not found, or Absent to proceed.
      */
    private def validateProgram()(using Frame): Maybe[CommandException] =
        if args.isEmpty then Present(ProgramNotFoundException(""))
        else if Platform.isWindows then
            // On Windows, Files.isExecutable is unreliable due to WOW64
            // filesystem redirection and ACL-based permission checks.
            // Let ProcessBuilder.start() handle validation natively.
            Absent
        else
            val cmd = args.head
            if cmd.startsWith("/") || cmd.contains("/") then
                val path = java.nio.file.Paths.get(cmd)
                if !java.nio.file.Files.exists(path) then Present(ProgramNotFoundException(cmd))
                // A folder passes the execute check, which reads its search permission, and Native's spawn reports its exec failure
                // as an exit code rather than an exception, so a folder is refused here on both.
                else if java.nio.file.Files.isDirectory(path) || !java.nio.file.Files.isExecutable(path) then
                    Present(PermissionDeniedException(cmd))
                else Absent
                end if
            else
                val pathEnv = java.lang.System.getenv("PATH")
                val pathStr = if pathEnv != null then pathEnv else ""
                val dirs    = pathStr.split(":")
                val found   = dirs.exists(dir => java.nio.file.Files.isExecutable(java.nio.file.Paths.get(dir, cmd)))
                if found then Absent
                else Present(ProgramNotFoundException(cmd))
            end if
        end if
    end validateProgram

    /** Translates a java.io.IOException from ProcessBuilder.start() into a CommandException. */
    private def translateIOException(e: IOException, cmd: String)(using Frame): CommandException =
        // Every message the JVM raises starts with "Cannot run program", so the permission check comes first.
        val msg = e.getMessage
        if msg != null && (msg.contains("Permission denied") || msg.contains("error=13")) then
            PermissionDeniedException(cmd)
        else
            ProgramNotFoundException(cmd)
        end if
    end translateIOException

    // --- Effectful operations ---

    def spawn()(using AllowUnsafe, Frame): Result[CommandException, Process.Unsafe] =
        // Every child's stdin is a pipe this process may write after the child is gone; the write must fail, not kill us.
        ProcessSignalPlatform.ignoreBrokenPipes()
        // Validate program exists before spawning — on Scala Native, ProcessBuilder.start()
        // does not throw for a missing program (unlike JVM), so we check up front.
        validateProgram() match
            case Present(err) => Result.fail(err)
            case Absent       =>
                val chain = pipelineChain
                if processGroup then spawnGroup(chain)
                else if chain.length == 1 then
                    // Simple (non-piped) spawn
                    toJProcessBuilder() match
                        case Result.Failure(err) => Result.fail(err)
                        case Result.Panic(ex)    => Result.panic(ex)
                        case Result.Success(pb)  =>
                            try
                                val jp   = ProcessSpawnPlatform.start(pb)
                                val proc = new JvmProcessUnsafe(jp)
                                feedStdin(proc.feeds, jp.getOutputStream)
                                Result.succeed(proc)
                            catch
                                case e: IOException => Result.fail(translateIOException(e, args.headMaybe.getOrElse("")))
                else
                    // Pipeline spawn — delegate to `sh -c "cmd1 | cmd2 | ..."` so the OS
                    // kernel handles inter-process piping directly. This avoids the pump-per-stage
                    // deadlocks on Scala Native, where blocking I/O off the calling thread is
                    // unreliable.
                    val firstCmd = chain.head
                    val pb       = new JProcessBuilder("sh", "-c", shellCommand(chain))

                    firstCmd.envMode match
                        case EnvMode.Inherit      => ()
                        case EnvMode.Append(vars) =>
                            vars.foreach { (k, v) => discard(pb.environment().put(k, v)) }
                        case EnvMode.Remove(names) =>
                            names.foreach(pb.environment().remove)
                        case EnvMode.AppendThenRemove(vars, names) =>
                            vars.foreach { (k, v) => discard(pb.environment().put(k, v)) }
                            names.foreach(pb.environment().remove)
                        case EnvMode.Replace(vars) =>
                            pb.environment().clear()
                            vars.foreach { (k, v) => discard(pb.environment().put(k, v)) }
                        case EnvMode.Clear =>
                            pb.environment().clear()
                        case EnvMode.ClearThenAppend(vars) =>
                            pb.environment().clear()
                            vars.foreach { (k, v) => discard(pb.environment().put(k, v)) }
                    end match
                    firstCmd.workDir.foreach(path => discard(pb.directory(new java.io.File(path.toString))))
                    if chain.last.redirectError then discard(pb.redirectErrorStream(true))

                    try
                        val jp   = ProcessSpawnPlatform.start(pb)
                        val proc = new JvmProcessUnsafe(jp)
                        firstCmd.feedStdin(proc.feeds, jp.getOutputStream)
                        Result.succeed(proc)
                    catch
                        case e: IOException =>
                            Result.fail(translateIOException(e, chain.head.args.headMaybe.getOrElse("")))
                    end try
                end if
        end match
    end spawn

    /** Spawns this command, or the pipeline it heads, as the leader of a new process group. A pipeline runs as one `sh -c`, as on the
      * ProcessBuilder path, so every stage is in the shell's group.
      */
    private def spawnGroup(chain: Seq[JvmCommandUnsafe])(using AllowUnsafe, Frame): Result[CommandException, Process.Unsafe] =
        missingWorkDir match
            case Present(err) => Result.fail(err)
            case Absent       =>
                val piped    = chain.length > 1
                val program  = if piped then Chunk("sh", "-c", shellCommand(chain)) else args
                val stdin    = if stdinStream.isDefined then Process.Input.Pipe else stdinSource
                val stdout   = if piped then StdioSink.Pipe else stdoutSink
                val stderr   = if piped then StdioSink.Pipe else stderrSink
                val mergeErr = if piped then chain.last.redirectError else redirectError
                ProcessGroupPlatform.spawn(program, workDir, environment, stdin, stdout, stderr, mergeErr).map { spawned =>
                    val proc = new GroupProcessUnsafe(spawned, ProcessGroupPlatform.awaitExit(spawned.pid))
                    feedStdin(proc.feeds, spawned.stdin)
                    proc
                }
        end match
    end spawnGroup

    def text()(using AllowUnsafe, Frame): Fiber.Unsafe[String, Abort[CommandException]] =
        val p = Promise.Unsafe.init[String, Abort[CommandException]]()
        spawn() match
            case Result.Failure(err) =>
                p.completeDiscard(Result.fail(err))
            case Result.Panic(ex) =>
                p.completeDiscard(Result.panic(ex))
            case Result.Success(proc) =>
                // Drained while the process runs, and completed at end of stream rather than at exit.
                //
                // Reading only after onExit deadlocks any child that writes more than the pipe
                // buffer, roughly 64KB: the child blocks on write, so it never exits, so onExit
                // never fires, so the read that would unblock it never starts. Process.scala
                // documents this hazard for the stdout-plus-stderr case; it was reproduced here on
                // a single stream.
                //
                // End of stream is the right completion point for this operation: it yields the
                // output text and does not report exit status, and the child closes stdout when it
                // exits. The onExit handler remains only to surface a spawn failure, and completing
                // an already-completed promise is a no-op, so whichever arrives first wins.
                val out = proc.stdoutJava
                // Unsafe: bridges a blocking pipe read from outside the effect system. The carrier
                // parks in the read and the scheduler's `BlockingMonitor` drains that worker's
                // queue to the others; the read ends at end of stream, which the child reaches by
                // exiting.
                discard(Fiber.Unsafe.init {
                    Sync.defer {
                        try p.completeDiscard(Result.succeed(new String(readAll(out), StandardCharsets.UTF_8)))
                        catch case e: IOException => p.completeDiscard(Result.panic(e))
                    }
                })
                // No scope holds this process, so a group is killed at the program's exit: nothing else would reap its leader, and a
                // process it left holding stdout would keep the read open.
                proc.waitFor().onComplete {
                    case Result.Panic(error) => p.completeDiscard(Result.panic(error))
                    case _                   => if processGroup then discard(proc.destroyTree())
                }
        end match
        p
    end text

    def waitFor()(using AllowUnsafe, Frame): Fiber.Unsafe[ExitCode, Abort[CommandException]] =
        val p = Promise.Unsafe.init[ExitCode, Abort[CommandException]]()
        spawn() match
            case Result.Failure(err) => p.completeDiscard(Result.fail(err))
            case Result.Panic(ex)    => p.completeDiscard(Result.panic(ex))
            // No scope holds this process, so a group is killed, and its leader reaped, at the program's exit.
            case Result.Success(proc) if processGroup =>
                // Unsafe: starts the wait from outside the effect system, where the unsafe tier runs.
                p.becomeDiscard(Fiber.Unsafe.init(proc.safe.waitFor.map(code => proc.safe.destroyTree.andThen(code))).safe)
            case Result.Success(proc) => p.becomeDiscard(proc.waitFor().safe)
        end match
        p
    end waitFor

    def waitForSuccess()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[CommandException | ExitCode]] =
        // Unsafe: starts the wait from outside the effect system, where the unsafe tier runs.
        Fiber.Unsafe.init(waitFor().safe.get.map(code => if code.isSuccess then () else Abort.fail(code)))

    private def copy(
        args: Chunk[String] = this.args,
        workDir: Maybe[kyo.Path] = this.workDir,
        envMode: EnvMode = this.envMode,
        stdinSource: Process.Input = this.stdinSource,
        stdinStream: Maybe[Stream[Byte, Sync]] = this.stdinStream,
        stdoutSink: StdioSink = this.stdoutSink,
        stderrSink: StdioSink = this.stderrSink,
        redirectError: Boolean = this.redirectError,
        pipeTo: Maybe[Command.Unsafe] = this.pipeTo,
        processGroup: Boolean = this.processGroup
    ): JvmCommandUnsafe =
        new JvmCommandUnsafe(
            args,
            workDir,
            envMode,
            stdinSource,
            stdinStream,
            stdoutSink,
            stderrSink,
            redirectError,
            pipeTo,
            processGroup
        )

end JvmCommandUnsafe

// --- ProcessPlatformSpecific — factory used by Command.apply in shared code ---

private[kyo] object ProcessPlatformSpecific:

    /** Creates a `Command.Unsafe` from the given argument list. */
    def makeCommand(args: Chunk[String]): Command =
        new JvmCommandUnsafe(args).safe

end ProcessPlatformSpecific
