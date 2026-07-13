package kyo.internal.transport

import kyo.*
import kyo.net.NetException
import kyo.net.NetPlatform

/** Unix-domain-socket backend over kyo-net, shared across JVM, JS, Native, and Wasm.
  *
  * Binds a listener on `sockPath` through the platform transport and serves a single client: the first accepted connection completes `first` and
  * becomes the wire; any later accept is closed immediately. Scope cleanup closes the accepted connection, closes the listener, and removes the
  * socket file (kyo-net does not unlink it). Replaces the former per-platform implementations (a raw java.nio server on the JVM, throw-stubs on
  * JS and Native) with one path that runs everywhere kyo-net's transport runs.
  */
private[kyo] object UdsBackend:

    def connect(
        sockPath: Path,
        framer: JsonRpcFramer = JsonRpcFramer.lineDelimited,
        codec: Schema[JsonRpcEnvelope] = summon[Schema[JsonRpcEnvelope]]
    )(using Frame): JsonRpcTransport < (Async & Scope & Abort[Throwable]) =
        // Unsafe: listenUnix and Promise.Unsafe are unsafe-tier; the AllowUnsafe bridged here is captured by the accept-handler closure below.
        Sync.Unsafe.defer {
            val first = Promise.Unsafe.init[kyo.net.Connection, Abort[NetException | Closed]]()
            NetPlatform.transport.listenUnix(sockPath.toString, backlog = 1) { conn =>
                // Single-client server: the first accept wins and becomes the wire; a later client is closed immediately rather than left
                // un-accepted in the kernel backlog.
                if !first.complete(Result.succeed(conn)) then conn.close()
            }.safe.get.map { listener =>
                val wire: JsonRpcWireTransport = new UdsServerWireTransport(first)
                Scope.ensure {
                    wire.close
                        .andThen(Sync.Unsafe.defer(listener.close()))
                        .andThen(Abort.run[FileFsException](sockPath.remove).unit)
                }.andThen {
                    JsonRpcTransport.fromWire(wire, framer, codec)
                }
            }
        }
end UdsBackend

/** Wire over the single client a [[UdsBackend]] listener accepts. `send`/`incoming` park on the first-accept promise until a client arrives;
  * `close` unblocks a never-connected wire by failing the promise Closed, and closes the accepted connection if one exists.
  */
final private[kyo] class UdsServerWireTransport(
    first: Promise.Unsafe[kyo.net.Connection, Abort[NetException | Closed]]
) extends JsonRpcWireTransport:

    def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) =
        val p: Promise[kyo.net.Connection, Abort[NetException | Closed]]         = first.safe
        val pending: kyo.net.Connection < (Async & Abort[NetException | Closed]) = p.get
        Abort.run[NetException | Closed](pending).map {
            case Result.Success(conn)           => conn.outbound.safe.put(Span.fromUnsafe(bytes.toArray))
            case Result.Failure(closed: Closed) => Abort.fail(closed)
            case Result.Failure(e: NetException) =>
                Abort.panic(e) // a transport failure reaching the first-accept promise surfaces typed, never silently
            case Result.Panic(e) => Abort.panic(e)
        }
    end send

    def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
        Stream:
            val p: Promise[kyo.net.Connection, Abort[NetException | Closed]]         = first.safe
            val pending: kyo.net.Connection < (Async & Abort[NetException | Closed]) = p.get
            Abort.run[NetException | Closed](pending).map {
                case Result.Success(conn)      => ConnectionWireTransport(conn).incoming.emit
                case Result.Failure(_: Closed) => () // closed before any client connected: empty stream, orderly end
                case Result.Failure(e: NetException) =>
                    Abort.panic(e) // a transport failure reaching the first-accept promise surfaces typed, never silently
                case Result.Panic(e) => Abort.panic(e)
            }

    def close(using Frame): Unit < Async =
        // Unsafe: promise completion and Connection.close are unsafe-tier, both idempotent.
        Sync.Unsafe.defer {
            val closedEarly =
                first.complete(Result.fail(Closed("UdsBackend", summon[Frame], "closed before a client connected")))
            if !closedEarly then
                first.poll() match
                    case Present(Result.Success(conn)) => conn.eval.close()
                    case _                             => ()
            end if
        }
end UdsServerWireTransport
