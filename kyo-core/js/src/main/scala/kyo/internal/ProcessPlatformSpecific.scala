package kyo.internal

import java.io.InputStream
import java.io.OutputStream
import kyo.*
import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.Uint8Array

// -----------------------------------------------------------------------
// Node.js child_process facades
// -----------------------------------------------------------------------

@js.native
@JSImport("node:child_process", JSImport.Namespace)
private[kyo] object NodeChildProcess extends js.Object:
    def spawn(command: String, args: js.Array[String], options: js.Dynamic): NodeChildProcessInstance =
        js.native
    def spawnSync(command: String, args: js.Array[String], options: js.Dynamic): js.Dynamic =
        js.native
end NodeChildProcess

@js.native
private[kyo] trait NodeChildProcessInstance extends js.Object:
    def pid: Int                                                                          = js.native
    def exitCode: js.Dynamic                                                              = js.native
    def killed: Boolean                                                                   = js.native
    def stdin: NodeWritableStream                                                         = js.native
    def stdout: NodeReadableStream                                                        = js.native
    def stderr: NodeReadableStream                                                        = js.native
    def kill(signal: String): Boolean                                                     = js.native
    def on(event: String, listener: js.Function1[js.Any, Unit]): NodeChildProcessInstance = js.native
end NodeChildProcessInstance

@js.native
private[kyo] trait NodeReadableStream extends js.Object:
    def on(event: String, listener: js.Function1[js.Any, Unit]): NodeReadableStream = js.native
    def pipe(destination: NodeWritableStream): NodeWritableStream                   = js.native
end NodeReadableStream

@js.native
private[kyo] trait NodeWritableStream extends js.Object:
    def write(data: Uint8Array): Boolean = js.native
    def end(): Unit                      = js.native
end NodeWritableStream

// -----------------------------------------------------------------------
// NodeInputStream — InputStream backed by a Node.js Readable stream
//
// Node.js is fundamentally async so a truly blocking read() is not
// possible.  Instead we buffer all data that arrives via the 'data'
// event and serve it on demand.  When the event loop turns between
// kyo-fiber steps the buffered bytes will be available.  This works
// correctly for the streamFromJavaInputStream usage pattern which calls
// read() in a Sync.Unsafe.defer loop — each iteration yields back to
// the event loop, giving Node.js a chance to push more 'data' events
// into the buffer.
// -----------------------------------------------------------------------

final private[kyo] class NodeInputStream(readable: NodeReadableStream) extends InputStream:

    // Simple growable byte queue.
    // Accessed only from the JS single thread so no synchronisation needed.
    private val buffer = scala.collection.mutable.ArrayBuffer.empty[Byte]
    private var pos    = 0
    private var ended  = false

    discard(readable.on(
        "data",
        { (chunk: js.Any) =>
            val arr = chunk.asInstanceOf[Uint8Array]
            var i   = 0
            while i < arr.length do
                discard(buffer += arr(i).toByte)
                i += 1
        }
    ))
    discard(readable.on("end", { (_: js.Any) => ended = true }))
    discard(readable.on("error", { (_: js.Any) => ended = true }))

    override def read(): Int =
        if pos < buffer.length then
            val b = buffer(pos) & 0xff
            pos += 1
            // Reclaim memory when we have consumed more than half and at least 4 KB
            if pos > 4096 && pos > buffer.length / 2 then
                buffer.remove(0, pos)
                pos = 0
            end if
            b
        else if ended then -1
        else -2 // no data yet — see read(byte[],int,int) override

    override def read(b: Array[Byte], off: Int, len: Int): Int =
        if len == 0 then return 0
        val avail = buffer.length - pos
        if avail > 0 then
            val n = math.min(avail, len)
            var i = 0
            while i < n do
                b(off + i) = buffer(pos + i)
                i += 1
            pos += n
            if pos > 4096 && pos > buffer.length / 2 then
                buffer.remove(0, pos)
                pos = 0
            end if
            n
        else if ended then -1
        else 0 // no data yet; caller should yield to event loop and retry
        end if
    end read

    override def available(): Int = buffer.length - pos

    override def close(): Unit = ()

end NodeInputStream

private[kyo] object NodeInputStream:
    /** An InputStream that is already ended — read() always returns -1. */
    val empty: InputStream = new InputStream:
        override def read(): Int = -1
end NodeInputStream

// -----------------------------------------------------------------------
// NodeOutputStream — OutputStream backed by a Node.js Writable stream
// -----------------------------------------------------------------------

final private[kyo] class NodeOutputStream(writable: NodeWritableStream) extends OutputStream:

    override def write(b: Int): Unit =
        val arr = new Uint8Array(1)
        arr(0) = (b & 0xff).toShort
        discard(writable.write(arr))
    end write

    override def write(b: Array[Byte], off: Int, len: Int): Unit =
        val arr = new Uint8Array(len)
        var i   = 0
        while i < len do
            arr(i) = b(off + i).toShort
            i += 1
        discard(writable.write(arr))
    end write

    override def close(): Unit = writable.end()

end NodeOutputStream

// -----------------------------------------------------------------------
// NodeProcessUnsafe — Process.Unsafe backed by a NodeChildProcessInstance
// -----------------------------------------------------------------------

final private[kyo] class NodeProcessUnsafe(
    val child: NodeChildProcessInstance,
    val stderrEnded: Boolean = false
) extends Process.Unsafe:

    // Stores a spawn error (e.g. ENOENT) so it can be surfaced via waitFor().
    private var spawnError: js.Any = null.asInstanceOf[js.Any]

    def markError(err: js.Any): Unit = spawnError = err

    def waitFor()(using AllowUnsafe, Frame): Fiber.Unsafe[Process.ExitCode, Any] =
        val p        = Promise.Unsafe.init[Process.ExitCode, Any]()
        var resolved = false
        val ec       = child.exitCode
        if ec != null && !js.isUndefined(ec) then
            resolved = true
            p.completeDiscard(Result.succeed(Process.ExitCode(ec.asInstanceOf[Int])))
        end if
        if !resolved then
            discard(child.on(
                "exit",
                { (codeOrNull: js.Any) =>
                    if !resolved then
                        resolved = true
                        val code = if codeOrNull == null then 1 else codeOrNull.asInstanceOf[Int]
                        p.completeDiscard(Result.succeed(Process.ExitCode(code)))
                    end if
                }
            ))
            // Handle spawn errors (e.g. ENOENT) which fire asynchronously on Node.js.
            // Treat them as a process exit with code 1 so callers don't wait forever.
            discard(child.on(
                "error",
                { (_: js.Any) =>
                    if !resolved then
                        resolved = true
                        p.completeDiscard(Result.succeed(Process.ExitCode(1)))
                    end if
                }
            ))
        end if
        p
    end waitFor

    def waitFor(timeout: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Maybe[Process.ExitCode], Any] =
        val p        = Promise.Unsafe.init[Maybe[Process.ExitCode], Any]()
        var resolved = false
        val ec       = child.exitCode
        if ec != null && !js.isUndefined(ec) then
            resolved = true
            p.completeDiscard(Result.succeed(Present(Process.ExitCode(ec.asInstanceOf[Int]))))
        end if
        if !resolved then
            discard(child.on(
                "exit",
                { (codeOrNull: js.Any) =>
                    if !resolved then
                        resolved = true
                        val code = if codeOrNull == null then 1 else codeOrNull.asInstanceOf[Int]
                        p.completeDiscard(Result.succeed(Present(Process.ExitCode(code))))
                    end if
                }
            ))
            discard(child.on(
                "error",
                { (_: js.Any) =>
                    if !resolved then
                        resolved = true
                        p.completeDiscard(Result.succeed(Absent))
                    end if
                }
            ))
            discard(js.Dynamic.global.setTimeout(
                { () =>
                    if !resolved then
                        resolved = true
                        p.completeDiscard(Result.succeed(Absent))
                    end if
                }: js.Function0[Unit],
                timeout.toMillis.toDouble
            ))
        end if
        p
    end waitFor

    def exitCode()(using AllowUnsafe): Maybe[Process.ExitCode] =
        val ec = child.exitCode
        if ec == null || js.isUndefined(ec) then Absent
        else Present(Process.ExitCode(ec.asInstanceOf[Int]))
    end exitCode

    def destroy()(using AllowUnsafe): Unit         = discard(child.kill("SIGTERM"))
    def destroyForcibly()(using AllowUnsafe): Unit = discard(child.kill("SIGKILL"))

    def isAlive()(using AllowUnsafe): Boolean =
        !child.killed && (child.exitCode == null || js.isUndefined(child.exitCode))

    def pid()(using AllowUnsafe): Long = child.pid.toLong

    private val _stdout: InputStream =
        if child.stdout == null || js.isUndefined(child.stdout) then NodeInputStream.empty
        else new NodeInputStream(child.stdout)
    private val _stderr: InputStream =
        if stderrEnded || child.stderr == null || js.isUndefined(child.stderr) then NodeInputStream.empty
        else new NodeInputStream(child.stderr)

    def stdoutJava(using AllowUnsafe): InputStream = _stdout
    def stderrJava(using AllowUnsafe): InputStream = _stderr
    def stdinJava(using AllowUnsafe): OutputStream = new NodeOutputStream(child.stdin)

end NodeProcessUnsafe

// -----------------------------------------------------------------------
import Command.EnvMode

// StdioSink
// -----------------------------------------------------------------------

private[kyo] enum NodeStdioSink derives CanEqual:
    case Pipe
    case Inherit
    case ToFile(path: kyo.Path, append: Boolean)
end NodeStdioSink

// -----------------------------------------------------------------------
// NodeCommandUnsafe — Command.Unsafe backed by node:child_process.spawn
// -----------------------------------------------------------------------

final private[kyo] class NodeCommandUnsafe(
    val args: Chunk[String],
    val workDir: Maybe[kyo.Path] = Absent,
    val envMode: EnvMode = EnvMode.Inherit,
    val stdinSource: Process.Input = Process.Input.Inherit,
    val stdinStream: Maybe[Stream[Byte, Sync]] = Absent,
    val stdoutSink: NodeStdioSink = NodeStdioSink.Pipe,
    val stderrSink: NodeStdioSink = NodeStdioSink.Pipe,
    val redirectError: Boolean = false,
    val pipeTo: Maybe[Command.Unsafe] = Absent
) extends Command.Unsafe:

    // -----------------------------------------------------------------------
    // Builder methods — all return new instances
    // -----------------------------------------------------------------------

    def withCwd(path: kyo.Path): NodeCommandUnsafe = copy(workDir = Present(path))
    def withEnvAppend(vars: Map[String, String]): NodeCommandUnsafe =
        val newMode = envMode match
            case EnvMode.Clear                 => EnvMode.ClearThenAppend(vars)
            case EnvMode.ClearThenAppend(prev) => EnvMode.ClearThenAppend(prev ++ vars)
            case _                             => EnvMode.Append(vars)
        copy(envMode = newMode)
    end withEnvAppend
    def withEnvReplace(vars: Map[String, String]): NodeCommandUnsafe = copy(envMode = EnvMode.Replace(vars))
    def withEnvClear: NodeCommandUnsafe                              = copy(envMode = EnvMode.Clear)

    def withStdin(input: Process.Input): NodeCommandUnsafe =
        copy(stdinSource = input, stdinStream = Absent)

    def withStdinStream(s: Stream[Byte, Sync]): NodeCommandUnsafe =
        copy(stdinStream = Present(s), stdinSource = Process.Input.Inherit)

    def withInheritStdout(value: Boolean): NodeCommandUnsafe =
        copy(stdoutSink = if value then NodeStdioSink.Inherit else NodeStdioSink.Pipe)

    def withInheritStderr(value: Boolean): NodeCommandUnsafe =
        copy(stderrSink = if value then NodeStdioSink.Inherit else NodeStdioSink.Pipe)

    def withStdoutFile(path: kyo.Path, append: Boolean): NodeCommandUnsafe =
        copy(stdoutSink = NodeStdioSink.ToFile(path, append))

    def withStderrFile(path: kyo.Path, append: Boolean): NodeCommandUnsafe =
        copy(stderrSink = NodeStdioSink.ToFile(path, append))

    def withRedirectErrorStream(value: Boolean): NodeCommandUnsafe = copy(redirectError = value)
    def withAndThen(that: Command.Unsafe): NodeCommandUnsafe       = copy(pipeTo = Present(that))

    /** Flattens the pipeline chain into a sequence of commands in order (head = leftmost). */
    private def pipelineChain: Seq[NodeCommandUnsafe] =
        pipeTo match
            case Absent =>
                Seq(this)
            case Present(next: NodeCommandUnsafe) =>
                this +: next.pipelineChain
            case Present(_) =>
                Seq(this)

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /** Builds the options object for NodeChildProcess.spawn. */
    private def buildOptions()(using AllowUnsafe): js.Dynamic =
        val opts = js.Dynamic.literal()

        workDir.foreach { path => opts.cwd = path.unsafe.show }

        envMode match
            case EnvMode.Inherit => ()
            case EnvMode.Append(vars) =>
                val env = js.Dynamic.global.Object.assign(
                    js.Dynamic.literal(),
                    js.Dynamic.global.process.env
                )
                vars.foreach { (k, v) => env.updateDynamic(k)(v) }
                opts.env = env
            case EnvMode.Replace(vars) =>
                val env = js.Dynamic.literal()
                vars.foreach { (k, v) => env.updateDynamic(k)(v) }
                opts.env = env
            case EnvMode.Clear =>
                opts.env = js.Dynamic.literal()
            case EnvMode.ClearThenAppend(vars) =>
                val env = js.Dynamic.literal()
                vars.foreach { (k, v) => env.updateDynamic(k)(v) }
                opts.env = env
        end match

        val stdioIn: js.Any =
            if stdinStream.isDefined then "pipe"
            else if stdinSource == Process.Input.Inherit then "inherit"
            else "pipe"

        val stdioOut: js.Any =
            if stdoutSink == NodeStdioSink.Pipe then "pipe"
            else if stdoutSink == NodeStdioSink.Inherit then "inherit"
            else "pipe"

        val stdioErr: js.Any =
            // When redirectError=true, discard child stderr (/dev/null) so no data flows
            // through the stderr pipe.  Callers that ask for proc.stderr receive an
            // already-ended stream (stderrEnded=true on NodeProcessUnsafe).
            if redirectError then "ignore"
            else if stderrSink == NodeStdioSink.Pipe then "pipe"
            else if stderrSink == NodeStdioSink.Inherit then "inherit"
            else "pipe"

        opts.stdio = js.Array(stdioIn, stdioOut, stdioErr)
        opts
    end buildOptions

    /** Writes a Java InputStream into a child process's writable stream synchronously. */
    private def feedInputStream(is: InputStream, childStdin: NodeWritableStream)(using AllowUnsafe): Unit =
        val buf = new Array[Byte](8192)
        var n   = is.read(buf)
        while n >= 0 do
            val arr = new Uint8Array(n)
            var i   = 0
            while i < n do
                arr(i) = buf(i).toShort
                i += 1
            discard(childStdin.write(arr))
            n = is.read(buf)
        end while
        childStdin.end()
    end feedInputStream

    /** Evaluates a Sync stream and writes the resulting bytes into a child process's writable stream. */
    private def feedStream(stream: Stream[Byte, Sync], childStdin: NodeWritableStream)(using AllowUnsafe, Frame): Unit =
        val chunk = Abort.run[Nothing](Sync.Unsafe.run(stream.run)).eval.getOrThrow
        val arr   = new Uint8Array(chunk.length)
        var i     = 0
        chunk.foreach { b =>
            arr(i) = (b & 0xff).toShort
            i += 1
        }
        discard(childStdin.write(arr))
        childStdin.end()
    end feedStream

    /** Pipes all data from a NodeReadableStream into a file path (append or overwrite). */
    private def pipeToFile(readable: NodeReadableStream, path: kyo.Path, append: Boolean)(using AllowUnsafe, Frame): Unit =
        var firstWrite = !append
        discard(readable.on(
            "data",
            { (chunk: js.Any) =>
                val arr   = chunk.asInstanceOf[Uint8Array]
                val bytes = new Array[Byte](arr.length)
                var i     = 0
                while i < arr.length do
                    bytes(i) = arr(i).toByte
                    i += 1
                val span = Span.from(bytes)
                if firstWrite then
                    firstWrite = false
                    discard(path.unsafe.writeBytes(span))
                else
                    discard(path.unsafe.appendBytes(span))
                end if
            }
        ))
    end pipeToFile

    /** Checks whether the command exists and is executable without running it.
      *
      * For absolute paths: checks the file directly with fs.accessSync. For names without a separator: scans the PATH directories. Returns
      * Some(CommandException) if the program is definitely not found/accessible, or None to proceed with the real spawn.
      */
    private def checkCommandExists()(using Frame): Option[CommandException] =
        if args.isEmpty then return Some(ProgramNotFoundException(""))
        val cmd = args.head
        try
            val fs       = js.Dynamic.global.require("node:fs")
            val nodePath = js.Dynamic.global.require("node:path")
            val X_OK     = fs.constants.X_OK.asInstanceOf[Int]
            // Helper: check if a file exists and is executable
            def isExec(p: String): Boolean =
                try
                    discard(fs.accessSync(p, X_OK))
                    true
                catch case _: Throwable => false
            end isExec
            // Absolute path or relative path with directory separator: check directly.
            // On Node.js (Unix), the separator is '/'.
            if cmd.startsWith("/") || cmd.contains("/") then
                if isExec(cmd) then None
                else Some(ProgramNotFoundException(cmd))
            else
                // Bare command name: scan PATH
                val pathEnv = js.Dynamic.global.process.env.PATH
                val pathStr = if js.typeOf(pathEnv) == "string" then pathEnv.asInstanceOf[String] else ""
                // On Unix (Node.js), PATH entries are separated by ':'.
                val dirs = pathStr.split(":")
                val found = dirs.exists { dir =>
                    val full = nodePath.join(dir, cmd).asInstanceOf[String]
                    isExec(full)
                }
                if found then None
                else Some(ProgramNotFoundException(cmd))
            end if
        catch case _: Throwable => None // If check fails, let spawn() proceed and handle the error
        end try
    end checkCommandExists

    /** Translates a js.JavaScriptException from spawn into a CommandException. */
    private def translateError(e: js.JavaScriptException)(using Frame): CommandException =
        val err  = e.exception.asInstanceOf[js.Dynamic]
        val code = if js.typeOf(err.code) == "string" then err.code.asInstanceOf[String] else ""
        code match
            case "ENOENT"           => ProgramNotFoundException(args.headOption.getOrElse(""))
            case "EACCES" | "EPERM" => PermissionDeniedException(args.headOption.getOrElse(""))
            case _                  => ProgramNotFoundException(args.headOption.getOrElse(""))
        end match
    end translateError

    // -----------------------------------------------------------------------
    // Effectful operations
    // -----------------------------------------------------------------------

    def spawn()(using AllowUnsafe, Frame): Result[CommandException, Process.Unsafe] =
        if args.isEmpty then return Result.fail(ProgramNotFoundException(""))
        // Validate program exists synchronously so spawn() can return Result.Failure without
        // waiting for the async 'error' event (which would prevent synchronous error reporting).
        checkCommandExists() match
            case Some(err) => return Result.fail(err)
            case None      => ()
        // Validate cwd exists before spawning so we can report WorkingDirectoryNotFoundException
        // synchronously (Node.js would otherwise fire an asynchronous 'error' event).
        workDir.foreach { path =>
            val dirPath = path.unsafe.show
            val exists  = js.Dynamic.global.require("node:fs").existsSync(dirPath).asInstanceOf[Boolean]
            if !exists then
                return Result.fail(WorkingDirectoryNotFoundException(path))
        }
        try
            val chain = pipelineChain
            if chain.length == 1 then
                // === Single-process spawn ===
                val opts   = buildOptions()
                val jsArgs = js.Array(args.drop(1).toSeq*)
                val child  = NodeChildProcess.spawn(args.head, jsArgs, opts)
                val proc   = new NodeProcessUnsafe(child, stderrEnded = redirectError)

                // Register an error handler to prevent Node.js from crashing on unhandled
                // 'error' events (e.g. ENOENT when the program is not found). The error is
                // stored on the NodeProcessUnsafe and surfaced via waitFor().
                discard(child.on(
                    "error",
                    { (err: js.Any) =>
                        proc.markError(err)
                    }
                ))

                stdinStream match
                    case Present(s) => feedStream(s, child.stdin)
                    case Absent =>
                        stdinSource match
                            case Process.Input.FromStream(is) => feedInputStream(is, child.stdin)
                            case Process.Input.Inherit        => ()
                        end match
                end match

                stdoutSink match
                    case NodeStdioSink.ToFile(path, ap) => pipeToFile(child.stdout, path, ap)
                    case _                              => ()
                end match

                if !redirectError then
                    stderrSink match
                        case NodeStdioSink.ToFile(path, ap) => pipeToFile(child.stderr, path, ap)
                        case _                              => ()
                    end match
                end if

                Result.succeed(proc)
            else
                // === Pipeline spawn ===
                // Build options that force all stdio to pipe for pipeline stages
                def pipeOpts(cmd: NodeCommandUnsafe): js.Dynamic =
                    val opts = js.Dynamic.literal()
                    cmd.workDir.foreach { path => opts.cwd = path.unsafe.show }
                    cmd.envMode match
                        case EnvMode.Inherit => ()
                        case EnvMode.Append(vars) =>
                            val env = js.Dynamic.global.Object.assign(
                                js.Dynamic.literal(),
                                js.Dynamic.global.process.env
                            )
                            vars.foreach { (k, v) => env.updateDynamic(k)(v) }
                            opts.env = env
                        case EnvMode.Replace(vars) =>
                            val env = js.Dynamic.literal()
                            vars.foreach { (k, v) => env.updateDynamic(k)(v) }
                            opts.env = env
                        case EnvMode.Clear =>
                            opts.env = js.Dynamic.literal()
                        case EnvMode.ClearThenAppend(vars) =>
                            val env = js.Dynamic.literal()
                            vars.foreach { (k, v) => env.updateDynamic(k)(v) }
                            opts.env = env
                    end match
                    opts.stdio = js.Array[js.Any]("pipe", "pipe", "pipe")
                    opts
                end pipeOpts

                // Spawn all stages
                val children = chain.map { cmd =>
                    val opts   = pipeOpts(cmd)
                    val jsArgs = js.Array(cmd.args.drop(1).toSeq*)
                    NodeChildProcess.spawn(cmd.args.head, jsArgs, opts)
                }

                // Wire pipes: stdout of N -> stdin of N+1
                for i <- 0 until children.length - 1 do
                    children(i).stdout.pipe(children(i + 1).stdin)

                // Feed stdin into the first process if configured
                val firstCmd = chain.head
                firstCmd.stdinStream match
                    case Present(s) => feedStream(s, children.head.stdin)
                    case Absent =>
                        firstCmd.stdinSource match
                            case Process.Input.FromStream(is) => feedInputStream(is, children.head.stdin)
                            case Process.Input.Inherit        => ()
                        end match
                end match

                // Return the last process
                val lastChild = children.last
                val lastCmd   = chain.last
                val proc      = new NodeProcessUnsafe(lastChild, stderrEnded = lastCmd.redirectError)
                discard(lastChild.on("error", { (err: js.Any) => proc.markError(err) }))

                // Handle stdout/stderr sinks on the last process
                lastCmd.stdoutSink match
                    case NodeStdioSink.ToFile(path, ap) => pipeToFile(lastChild.stdout, path, ap)
                    case _                              => ()
                end match
                if !lastCmd.redirectError then
                    lastCmd.stderrSink match
                        case NodeStdioSink.ToFile(path, ap) => pipeToFile(lastChild.stderr, path, ap)
                        case _                              => ()
                    end match
                end if

                Result.succeed(proc)
            end if
        catch
            case e: js.JavaScriptException => Result.fail(translateError(e))
            case e: Throwable              => Result.panic(e)
        end try
    end spawn

    def text()(using AllowUnsafe, Frame): Fiber.Unsafe[String, Abort[CommandException]] =
        val p = Promise.Unsafe.init[String, Abort[CommandException]]()
        spawn() match
            case Result.Failure(err) => p.completeDiscard(Result.fail(err))
            case Result.Panic(ex)    => p.completeDiscard(Result.panic(ex))
            case Result.Success(proc: NodeProcessUnsafe) =>
                val outIs = proc.stdoutJava
                // Register 'error' handler to propagate spawn failures (e.g. ENOENT) as CommandException.
                discard(proc.child.on(
                    "error",
                    { (errAny: js.Any) =>
                        p.completeDiscard(Result.fail(translateError(
                            new js.JavaScriptException(errAny)
                        )))
                    }
                ))
                // Attach to the exit event directly so we read all buffered output after exit
                discard(proc.child.on(
                    "exit",
                    { (_: js.Any) =>
                        val baos = new java.io.ByteArrayOutputStream()
                        var b    = outIs.read()
                        while b >= 0 do
                            baos.write(b)
                            b = outIs.read()
                        p.completeDiscard(Result.succeed(baos.toString("UTF-8")))
                    }
                ))
                // Handle if process already exited
                val ec = proc.child.exitCode
                if ec != null && !js.isUndefined(ec) then
                    val baos = new java.io.ByteArrayOutputStream()
                    var b    = outIs.read()
                    while b >= 0 do
                        baos.write(b)
                        b = outIs.read()
                    p.completeDiscard(Result.succeed(baos.toString("UTF-8")))
                end if
            case Result.Success(_) =>
                p.completeDiscard(Result.panic(new IllegalStateException("Unexpected Process.Unsafe type")))
        end match
        p
    end text

    def waitFor()(using AllowUnsafe, Frame): Fiber.Unsafe[Process.ExitCode, Abort[CommandException]] =
        val p = Promise.Unsafe.init[Process.ExitCode, Abort[CommandException]]()
        spawn() match
            case Result.Failure(err) => p.completeDiscard(Result.fail(err))
            case Result.Panic(ex)    => p.completeDiscard(Result.panic(ex))
            case Result.Success(proc: NodeProcessUnsafe) =>
                val ec = proc.child.exitCode
                if ec != null && !js.isUndefined(ec) then
                    p.completeDiscard(Result.succeed(Process.ExitCode(ec.asInstanceOf[Int])))
                else
                    // Register 'error' handler to propagate spawn failures (e.g. ENOENT) as CommandException.
                    discard(proc.child.on(
                        "error",
                        { (errAny: js.Any) =>
                            p.completeDiscard(Result.fail(translateError(
                                new js.JavaScriptException(errAny)
                            )))
                        }
                    ))
                    discard(proc.child.on(
                        "exit",
                        { (codeOrNull: js.Any) =>
                            val code = if codeOrNull == null then 1 else codeOrNull.asInstanceOf[Int]
                            p.completeDiscard(Result.succeed(Process.ExitCode(code)))
                        }
                    ))
                end if
            case Result.Success(_) =>
                p.completeDiscard(Result.panic(new IllegalStateException("Unexpected Process.Unsafe type")))
        end match
        p
    end waitFor

    def waitForSuccess()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[CommandException | Process.ExitCode]] =
        val p = Promise.Unsafe.init[Unit, Abort[CommandException | Process.ExitCode]]()
        spawn() match
            case Result.Failure(err) => p.completeDiscard(Result.fail(err))
            case Result.Panic(ex)    => p.completeDiscard(Result.panic(ex))
            case Result.Success(proc: NodeProcessUnsafe) =>
                val ec = proc.child.exitCode
                if ec != null && !js.isUndefined(ec) then
                    val code = Process.ExitCode(ec.asInstanceOf[Int])
                    if code.isSuccess then p.completeDiscard(Result.succeed(()))
                    else p.completeDiscard(Result.fail(code))
                else
                    discard(proc.child.on(
                        "error",
                        { (errAny: js.Any) =>
                            p.completeDiscard(Result.fail(translateError(
                                new js.JavaScriptException(errAny)
                            )))
                        }
                    ))
                    discard(proc.child.on(
                        "exit",
                        { (codeOrNull: js.Any) =>
                            val rawCode = if codeOrNull == null then 1 else codeOrNull.asInstanceOf[Int]
                            val code    = Process.ExitCode(rawCode)
                            if code.isSuccess then p.completeDiscard(Result.succeed(()))
                            else p.completeDiscard(Result.fail(code))
                        }
                    ))
                end if
            case Result.Success(_) =>
                p.completeDiscard(Result.panic(new IllegalStateException("Unexpected Process.Unsafe type")))
        end match
        p
    end waitForSuccess

    private def copy(
        args: Chunk[String] = this.args,
        workDir: Maybe[kyo.Path] = this.workDir,
        envMode: EnvMode = this.envMode,
        stdinSource: Process.Input = this.stdinSource,
        stdinStream: Maybe[Stream[Byte, Sync]] = this.stdinStream,
        stdoutSink: NodeStdioSink = this.stdoutSink,
        stderrSink: NodeStdioSink = this.stderrSink,
        redirectError: Boolean = this.redirectError,
        pipeTo: Maybe[Command.Unsafe] = this.pipeTo
    ): NodeCommandUnsafe =
        new NodeCommandUnsafe(
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

end NodeCommandUnsafe

// -----------------------------------------------------------------------
// ProcessPlatformSpecific — factory used by Command.apply in shared code
// -----------------------------------------------------------------------

private[kyo] object ProcessPlatformSpecific:

    /** Creates a `Command` from the given argument list. */
    def makeCommand(args: Chunk[String]): Command =
        new NodeCommandUnsafe(args).safe

end ProcessPlatformSpecific
