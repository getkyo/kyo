package kyo.internal.transport

import java.io.IOException
import kyo.*

/** A child process's stdin and stdout as a byte wire, backing [[JsonRpcTransport.subprocess]].
  *
  * `send` writes one framed message to the child's stdin and flushes it, so a message never waits in a buffer for the next one. `incoming`
  * streams the child's stdout one chunk per read and ends when the child closes it, which is what happens when the child exits.
  */
final private[kyo] class SubprocessWire(process: Process, resource: String) extends JsonRpcWireTransport:

    def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) =
        // Abort.run[IOException] turns the IOException a failed write throws into a failure.
        Abort.run[IOException] {
            // Unsafe: the child's stdin is exposed only as a java.io.OutputStream.
            Sync.Unsafe.defer {
                val stdin = process.unsafe.stdinJava
                stdin.write(bytes.toArray)
                stdin.flush()
            }
        }.map {
            case Result.Success(_) => ()
            // A write to a child that has exited, or after close, fails at the pipe: the peer is gone.
            case Result.Failure(error) => Abort.fail(new Closed(resource, summon[Frame], s"stdin: ${error.getMessage}"))
            case Result.Panic(error)   => Abort.panic(error)
        }

    def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
        process.stdout.mapChunkPure(chunk => Chunk(chunk)).handle(Scope.run)

    def close(using Frame): Unit < Async =
        Abort.run[IOException] {
            // Unsafe: closing the child's stdin, exposed only as a java.io.OutputStream, is how the child learns the session is over.
            Sync.Unsafe.defer(process.unsafe.stdinJava.close())
        }.unit

end SubprocessWire

/** A JSON-RPC transport over a spawned child process, backing [[JsonRpcTransport.subprocess]].
  *
  * Envelopes travel over the child's stdin and stdout through the same framer and codec as every other byte transport. The child's stderr is
  * drained from spawn into a bounded buffer that drops its oldest chunk when full, so a child writing a lot of diagnostics never blocks on a
  * full pipe and a late reader sees the most recent output.
  *
  * Closing is idempotent and always ends with the child gone: stdin is closed and the child gets `closeGracePeriod` to exit on its own, then
  * a termination request and another `closeGracePeriod`, then a forced kill. Concurrent callers wait for the first close to finish.
  */
final private[kyo] class SubprocessTransport(
    val process: Process,
    wire: SubprocessWire,
    envelopes: JsonRpcTransport,
    stderrChunks: Channel[Chunk[Byte]],
    closeGracePeriod: Duration,
    closeStarted: AtomicBoolean,
    closeDone: Fiber.Promise[Unit, Any]
) extends JsonRpcTransport.Subprocess:

    def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed | JsonRpcError]) = envelopes.send(env)

    def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] = envelopes.incoming

    def stderr(using Frame): Stream[Byte, Async] =
        stderrChunks.streamUntilClosed().mapChunkPure(_.flattenChunk)

    def close(using Frame): Unit < Async =
        closeStarted.compareAndSet(false, true).map {
            case false => closeDone.get
            case true  =>
                // The finalizer runs on interruption too, so a close that is cut short still leaves no child running.
                Sync.ensure(forceKillIfAlive.andThen(closeDone.completeUnitDiscard))(terminate)
        }

    private def terminate(using Frame): Unit < Async =
        wire.close.andThen(process.waitFor(closeGracePeriod)).map {
            case Present(_) => Kyo.unit
            case Absent     =>
                process.destroy.andThen(process.waitFor(closeGracePeriod)).map {
                    case Present(_) => Kyo.unit
                    case Absent     => process.destroyForcibly.andThen(process.waitFor).unit
                }
        }

    private def forceKillIfAlive(using Frame): Unit < Sync =
        process.isAlive.map(alive => if alive then process.destroyForcibly else Kyo.unit)

end SubprocessTransport

private[kyo] object SubprocessTransport:

    /** Maximum number of stderr chunks kept for a reader; the oldest is dropped when a new one arrives and the buffer is full. */
    val stderrBufferChunks: Int = 256

    def init(
        command: Command,
        framer: JsonRpcFramer,
        codec: Schema[JsonRpcEnvelope],
        closeGracePeriod: Duration
    )(using Frame): JsonRpcTransport.Subprocess < (Sync & Abort[CommandException]) =
        // The protocol owns stdin and stdout, and stderr must stay off stdout; every other setting is the caller's.
        // Unsafe: Command's builder transitions for these three settings are exposed on its Unsafe tier only; they are pure.
        val protocolCommand = command.unsafe
            .withStdin(Process.Input.Pipe)
            .withInheritStdout(false)
            .withRedirectErrorStream(false)
            .safe
        val resource = s"subprocess ${command.args.headMaybe.getOrElse("")}"
        protocolCommand.spawnUnscoped.map { process =>
            for
                stderrChunks <- Channel.initUnscoped[Chunk[Byte]](stderrBufferChunks)
                closeStarted <- AtomicBoolean.init(false)
                closeDone    <- Fiber.Promise.init[Unit, Any]
                _            <- Fiber.initUnscoped(pumpStderr(process, stderrChunks))
                wire = new SubprocessWire(process, resource)
            yield new SubprocessTransport(
                process,
                wire,
                new WireTransportAdapter(wire, framer, codec),
                stderrChunks,
                closeGracePeriod,
                closeStarted,
                closeDone
            )
        }
    end init

    // Drains the child's stderr until it is closed, keeping the most recent chunks, then ends the buffer so a reader sees the end once it
    // has read what is left.
    private def pumpStderr(process: Process, stderrChunks: Channel[Chunk[Byte]])(using Frame): Unit < Async =
        // Unsafe: closeAwaitEmpty is started without waiting for a reader to drain the buffer; nobody may ever read it.
        Sync.ensure(Sync.Unsafe.defer(discard(stderrChunks.unsafe.closeAwaitEmpty()))) {
            Abort.run[Closed] {
                Scope.run(process.stderr.foreachChunk(chunk => keepLatest(stderrChunks, chunk)))
            }.unit
        }

    private def keepLatest(stderrChunks: Channel[Chunk[Byte]], chunk: Chunk[Byte])(using Frame): Unit < (Sync & Abort[Closed]) =
        stderrChunks.offer(chunk).map {
            case true  => Kyo.unit
            case false => stderrChunks.poll.andThen(stderrChunks.offerDiscard(chunk))
        }

end SubprocessTransport
