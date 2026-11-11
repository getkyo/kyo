package kyo.internal

import java.io.*
import java.lang.Process as JProcess
import java.lang.ProcessBuilder as JProcessBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.Path as JPath
import java.util.concurrent.TimeUnit
import kyo.*
import scala.jdk.CollectionConverters.*

// --- InputFeeds — the stop actions of a process's stdin feeds ---

/** The stop actions of the fibers feeding a process's stdin. Retained so a feed ends with the process rather than running on after it,
  * which is what an unheld feed does.
  */
final private[kyo] class InputFeeds:

    private val stops = new java.util.concurrent.ConcurrentLinkedQueue[() => Unit]()

    def register(stop: () => Unit)(using AllowUnsafe): Unit = discard(stops.add(stop))

    /** Stops every feed. Idempotent, and safe to call after the process has already exited: a feed can still be parked reading its source
      * even when the child is gone.
      */
    def stopAll()(using AllowUnsafe): Unit =
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

    override private[kyo] def closeStreams()(using AllowUnsafe): Unit =
        try jp.getInputStream.close()
        finally
            try jp.getErrorStream.close()
            finally
                // The child has been joined. A buffered write attempted after its exit can make close's flush report EPIPE or
                // "Stream closed"; java.io still closes the underlying pipe in its finally block.
                try jp.getOutputStream.close()
                catch case _: IOException => ()

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
      * Each chunk is written before requesting the next, so an unbounded source is not collected during acquisition.
      * Returns the stop action for the same reason as [[feedInputStream]].
      */
    private def feedStream(stream: Stream[Byte, Sync], processStdin: OutputStream)(using AllowUnsafe, Frame): () => Unit =
        // Unsafe: spawns the pump from outside the effect system, where spawn() runs.
        val feed = Fiber.Unsafe.init {
            Abort.run[Throwable] {
                stream.foreachChunk(chunk => Sync.defer(processStdin.write(chunk.toArray)))
            }.andThen(Sync.defer(closeQuietly(processStdin)))
        }
        () =>
            discard(feed.interrupt())
            closeQuietly(processStdin)
    end feedStream

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
                if java.nio.file.Files.isExecutable(java.nio.file.Paths.get(cmd)) then Absent
                else Present(ProgramNotFoundException(cmd))
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
        val msg = e.getMessage
        if msg != null && (msg.contains("No such file") || msg.contains("Cannot run program") || msg.contains("error=2")) then
            ProgramNotFoundException(cmd)
        else if msg != null && (msg.contains("Permission denied") || msg.contains("error=13")) then
            PermissionDeniedException(cmd)
        else
            ProgramNotFoundException(cmd)
        end if
    end translateIOException

    // --- Effectful operations ---

    override private[kyo] def acquire()(using Frame): Process.Unsafe < (Async & Scope & Abort[CommandException]) =
        if !processGroup then super.acquire()
        else if Platform.isWindows then Abort.fail(ProcessGroupUnsupportedException(args.headMaybe.getOrElse("")))
        else
            Sync.Unsafe.defer {
                missingWorkDir match
                    case Present(error) => Abort.fail(error)
                    case Absent         =>
                        val chain       = pipelineChain
                        val piped       = chain.length > 1
                        val program     = if piped then Chunk("sh", "-c", shellCommand(chain)) else args
                        val input       = if stdinStream.isDefined then Process.Input.Pipe else stdinSource
                        val output      = if piped then StdioSink.Pipe else stdoutSink
                        val errors      = if piped then StdioSink.Pipe else stderrSink
                        val mergeErrors = if piped then chain.last.redirectError else redirectError
                        val inputMask   = if input == Process.Input.Inherit then 0 else 1
                        val outputMask  = if output == StdioSink.Pipe then 2 else 0
                        val errorMask   = if mergeErrors then 8 else if errors == StdioSink.Pipe then 4 else 0
                        val mask        = inputMask | outputMask | errorMask
                        ProcessSupervisor.init(args.headMaybe.getOrElse(""), mask) { (executable, endpoint) =>
                            new JvmCommandUnsafe(
                                Chunk(executable, endpoint, "", mask.toString) ++ program,
                                workDir,
                                envMode,
                                Process.Input.Inherit,
                                Absent,
                                if output == StdioSink.Pipe then StdioSink.Inherit else output,
                                if errors == StdioSink.Pipe || mergeErrors then StdioSink.Inherit else errors
                            ).spawnRaw()
                        }.map { process =>
                            Sync.defer {
                                val feeds = new InputFeeds
                                feedStdin(feeds, process.stdinJava)
                                process.registerInputFeed(() => feeds.stopAll())
                                process
                            }
                        }
                end match
            }
        end if
    end acquire

    private[kyo] def spawnRaw()(using AllowUnsafe, Frame): Result[CommandException, Process.Unsafe] =
        // Every child's stdin is a pipe this process may write after the child is gone; the write must fail, not kill us.
        ProcessSignalPlatform.ignoreBrokenPipes()
        // Validate program exists before spawning — on Scala Native, ProcessBuilder.start()
        // does not throw for a missing program (unlike JVM), so we check up front.
        validateProgram() match
            case Present(err) => Result.fail(err)
            case Absent       =>
                val chain = pipelineChain
                if chain.length == 1 then
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
    end spawnRaw

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
