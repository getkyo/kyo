package kyo.internal

import java.io.*
import java.lang.Process as JProcess
import java.lang.ProcessBuilder as JProcessBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.Path as JPath
import java.util.concurrent.TimeUnit
import kyo.*
import scala.jdk.CollectionConverters.*

// --- JvmProcessUnsafe — concrete Process.Unsafe backed by java.lang.Process ---

final private[kyo] class JvmProcessUnsafe(private[internal] val jp: JProcess) extends Process.Unsafe:

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
    val pipeTo: Maybe[Command.Unsafe] = Absent
) extends Command.Unsafe:

    // --- Builder methods — all return new instances ---

    def withCwd(path: kyo.Path): JvmCommandUnsafe =
        copy(workDir = Present(path))

    def withEnvAppend(vars: Map[String, String]): JvmCommandUnsafe =
        val newMode = envMode match
            case EnvMode.Clear                 => EnvMode.ClearThenAppend(vars)
            case EnvMode.ClearThenAppend(prev) => EnvMode.ClearThenAppend(prev ++ vars)
            case _                             => EnvMode.Append(vars)
        copy(envMode = newMode)
    end withEnvAppend

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

    // --- Internal helpers ---

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
        // Validate working directory up-front
        val wdError = workDir match
            case Present(path) =>
                val nio = path.unsafe match
                    case n: NioPathUnsafe => n.jpath
                    case other            => java.nio.file.Paths.get(other.show)
                if !java.nio.file.Files.exists(nio) then Present(WorkingDirectoryNotFoundException(path))
                else Absent
            case Absent => Absent

        wdError match
            case Present(err) => Result.fail(err)
            case _ =>
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
                    case EnvMode.Inherit => () // default: inherit parent env
                    case EnvMode.Append(vars) =>
                        pb.environment().putAll(vars.asJava)
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
                            end match
                    end match
                end if

                // stdout
                stdoutSink match
                    case StdioSink.Pipe    => discard(pb.redirectOutput(JProcessBuilder.Redirect.PIPE))
                    case StdioSink.Inherit => discard(pb.redirectOutput(JProcessBuilder.Redirect.INHERIT))
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
                        case StdioSink.Pipe    => discard(pb.redirectError(JProcessBuilder.Redirect.PIPE))
                        case StdioSink.Inherit => discard(pb.redirectError(JProcessBuilder.Redirect.INHERIT))
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

    /** Feeds an InputStream into a process's stdin on a daemon thread, closing when done. */
    private def feedInputStream(is: InputStream, processStdin: OutputStream): Unit =
        val t = new Thread(() =>
            try
                val buf = new Array[Byte](8192)
                var n   = is.read(buf)
                while n >= 0 do
                    processStdin.write(buf, 0, n)
                    n = is.read(buf)
                processStdin.close()
            catch
                case _: IOException => ()
            finally
                try is.close()
                catch case _: IOException => ()
        )
        t.setDaemon(true)
        t.start()
    end feedInputStream

    /** Drains a Stream[Byte, Sync] into a process's stdin on a daemon thread, closing when done.
      *
      * The stream is evaluated eagerly (it is Sync-only), then the bytes are written in a background thread so that the caller (spawn) is
      * not blocked by the write.
      */
    private def feedStream(stream: Stream[Byte, Sync], processStdin: OutputStream)(using AllowUnsafe, Frame): Unit =
        val chunk = Abort.run[Nothing](Sync.Unsafe.run(stream.run)).eval.getOrThrow
        val bytes = chunk.toArray
        val t = new Thread(() =>
            try
                processStdin.write(bytes)
                processStdin.close()
            catch
                case _: IOException => ()
        )
        t.setDaemon(true)
        t.start()
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

    def spawn()(using AllowUnsafe, Frame): Result[CommandException, Process.Unsafe] =
        // Validate program exists before spawning — on Scala Native, ProcessBuilder.start()
        // does not throw for a missing program (unlike JVM), so we check up front.
        validateProgram() match
            case Present(err) => Result.fail(err)
            case Absent =>
                val chain = pipelineChain
                if chain.length == 1 then
                    // Simple (non-piped) spawn
                    toJProcessBuilder() match
                        case Result.Failure(err) => Result.fail(err)
                        case Result.Panic(ex)    => Result.panic(ex)
                        case Result.Success(pb) =>
                            try
                                val jp   = pb.start()
                                val proc = new JvmProcessUnsafe(jp)
                                // Feed stdin if needed
                                stdinStream match
                                    case Present(s) => feedStream(s, jp.getOutputStream)
                                    case Absent =>
                                        stdinSource match
                                            case Process.Input.FromStream(is) => feedInputStream(is, jp.getOutputStream)
                                            case Process.Input.Inherit        => ()
                                end match
                                Result.succeed(proc)
                            catch
                                case e: IOException => Result.fail(translateIOException(e, args.headMaybe.getOrElse("")))
                else
                    // Pipeline spawn — delegate to `sh -c "cmd1 | cmd2 | ..."` so the OS
                    // kernel handles inter-process piping directly. This avoids daemon thread
                    // deadlocks on Scala Native where blocking I/O in threads is unreliable.
                    def shellEscape(a: String): String = "'" + a.replace("'", "'\\''") + "'"
                    val shellCmd                       = chain.map(_.args.map(shellEscape).mkString(" ")).mkString(" | ")

                    val firstCmd = chain.head
                    val pb       = new JProcessBuilder("sh", "-c", shellCmd)

                    firstCmd.envMode match
                        case EnvMode.Inherit => ()
                        case EnvMode.Append(vars) =>
                            vars.foreach { (k, v) => discard(pb.environment().put(k, v)) }
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
                        val jp   = pb.start()
                        val proc = new JvmProcessUnsafe(jp)
                        firstCmd.stdinStream match
                            case Present(s) => feedStream(s, jp.getOutputStream)
                            case Absent =>
                                firstCmd.stdinSource match
                                    case Process.Input.FromStream(is) => feedInputStream(is, jp.getOutputStream)
                                    case Process.Input.Inherit        => ()
                                end match
                        end match
                        Result.succeed(proc)
                    catch
                        case e: IOException =>
                            Result.fail(translateIOException(e, chain.head.args.headMaybe.getOrElse("")))
                    end try
                end if
        end match
    end spawn

    def text()(using AllowUnsafe, Frame): Fiber.Unsafe[String, Abort[CommandException]] =
        val p = Promise.Unsafe.init[String, Abort[CommandException]]()
        spawn() match
            case Result.Failure(err) =>
                p.completeDiscard(Result.fail(err))
            case Result.Panic(ex) =>
                p.completeDiscard(Result.panic(ex))
            case Result.Success(proc: JvmProcessUnsafe) =>
                discard(proc.jp.onExit().whenComplete { (exitedProcess, error) =>
                    if error != null then
                        p.completeDiscard(Result.panic(error))
                    else
                        try
                            val bytes = readAll(exitedProcess.getInputStream)
                            p.completeDiscard(Result.succeed(new String(bytes, StandardCharsets.UTF_8)))
                        catch
                            case e: IOException => p.completeDiscard(Result.panic(e))
                })
        end match
        p
    end text

    def waitFor()(using AllowUnsafe, Frame): Fiber.Unsafe[ExitCode, Abort[CommandException]] =
        val p = Promise.Unsafe.init[ExitCode, Abort[CommandException]]()
        spawn() match
            case Result.Failure(err) =>
                p.completeDiscard(Result.fail(err))
            case Result.Panic(ex) =>
                p.completeDiscard(Result.panic(ex))
            case Result.Success(proc: JvmProcessUnsafe) =>
                discard(proc.jp.onExit().whenComplete { (exitedProcess, error) =>
                    if error != null then
                        p.completeDiscard(Result.panic(error))
                    else
                        p.completeDiscard(Result.succeed(ExitCode(exitedProcess.exitValue())))
                })
        end match
        p
    end waitFor

    def waitForSuccess()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[CommandException | ExitCode]] =
        val p = Promise.Unsafe.init[Unit, Abort[CommandException | ExitCode]]()
        spawn() match
            case Result.Failure(err) =>
                p.completeDiscard(Result.fail(err))
            case Result.Panic(ex) =>
                p.completeDiscard(Result.panic(ex))
            case Result.Success(proc: JvmProcessUnsafe) =>
                discard(proc.jp.onExit().whenComplete { (exitedProcess, error) =>
                    if error != null then
                        p.completeDiscard(Result.panic(error))
                    else
                        val code = ExitCode(exitedProcess.exitValue())
                        if code.isSuccess then p.completeDiscard(Result.succeed(()))
                        else p.completeDiscard(Result.fail(code))
                })
        end match
        p
    end waitForSuccess

    private def copy(
        args: Chunk[String] = this.args,
        workDir: Maybe[kyo.Path] = this.workDir,
        envMode: EnvMode = this.envMode,
        stdinSource: Process.Input = this.stdinSource,
        stdinStream: Maybe[Stream[Byte, Sync]] = this.stdinStream,
        stdoutSink: StdioSink = this.stdoutSink,
        stderrSink: StdioSink = this.stderrSink,
        redirectError: Boolean = this.redirectError,
        pipeTo: Maybe[Command.Unsafe] = this.pipeTo
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
            pipeTo
        )

end JvmCommandUnsafe

// --- ProcessPlatformSpecific — factory used by Command.apply in shared code ---

private[kyo] object ProcessPlatformSpecific:

    /** Creates a `Command.Unsafe` from the given argument list. */
    def makeCommand(args: Chunk[String]): Command =
        new JvmCommandUnsafe(args).safe

end ProcessPlatformSpecific
