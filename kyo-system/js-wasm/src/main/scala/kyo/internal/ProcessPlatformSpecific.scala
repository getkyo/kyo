package kyo.internal

import java.io.InputStream
import java.io.OutputStream
import kyo.*
import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.Uint8Array

// --- Node.js child_process facades ---

@js.native
@JSImport("node:child_process", JSImport.Namespace)
private[kyo] object NodeChildProcess extends js.Object:
    def spawn(command: String, args: js.Array[String], options: js.Dynamic): NodeChildProcessInstance =
        js.native
    def spawnSync(command: String, args: js.Array[String], options: js.Dynamic): js.Dynamic =
        js.native
end NodeChildProcess

// Namespace imports accessed dynamically. @JSImport compiles to require() under CommonJS and
// to import under ESModule, so these work on both the JS and WASM backends, unlike a
// js.Dynamic.global.require call (require is not a global in a Node ES module).
@js.native
@JSImport("node:fs", JSImport.Namespace)
private[kyo] object NodeFsModule extends js.Object

@js.native
@JSImport("node:path", JSImport.Namespace)
private[kyo] object NodePathModule extends js.Object

@js.native
private[kyo] trait NodeChildProcessInstance extends js.Object:
    def pid: js.UndefOr[Int]                                                              = js.native
    def exitCode: js.Dynamic                                                              = js.native
    def killed: Boolean                                                                   = js.native
    def stdin: NodeWritableStream                                                         = js.native
    def stdout: NodeReadableStream                                                        = js.native
    def stderr: NodeReadableStream                                                        = js.native
    def kill(signal: String): Boolean                                                     = js.native
    def on(event: String, listener: js.Function1[js.Any, Unit]): NodeChildProcessInstance = js.native
    // Node's 'exit' event is (code, signal). The arity-1 overload above discards the second
    // argument, and with it the only indication that a child was terminated by a signal: Node sets
    // code to null in exactly that case.
    def on(event: String, listener: js.Function2[js.Any, js.Any, Unit]): NodeChildProcessInstance = js.native
    // Set when the child was terminated by a signal, and the companion to exitCode: a child that
    // has been sent a signal but has not yet died has both still null.
    def signalCode: js.Any = js.native
end NodeChildProcessInstance

@js.native
private[kyo] trait NodeRuntimeStream extends js.Object:
    def on(event: String, listener: js.Function1[js.Any, Unit]): NodeRuntimeStream = js.native
    def destroy(): NodeRuntimeStream                                               = js.native
    def destroyed: Boolean                                                         = js.native
    def closed: Boolean                                                            = js.native
end NodeRuntimeStream

@js.native
private[kyo] trait NodeReadableStream extends NodeRuntimeStream:
    def pause(): NodeReadableStream                               = js.native
    def resume(): NodeReadableStream                              = js.native
    def pipe(destination: NodeWritableStream): NodeWritableStream = js.native
end NodeReadableStream

@js.native
private[kyo] trait NodeWritableStream extends NodeRuntimeStream:
    def write(data: Uint8Array): Boolean                                       = js.native
    def write(data: Uint8Array, callback: js.Function1[js.Any, Unit]): Boolean = js.native
    def end(): Unit                                                            = js.native
    // True once the stream has been torn down: after an error such as EPIPE, or when Node destroys a child's stdin as the child exits.
    // True once end() has been called.
    def writableEnded: Boolean = js.native
end NodeWritableStream

private[kyo] object NodeWritableStream:

    /** Keeps a failed write to `stream` from ending the process.
      *
      * A write to a pipe whose reader is gone fails asynchronously: Node emits an 'error' event (EPIPE) on the stream after `write` has
      * returned, and an 'error' event with no listener is thrown as an uncaught exception, which ends the whole Node process. With a listener
      * installed the stream is only destroyed, and [[NodeOutputStream]] reports the failure to the next write as an `IOException`.
      */
    def guard(stream: NodeWritableStream): Unit =
        if stream != null && !js.isUndefined(stream) then
            discard(stream.on("error", { (_: js.Any) => () }))
end NodeWritableStream

/** Bounded Node-readable buffering with readiness suspension. Each readable pauses after delivering one chunk, including merged stderr. */
final private[kyo] class NodeInputStream private (
    initialReadiness: Promise.Unsafe[Unit, Any],
    completion: Promise.Unsafe[Unit, Any]
) extends InputStream:
    private val buffer           = scala.collection.mutable.ArrayBuffer.empty[Byte]
    private var position         = 0
    private var sources          = List.empty[NodeReadableStream]
    private var remainingSources = 0
    private var closed           = false
    private var failure          = Maybe.empty[java.io.IOException]
    private var readiness        = initialReadiness
    private var remainingCloses  = 0

    def mergeFrom(source: NodeReadableStream)(using AllowUnsafe): Unit =
        sources = source :: sources
        remainingSources += 1
        remainingCloses += 1
        var finished                          = false
        def finish()(using AllowUnsafe): Unit =
            if !finished then
                finished = true
                remainingSources -= 1
                readiness.completeDiscard(Result.succeed(()))
        discard(source.on(
            "data",
            { (chunk: js.Any) =>
                discard(source.pause())
                if !closed then
                    val bytes = chunk.asInstanceOf[Uint8Array]
                    var index = 0
                    while index < bytes.length do
                        discard(buffer += bytes(index).toByte)
                        index += 1
                    readiness.completeDiscard(Result.succeed(()))
                end if
            }
        ))
        discard(source.on("end", { (_: js.Any) => finish() }))
        discard(source.on(
            "error",
            { (error: js.Any) =>
                failure = Present(new java.io.IOException("Process output failed", js.JavaScriptException(error)))
                finish()
            }
        ))
        def onClose()(using AllowUnsafe): Unit =
            finish()
            remainingCloses -= 1
            if remainingCloses == 0 then completion.completeDiscard(Result.succeed(()))
        end onClose
        if source.closed then onClose()
        else discard(source.on("close", { (_: js.Any) => onClose() }))
    end mergeFrom

    private def resume()(using AllowUnsafe): Unit =
        if position == buffer.length && !closed && remainingSources > 0 then
            buffer.clear()
            position = 0
            if readiness.poll().isDefined then readiness = Promise.Unsafe.initUninterruptible[Unit, Any]()
            sources.foreach(source => discard(source.resume()))

    override def read(): Int =
        val bytes = new Array[Byte](1)
        val count = read(bytes, 0, 1)
        if count < 0 then -1 else if count == 0 then -2 else bytes(0) & 0xff
    end read

    override def read(bytes: Array[Byte], offset: Int, length: Int): Int =
        // Unsafe: java.io is the imperative host boundary for this event-loop-owned adapter.
        import AllowUnsafe.embrace.danger
        if offset < 0 || length < 0 || offset > bytes.length - length then throw new IndexOutOfBoundsException
        if length == 0 then 0
        else
            resume()
            val count = math.min(length, buffer.length - position)
            if count > 0 then
                var index = 0
                while index < count do
                    bytes(offset + index) = buffer(position + index)
                    index += 1
                position += count
                count
            else
                failure.foreach(error => throw error)
                if closed || remainingSources == 0 then -1 else 0
            end if
        end if
    end read

    def readAsync(bytes: Array[Byte], offset: Int, length: Int)(using Frame): Int < Async =
        Loop(()) { _ =>
            Sync.defer {
                val count = read(bytes, offset, length)
                if count != 0 || length == 0 then Loop.done(count)
                else readiness.safe.get.andThen(Loop.continue(()))
            }
        }

    def stream(using Frame): Stream[Byte, Async & Scope] = ProcessStreams.fromReader(readAsync, () => closeAndWait())

    override def available(): Int = buffer.length - position

    override def close(): Unit =
        // Unsafe: java.io close wakes effectful readers and releases the host streams.
        import AllowUnsafe.embrace.danger
        if !closed then
            closed = true
            buffer.clear()
            position = 0
            readiness.completeDiscard(Result.succeed(()))
            sources.foreach(source => discard(source.destroy()))
        end if
    end close

    def closeAndWait()(using Frame): Unit < Async = Sync.defer(close()).andThen(completion.safe.get)
end NodeInputStream

private[kyo] object NodeInputStream:
    def init(readable: NodeReadableStream)(using AllowUnsafe): NodeInputStream =
        val stream = new NodeInputStream(
            Promise.Unsafe.initUninterruptible[Unit, Any](),
            Promise.Unsafe.initUninterruptible[Unit, Any]()
        )
        stream.mergeFrom(readable)
        stream
    end init

    /** An InputStream that is already ended — read() always returns -1. */
    val empty: InputStream = new InputStream:
        override def read(): Int = -1
end NodeInputStream

// --- NodeOutputStream — OutputStream backed by a Node.js Writable stream ---

final private[kyo] class NodeOutputStream(writable: NodeWritableStream) extends OutputStream:

    // A write to a torn-down stream is silently discarded by Node, so the closed state is checked here and reported the way
    // java.io.OutputStream reports it: the stream is destroyed after a pipe error or when its child exited, or ended by close().
    private def ensureOpen(): Unit =
        if writable == null || js.isUndefined(writable) || writable.destroyed || writable.writableEnded then
            throw new java.io.IOException("Stream closed")

    override def write(b: Int): Unit =
        ensureOpen()
        val arr = new Uint8Array(1)
        arr(0) = (b & 0xff).toShort
        discard(writable.write(arr))
    end write

    override def write(b: Array[Byte], off: Int, len: Int): Unit =
        ensureOpen()
        val arr = new Uint8Array(len)
        var i   = 0
        while i < len do
            arr(i) = b(off + i).toShort
            i += 1
        discard(writable.write(arr))
    end write

    override def close(): Unit =
        if writable != null && !js.isUndefined(writable) && !writable.destroyed && !writable.writableEnded then writable.end()

end NodeOutputStream

// --- NodeProcessUnsafe — Process.Unsafe backed by a NodeChildProcessInstance ---

final private[kyo] class NodeProcessUnsafe private (
    val child: NodeChildProcessInstance,
    val stderrEnded: Boolean,
    upstream: Seq[NodeChildProcessInstance],
    startup: Promise.Unsafe[Unit, Abort[CommandException]],
    _stdout: Maybe[NodeInputStream],
    _stderr: Maybe[NodeInputStream]
) extends Process.Unsafe:

    // Node reports failed exec asynchronously. Acquisition observes that result before handing over the owned handle.
    def markError(error: CommandException)(using AllowUnsafe): Unit = startup.completeDiscard(Result.fail(error))

    def awaitStartup(using Frame): Unit < (Async & Abort[CommandException]) = startup.safe.get

    private var inputFeed                                            = Maybe.empty[() => Unit]
    private var feedsClosed                                          = false
    def registerInputFeed(stop: () => Unit)(using AllowUnsafe): Unit =
        if feedsClosed then stop() else inputFeed = Present(stop)

    override def stopInputFeeds()(using AllowUnsafe): Unit =
        feedsClosed = true
        val previous = inputFeed
        inputFeed = Absent
        previous.foreach(_())
    end stopInputFeeds

    private def input: NodeWritableStream = if upstream.isEmpty then child.stdin else upstream.head.stdin

    def writeInput(bytes: Array[Byte], offset: Int, length: Int)(using Frame): Unit < Async = Sync.Unsafe.defer {
        val completion = Promise.Unsafe.initUninterruptible[Unit, Any]()
        val buffer     = new Uint8Array(length)
        var index      = 0
        while index < length do
            buffer(index) = (bytes(offset + index) & 0xff).toShort
            index += 1
        discard(input.write(
            buffer,
            { (error: js.Any) =>
                if error == null || js.isUndefined(error) then completion.completeDiscard(Result.succeed(()))
                else
                    completion.completeDiscard(Result.panic(new java.io.IOException(
                        "Process input write failed",
                        js.JavaScriptException(error)
                    )))
            }
        ))
        completion.safe.get
    }

    override def destroyTree()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] = Fiber.Unsafe.init {
        val children = Chunk.from(upstream :+ child)
        Sync.defer(children.foreach(process => if NodeProcessUnsafe.running(process) then discard(process.kill("SIGKILL"))))
            .andThen(Kyo.foreachDiscard(children.filter(_.pid.isDefined))(process => waitForChild(process).uninterruptible().safe.get))
    }

    def waitFor()(using AllowUnsafe, Frame): Fiber.Unsafe[ExitCode, Any] = waitForChild(child)

    private def waitForChild(target: NodeChildProcessInstance)(using AllowUnsafe, Frame): Fiber.Unsafe[Process.ExitCode, Any] =
        val p        = Promise.Unsafe.init[Process.ExitCode, Any]()
        var resolved = target.pid.isEmpty
        if resolved then p.completeDiscard(Result.panic(new java.io.IOException("Process did not start")))
        val ec = target.exitCode
        val sc = target.signalCode
        // Both are consulted, because Node leaves exitCode null for a signalled death and records
        // the signal in signalCode instead. Checking exitCode alone missed a process that had
        // already been killed: the 'exit' event had fired before the listener below was attached,
        // so nothing ever completed the promise and the wait hung.
        if (ec != null && !js.isUndefined(ec)) || (sc != null && !js.isUndefined(sc)) then
            resolved = true
            p.completeDiscard(Result.succeed(exitCodeFrom(ec, sc)))
        end if
        if !resolved then
            discard(target.on(
                "exit",
                { (codeOrNull: js.Any, signalOrNull: js.Any) =>
                    if !resolved then
                        resolved = true
                        p.completeDiscard(Result.succeed(exitCodeFrom(codeOrNull, signalOrNull)))
                    end if
                }: js.Function2[js.Any, js.Any, Unit]
            ))
            // Handle spawn errors (e.g. ENOENT) which fire asynchronously on Node.js.
            // Treat them as a process exit with code 1 so callers don't wait forever.
            discard(target.on(
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
    end waitForChild

    def waitFor(timeout: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Maybe[ExitCode], Any] =
        Fiber.Unsafe.init {
            Clock.let(Clock.live) {
                Abort.run[Timeout](Async.timeout(timeout)(waitFor().safe.get)).map {
                    case Result.Success(code) => Present(code)
                    case Result.Failure(_)    => Absent
                    case Result.Panic(error)  => Abort.panic(error)
                }
            }
        }
    end waitFor

    // Node reports a signal death as code == null with signal set to its name, so a null code alone
    // is not "exited with 1". Mapped onto the POSIX 128 + signal convention that Process.ExitCode
    // already uses, which is what makes ExitCode.Signaled reachable on this platform at all.
    private def exitCodeFrom(codeOrNull: js.Any, signalOrNull: js.Any): Process.ExitCode =
        val signalNumber =
            if signalOrNull == null || js.isUndefined(signalOrNull) then Absent
            else
                signalOrNull.asInstanceOf[String] match
                    case "SIGHUP"  => Present(1)
                    case "SIGINT"  => Present(2)
                    case "SIGQUIT" => Present(3)
                    case "SIGKILL" => Present(9)
                    case "SIGSEGV" => Present(11)
                    case "SIGPIPE" => Present(13)
                    case "SIGTERM" => Present(15)
                    case _         => Absent
        signalNumber.fold {
            if codeOrNull == null || js.isUndefined(codeOrNull) then Process.ExitCode(1)
            else Process.ExitCode(codeOrNull.asInstanceOf[Int])
        }(n => Process.ExitCode(128 + n))
    end exitCodeFrom

    // A signal death leaves exitCode null and records the signal in signalCode, so both are consulted, as isAlive does.
    def exitCode()(using AllowUnsafe): Maybe[Process.ExitCode] =
        val ec = child.exitCode
        val sc = child.signalCode
        if (ec == null || js.isUndefined(ec)) && (sc == null || js.isUndefined(sc)) then Absent
        else Present(exitCodeFrom(ec, sc))
    end exitCode

    def destroy()(using AllowUnsafe): Unit         = discard(child.kill("SIGTERM"))
    def destroyForcibly()(using AllowUnsafe): Unit = discard(child.kill("SIGKILL"))

    def isAlive()(using AllowUnsafe): Boolean = NodeProcessUnsafe.running(child)

    def pid()(using AllowUnsafe): Long = child.pid.getOrElse(throw new java.io.IOException("Process did not start")).toLong

    def stdoutJava(using AllowUnsafe): InputStream = _stdout.fold(NodeInputStream.empty)(identity)
    def stderrJava(using AllowUnsafe): InputStream = _stderr.fold(NodeInputStream.empty)(identity)
    def stdinJava(using AllowUnsafe): OutputStream = new NodeOutputStream(input)

    override private[kyo] def stdoutStream(using Frame): Stream[Byte, Async & Scope] = _stdout.fold(Stream.empty[Byte])(_.stream)
    override private[kyo] def stderrStream(using Frame): Stream[Byte, Async & Scope] = _stderr.fold(Stream.empty[Byte])(_.stream)

    override private[kyo] def close()(using Frame): Unit < Async =
        ProcessStreams.finish(Sync.Unsafe.defer(stopInputFeeds()).andThen(Sync.Unsafe.defer(destroyTree().safe.get))) {
            ProcessStreams.finish(_stdout.fold(Kyo.unit)(_.closeAndWait())) {
                ProcessStreams.finish(_stderr.fold(Kyo.unit)(_.closeAndWait())) {
                    val streams = Chunk.from(upstream :+ child).flatMap(process =>
                        Chunk[NodeRuntimeStream](process.stdin, process.stdout, process.stderr)
                            .filter(stream => stream != null && !js.isUndefined(stream))
                    )
                    Kyo.foreachDiscard(streams) { stream =>
                        Sync.Unsafe.defer {
                            val completion = Promise.Unsafe.initUninterruptible[Unit, Any]()
                            if stream.closed then completion.completeDiscard(Result.succeed(()))
                            else discard(stream.on("close", { (_: js.Any) => completion.completeDiscard(Result.succeed(())) }))
                            discard(stream.destroy())
                            completion.safe.get
                        }
                    }
                }
            }
        }

end NodeProcessUnsafe

private[kyo] object NodeProcessUnsafe:

    def init(
        child: NodeChildProcessInstance,
        stderrEnded: Boolean = false,
        upstream: Seq[NodeChildProcessInstance] = Seq.empty,
        captureOutputs: Boolean = true
    )(using AllowUnsafe): NodeProcessUnsafe =
        val startup = Promise.Unsafe.initUninterruptible[Unit, Abort[CommandException]]()
        if child.pid.isDefined then startup.completeDiscard(Result.succeed(()))
        val stdout =
            if !captureOutputs || child.stdout == null || js.isUndefined(child.stdout) then Absent
            else
                val stream = NodeInputStream.init(child.stdout)
                if stderrEnded && child.stderr != null && !js.isUndefined(child.stderr) then stream.mergeFrom(child.stderr)
                Present(stream)
        val stderr =
            if !captureOutputs then Absent
            else
                val sources = (upstream ++ (if stderrEnded then Seq.empty else Seq(child)))
                    .map(_.stderr).filter(stream => stream != null && !js.isUndefined(stream))
                if sources.isEmpty then Absent
                else
                    val stream = NodeInputStream.init(sources.head)
                    sources.drop(1).foreach(stream.mergeFrom)
                    Present(stream)
                end if
        new NodeProcessUnsafe(child, stderrEnded, upstream, startup, stdout, stderr)
    end init

    // `child.killed` reports that a signal was delivered, not that the child died: a child sent SIGTERM that has not exited yet is still
    // running, and reading it as dead would skip the kill that release owes it.
    def running(child: NodeChildProcessInstance)(using AllowUnsafe): Boolean =
        child.pid.isDefined && (child.exitCode == null || js.isUndefined(child.exitCode)) &&
            (child.signalCode == null || js.isUndefined(child.signalCode))

end NodeProcessUnsafe

import Command.EnvMode

// --- StdioSink ---

private[kyo] enum NodeStdioSink derives CanEqual:
    case Pipe
    case Inherit
    case ToFile(path: kyo.Path, append: Boolean)
end NodeStdioSink

// --- NodeCommandUnsafe — Command.Unsafe backed by node:child_process.spawn ---

final private[kyo] class NodeCommandUnsafe(
    val args: Chunk[String],
    val workDir: Maybe[kyo.Path] = Absent,
    val envMode: EnvMode = EnvMode.Inherit,
    val stdinSource: Process.Input = Process.Input.Inherit,
    val stdinStream: Maybe[Stream[Byte, Sync]] = Absent,
    val stdoutSink: NodeStdioSink = NodeStdioSink.Pipe,
    val stderrSink: NodeStdioSink = NodeStdioSink.Pipe,
    val redirectError: Boolean = false,
    val pipeTo: Maybe[Command.Unsafe] = Absent,
    val processGroup: Boolean = false
) extends Command.Unsafe:

    // --- Builder methods — all return new instances ---

    def withProcessGroup: NodeCommandUnsafe                         = copy(processGroup = true)
    def withCwd(path: kyo.Path): NodeCommandUnsafe                  = copy(workDir = Present(path))
    def withEnvAppend(vars: Map[String, String]): NodeCommandUnsafe =
        val newMode = envMode match
            case EnvMode.Clear                         => EnvMode.ClearThenAppend(vars)
            case EnvMode.ClearThenAppend(prev)         => EnvMode.ClearThenAppend(prev ++ vars)
            case EnvMode.Remove(names)                 => EnvMode.AppendThenRemove(vars -- names, names)
            case EnvMode.AppendThenRemove(prev, names) => EnvMode.AppendThenRemove((prev ++ vars) -- names, names)
            case _                                     => EnvMode.Append(vars)
        copy(envMode = newMode)
    end withEnvAppend
    def withEnvRemove(names: Set[String]): NodeCommandUnsafe =
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

    // --- Internal helpers ---

    /** Builds the options object for NodeChildProcess.spawn. */
    private def buildOptions()(using AllowUnsafe): js.Dynamic =
        val opts = js.Dynamic.literal()

        workDir.foreach { path => opts.cwd = path.unsafe.show }

        envMode match
            case EnvMode.Inherit      => ()
            case EnvMode.Append(vars) =>
                val env = js.Dynamic.global.Object.assign(
                    js.Dynamic.literal(),
                    js.Dynamic.global.process.env
                )
                vars.foreach { (k, v) => env.updateDynamic(k)(v) }
                opts.env = env
            case EnvMode.Remove(names) =>
                val env = js.Dynamic.global.Object.assign(
                    js.Dynamic.literal(),
                    js.Dynamic.global.process.env
                )
                names.foreach(name => discard(js.Dynamic.global.Reflect.deleteProperty(env, name)))
                opts.env = env
            case EnvMode.AppendThenRemove(vars, names) =>
                val env = js.Dynamic.global.Object.assign(
                    js.Dynamic.literal(),
                    js.Dynamic.global.process.env
                )
                vars.foreach { (k, v) => env.updateDynamic(k)(v) }
                names.foreach(name => discard(js.Dynamic.global.Reflect.deleteProperty(env, name)))
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
            // When redirectError=true, still pipe stderr so we can merge its data events
            // into the stdout NodeInputStream (matching JVM's ProcessBuilder behavior).
            // The proc.stderr stream will be marked as ended (stderrEnded=true).
            if redirectError then "pipe"
            else if stderrSink == NodeStdioSink.Pipe then "pipe"
            else if stderrSink == NodeStdioSink.Inherit then "inherit"
            else "pipe"

        opts.stdio = js.Array(stdioIn, stdioOut, stdioErr)
        opts
    end buildOptions

    /** Checks whether the command exists and is executable without running it.
      *
      * For absolute paths: checks the file directly with fs.accessSync. For names without a separator: scans the PATH directories. Returns
      * Present(CommandException) if the program is definitely not found/accessible, or Absent to proceed with the real spawn.
      */
    private def checkCommandExists()(using Frame): Maybe[CommandException] =
        if args.isEmpty then Present(ProgramNotFoundException(""))
        else
            val cmd = args.head
            try
                val fs       = NodeFsModule.asInstanceOf[js.Dynamic]
                val nodePath = NodePathModule.asInstanceOf[js.Dynamic]
                val X_OK     = fs.constants.X_OK.asInstanceOf[Int]
                // Helper: check if a file exists and is executable
                def isExec(p: String): Boolean =
                    try
                        discard(fs.accessSync(p, X_OK))
                        true
                    catch case _: Throwable => false
                end isExec
                // Absolute path or relative path with directory separator: check directly.
                val isWin = Platform.isWindows
                if cmd.startsWith("/") || cmd.startsWith("\\") || cmd.contains("/") || cmd.contains(
                        "\\"
                    ) || (cmd.length > 1 && cmd(1) == ':')
                then
                    if isExec(cmd) then Absent
                    else Present(ProgramNotFoundException(cmd))
                else
                    // Bare command name: scan PATH
                    val pathEnv = js.Dynamic.global.process.env.PATH
                    val pathStr = if js.typeOf(pathEnv) == "string" then pathEnv.asInstanceOf[String] else ""
                    val pathSep = if isWin then ";" else ":"
                    val dirs    = pathStr.split(pathSep)
                    // On Windows, check with PATHEXT extensions (.exe, .cmd, .bat, etc.)
                    val extensions =
                        if isWin then
                            val pathExt = js.Dynamic.global.process.env.PATHEXT
                            if js.typeOf(pathExt) == "string" then pathExt.asInstanceOf[String].toLowerCase.split(";").toSeq
                            else Seq(".exe", ".cmd", ".bat", ".com")
                        else Seq("")
                    val found = dirs.exists { dir =>
                        extensions.exists { ext =>
                            val full = nodePath.join(dir, cmd + ext).asInstanceOf[String]
                            isExec(full)
                        }
                    }
                    if found then Absent
                    else Present(ProgramNotFoundException(cmd))
                end if
            catch case _: Throwable => Absent // If check fails, let spawn() proceed and handle the error
            end try
        end if
    end checkCommandExists

    /** Translates a js.JavaScriptException from spawn into a CommandException. */
    private def translateError(e: js.JavaScriptException)(using Frame): CommandException =
        val err  = e.exception.asInstanceOf[js.Dynamic]
        val code = if js.typeOf(err.code) == "string" then err.code.asInstanceOf[String] else ""
        code match
            case "ENOENT"           => ProgramNotFoundException(args.headMaybe.getOrElse(""))
            case "EACCES" | "EPERM" => PermissionDeniedException(args.headMaybe.getOrElse(""))
            case _                  => ProgramNotFoundException(args.headMaybe.getOrElse(""))
        end match
    end translateError

    // --- Effectful operations ---

    override private[kyo] def acquire()(using Frame): Process.Unsafe < (Async & Scope & Abort[CommandException]) =
        if !processGroup then acquirePlain()
        else if Platform.isWindows then Abort.fail(ProcessGroupUnsupportedException(args.headMaybe.getOrElse("")))
        else
            val chain = Chunk.from(pipelineChain)
            if chain.size == 1 then
                acquireGroupStage(Absent, Absent, Absent).map { process =>
                    Sync.Unsafe.defer {
                        feedOwned(process)
                        process
                    }
                }
            else
                for
                    links       <- Kyo.foreach(Chunk.from(0 until chain.size - 1))(_ => ProcessDescriptorPair.init(args.head))
                    errors      <- ProcessDescriptorPair.init(args.head)
                    errorReader <- Scope.acquireRelease(Sync.Unsafe.defer {
                        val bindings = kyo.ffi.Ffi.load[ProcessPipeBindings]
                        ProcessNodeInputStream.init(errors.takeRead(), bindings)
                    })(_.closeAndWait())
                    stages <- Kyo.foreach(Chunk.from(0 until chain.size)) { index =>
                        // Unsafe: descriptor ownership is inspected only while acquiring this stage.
                        Sync.Unsafe.defer {
                            val input  = if index == 0 then Absent else Present(links(index - 1).readDescriptor)
                            val output = if index == chain.size - 1 then Absent else Present(links(index).writeDescriptor)
                            val error  = if chain(index).stderrSink == NodeStdioSink.Pipe then Present(errors.writeDescriptor) else Absent
                            chain(index).acquireGroupStage(input, output, error)
                        }
                    }
                    pipeline <- Scope.acquireRelease(Sync.Unsafe.defer {
                        links.foreach(_.close())
                        errors.closeWrite()
                        chain.head.feedOwned(stages.head)
                        new ProcessPipeline(stages, errorReader)
                    })(_.close())
                yield pipeline
            end if
        end if
    end acquire

    private def acquireGroupStage(input: Maybe[Int], output: Maybe[Int], errors: Maybe[Int])(using
        Frame
    ): ProcessSupervisor < (Async & Scope & Abort[CommandException]) =
        Sync.Unsafe.defer {
            if args.isEmpty then Abort.fail(ProgramNotFoundException(""))
            else
                workDir.map(path => Abort.when(!NodeFs.existsSync(path.unsafe.show))(WorkingDirectoryNotFoundException(path)))
                    .getOrElse(Kyo.unit).andThen {
                        val inputMask  = if input.isDefined || (stdinStream.isEmpty && stdinSource == Process.Input.Inherit) then 0 else 1
                        val outputMask = if output.isEmpty && stdoutSink == NodeStdioSink.Pipe then 2 else 0
                        val errorMask  = if redirectError then 8 else if errors.isEmpty && stderrSink == NodeStdioSink.Pipe then 4 else 0
                        val mask       = inputMask | outputMask | errorMask
                        ProcessSupervisor.init(args.head, mask) { (executable, endpoint) =>
                            var files                                                          = List.empty[Int]
                            def descriptor(sink: NodeStdioSink, inherited: Maybe[Int]): js.Any = inherited match
                                case Present(fd) => fd
                                case Absent      => sink match
                                        case NodeStdioSink.ToFile(path, append) =>
                                            val fd = NodeFs.openSync(path.unsafe.show, if append then "a" else "w")
                                            files = fd :: files
                                            fd
                                        case _ => "inherit"
                            try
                                val options = buildOptions()
                                options.detached = false
                                val stdioInput: js.Any = input match
                                    case Present(fd) => fd
                                    case Absent      => "inherit"
                                options.stdio = js.Array[js.Any](
                                    stdioInput,
                                    descriptor(stdoutSink, output),
                                    if redirectError then "inherit" else descriptor(stderrSink, errors)
                                )
                                val arguments = Chunk(endpoint, "", mask.toString) ++ args
                                val child     = NodeChildProcess.spawn(executable, js.Array(arguments.toSeq*), options)
                                val helper    = NodeProcessUnsafe.init(child)
                                discard(child.on(
                                    "error",
                                    { (error: js.Any) => helper.markError(translateError(js.JavaScriptException(error))) }
                                ))
                                Result.succeed(helper)
                            catch case error: js.JavaScriptException => Result.fail(translateError(error))
                            finally files.foreach(NodeFs.closeSync)
                            end try
                        }
                    }
            end if
        }
    end acquireGroupStage

    private def feedOwned(process: ProcessSupervisor)(using Frame, AllowUnsafe): Unit =
        feedInto(process.streams.write, () => process.streams.finishInput(), process.registerInputFeed)

    private def feedInto(
        write: (Array[Byte], Int, Int) => Unit < Async,
        finish: () => Unit < Async,
        register: (() => Unit) => Unit
    )(using Frame, AllowUnsafe): Unit =
        def start(body: => Unit < Async, source: Maybe[InputStream]): Unit =
            val feed = Fiber.Unsafe.init {
                Abort.run[Throwable](body).andThen(finish())
                    .handle(Sync.ensure(source.foreach(_.close())))
            }
            register(() =>
                discard(feed.interrupt())
                source.foreach(_.close())
            )
        end start
        stdinStream match
            case Present(stream) =>
                start(
                    stream.foreachChunk { chunk =>
                        val bytes = chunk.toArray
                        write(bytes, 0, bytes.length)
                    },
                    Absent
                )
            case Absent => stdinSource match
                    case Process.Input.FromStream(source) =>
                        start(
                            {
                                val bytes = new Array[Byte](8192)
                                Loop.foreach {
                                    Sync.defer(source.read(bytes)).map { count =>
                                        if count < 0 then Loop.done
                                        else if count == 0 then Loop.continue
                                        else write(bytes, 0, count).andThen(Loop.continue)
                                    }
                                }
                            },
                            Present(source)
                        )
                    case _ => ()
        end match
    end feedInto

    private def acquirePlain()(using Frame): Process.Unsafe < (Async & Scope & Abort[CommandException]) =
        val chain = Chunk.from(pipelineChain)
        // The owner exists before spawning. All children and pipes are wired in one JS turn: Node may flush an unclaimed stdout on exit.
        Scope.acquireRelease(Sync.defer(scala.collection.mutable.ArrayBuffer.empty[NodeProcessUnsafe])) { owners =>
            val initial: Unit < Async = Kyo.unit
            owners.reverseIterator.foldLeft(initial)((previous, process) => ProcessStreams.finish(previous)(process.close()))
        }.map { owners =>
            Sync.Unsafe.defer {
                val started = chain.zipWithIndex.foldLeft(Result.succeed(()): Result[CommandException, Unit]) { (previous, entry) =>
                    previous.flatMap { _ =>
                        val (command, index) = entry
                        command.copy(
                            pipeTo = Absent,
                            stdoutSink = if index + 1 == chain.size then command.stdoutSink else NodeStdioSink.Pipe,
                            stdinSource = if index == 0 then command.stdinSource else Process.Input.Pipe,
                            stdinStream = if index == 0 then command.stdinStream else Absent
                        ).spawnRuntime(false).map(process => discard(owners += process))
                    }
                }
                Abort.get(started.map { _ =>
                    val children = Chunk.from(owners.map(_.child))
                    children.sliding(2).foreach { pair =>
                        if pair.size == 2 then discard(pair(0).stdout.pipe(pair(1).stdin))
                    }
                    val process = NodeProcessUnsafe.init(children.last, chain.last.redirectError, children.dropRight(1).toSeq)
                    discard(owners += process)
                    chain.head.feedInto(process.writeInput, () => Sync.defer(process.stdinJava.close()), process.registerInputFeed)
                    process: Process.Unsafe
                })
            }.map { process =>
                Kyo.foreachDiscard(Chunk.from(owners.dropRight(1)))(_.awaitStartup).andThen(process)
            }
        }
    end acquirePlain

    private[kyo] def spawnRaw()(using AllowUnsafe, Frame): Result[CommandException, Process.Unsafe] =
        spawnRuntime(true)

    /** The caller records this runtime-owned child before yielding. File sinks are inherited descriptors, not parent-side copies. */
    private def spawnRuntime(captureOutputs: Boolean)(using AllowUnsafe, Frame): Result[CommandException, NodeProcessUnsafe] =
        if args.isEmpty then Result.fail(ProgramNotFoundException(""))
        else
            checkCommandExists() match
                case Present(error) => Result.fail(error)
                case Absent         =>
                    workDir match
                        case Present(path) if !NodeFs.existsSync(path.unsafe.show) => Result.fail(WorkingDirectoryNotFoundException(path))
                        case _                                                     =>
                            var files                                                   = List.empty[Int]
                            def descriptor(sink: NodeStdioSink, inherited: Int): js.Any = sink match
                                case NodeStdioSink.Pipe                 => "pipe"
                                case NodeStdioSink.Inherit              => inherited
                                case NodeStdioSink.ToFile(path, append) =>
                                    val fd = NodeFs.openSync(path.unsafe.show, if append then "a" else "w")
                                    files = fd :: files
                                    fd
                            try
                                val options = buildOptions()
                                val input   = options.stdio.asInstanceOf[js.Array[js.Any]](0)
                                val output  = descriptor(stdoutSink, 1)
                                val error   = if redirectError then output else descriptor(stderrSink, 2)
                                options.stdio = js.Array[js.Any](input, output, error)
                                val child = NodeChildProcess.spawn(args.head, js.Array(args.drop(1).toSeq*), options)
                                NodeWritableStream.guard(child.stdin)
                                val process = NodeProcessUnsafe.init(child, redirectError, captureOutputs = captureOutputs)
                                discard(child.on(
                                    "error",
                                    { (error: js.Any) => process.markError(translateError(js.JavaScriptException(error))) }
                                ))
                                Result.succeed(process)
                            catch
                                case error: js.JavaScriptException => Result.fail(translateError(error))
                                case error: Throwable              => Result.panic(error)
                            finally files.foreach(NodeFs.closeSync)
                            end try
        end if
    end spawnRuntime

    private def copy(
        args: Chunk[String] = this.args,
        workDir: Maybe[kyo.Path] = this.workDir,
        envMode: EnvMode = this.envMode,
        stdinSource: Process.Input = this.stdinSource,
        stdinStream: Maybe[Stream[Byte, Sync]] = this.stdinStream,
        stdoutSink: NodeStdioSink = this.stdoutSink,
        stderrSink: NodeStdioSink = this.stderrSink,
        redirectError: Boolean = this.redirectError,
        pipeTo: Maybe[Command.Unsafe] = this.pipeTo,
        processGroup: Boolean = this.processGroup
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
            pipeTo,
            processGroup
        )

end NodeCommandUnsafe

// --- ProcessPlatformSpecific — factory used by Command.apply in shared code ---

private[kyo] object ProcessPlatformSpecific:

    /** Creates a `Command` from the given argument list. */
    def makeCommand(args: Chunk[String]): Command =
        new NodeCommandUnsafe(args).safe

end ProcessPlatformSpecific
