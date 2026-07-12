package kyo

import kyo.Stream
import kyo.net.NetException
import kyo.net.NetPlatform

/** Envelope-level message channel between two JSON-RPC peers.
  *
  * Implement this trait to connect an existing I/O layer to the endpoint. The lifecycle
  * methods are:
  *  - `send`: transmit an outbound [[JsonRpcEnvelope]] to the peer.
  *  - `incoming`: a stream of inbound [[JsonRpcEnvelope]] values received from the peer.
  *  - `close`: tear down the underlying connection.
  *
  * Pre-built factories in the companion cover the most common cases:
  *  - [[JsonRpcTransport.inMemory]]: paired in-memory channels for testing.
  *  - [[JsonRpcTransport.fromWire]]: wraps a [[JsonRpcWireTransport]] + [[JsonRpcFramer]] + a `Schema[JsonRpcEnvelope]`.
  *  - [[JsonRpcTransport.stdio]]: line-delimited stdin/stdout transport for CLI servers.
  *  - [[JsonRpcTransport.contentLengthStdio]]: Content-Length-framed stdio transport for LSP, DAP,
  *    BSP, and other header-framed JSON-RPC protocols.
  *  - [[JsonRpcTransport.unixDomain]]: Unix-domain-socket transport.
  *
  * @see [[JsonRpcHandler]]
  */
trait JsonRpcTransport:
    def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed])
    def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]]
    def close(using Frame): Unit < Async
end JsonRpcTransport

object JsonRpcTransport:

    /** Pair of cross-wired in-memory transports for tests.
      *
      * Returns (a, b) where a.send -> b.incoming and b.send -> a.incoming.
      * close on either end terminates both incoming streams.
      */
    def inMemory(capacity: Int)(using Frame): (JsonRpcTransport, JsonRpcTransport) < Sync =
        for
            // Unsafe: Channel.initUnscoped is the kyo idiom for fields that must be
            // released by the owning component's close() rather than Scope finalizers.
            aToB <- Channel.initUnscoped[JsonRpcEnvelope](capacity)
            bToA <- Channel.initUnscoped[JsonRpcEnvelope](capacity)
        yield
            // type-widening from internal subtype to public supertype required for the returned tuple element type
            val a: JsonRpcTransport = new internal.transport.InMemoryTransport(out = aToB, in = bToA)
            // type-widening from internal subtype to public supertype required for the returned tuple element type
            val b: JsonRpcTransport = new internal.transport.InMemoryTransport(out = bToA, in = aToB)
            (a, b)

    def inMemory(using Frame): (JsonRpcTransport, JsonRpcTransport) < Sync = inMemory(64)

    /** Lifts a byte-stream transport plus framer plus envelope codec into the envelope-level
      * `JsonRpcTransport` seam. Inbound bytes pass through `framer.parse` and `codec.decode`;
      * outbound envelopes pass through `codec.encode` and `framer.frame`.
      */
    def fromWire(
        wire: JsonRpcWireTransport,
        framer: JsonRpcFramer,
        codec: Schema[JsonRpcEnvelope] = summon[Schema[JsonRpcEnvelope]]
    )(using Frame): JsonRpcTransport < (Async & Scope) =
        Sync.defer(new internal.transport.WireTransportAdapter(wire, framer, codec))

    /** Line-delimited stdio transport for CLI-style RPC servers. Reads `Console.readLine`
      * and writes `Console.printLine`. EOF on stdin closes `incoming`. One envelope per line.
      */
    def stdio(
        framer: JsonRpcFramer = JsonRpcFramer.lineDelimited,
        codec: Schema[JsonRpcEnvelope] = summon[Schema[JsonRpcEnvelope]]
    )(using Frame): JsonRpcTransport < (Async & Scope) =
        Sync.defer(new internal.transport.StdioWireTransport).map { wire =>
            fromWire(wire, framer, codec)
        }

    /** Unix domain socket transport, served over the platform kyo-net transport.
      *
      * Binds a Unix-domain listener on `sockPath` and serves a single client: the first accepted connection becomes the
      * wire, and a [[Scope]] cleanup closes the connection and listener and removes the socket file. Works on every
      * platform kyo-net targets: JVM (the posix io_uring/epoll/kqueue backend, or the NIO floor), Native (posix), and
      * JS/Wasm (Node's `net` module, so it requires a Node.js runtime; a browser has no sockets).
      *
      * @param sockPath path to the socket file (must not already exist)
      * @param framer   byte-stream framing strategy; defaults to [[JsonRpcFramer.lineDelimited]]
      * @param codec    envelope serialisation; defaults to the strict `Schema[JsonRpcEnvelope]`
      */
    def unixDomain(
        sockPath: Path,
        framer: JsonRpcFramer = JsonRpcFramer.lineDelimited,
        codec: Schema[JsonRpcEnvelope] = summon[Schema[JsonRpcEnvelope]]
    )(using Frame): JsonRpcTransport < (Async & Scope & Abort[NetException]) =
        internal.transport.UdsBackend.connect(sockPath, framer, codec)

    /** Content-Length-framed stdio transport for JSON-RPC (LSP, DAP, BSP).
      *
      * Reads `Content-Length: N\r\n\r\n<N bytes>` frames from process stdin and writes matching frames to process stdout,
      * over the platform kyo-net transport's stdio connection (fds 0/1 on JVM and Native, `process.stdin`/`process.stdout`
      * on Node). Headers other than Content-Length are skipped on the read side; the write side emits strict CRLF as the LSP
      * base protocol requires.
      *
      * Stdio is process-global: one stdio transport per process. A second `contentLengthStdio()` (or a
      * [[JsonRpcTransport.stdio]] byte-stream claim) in the same process aborts [[kyo.net.NetStdioAlreadyOpenException]].
      *
      * To frame Content-Length messages over an arbitrary byte-stream pair (for example a spawned subprocess's pipes)
      * rather than process stdio, implement the [[JsonRpcWireTransport]] seam and pass it to [[fromWire]] with
      * [[JsonRpcFramer.contentLength]].
      *
      * @param framer framing strategy; defaults to [[JsonRpcFramer.contentLength]]
      * @param codec  envelope serialisation; defaults to the strict `Schema[JsonRpcEnvelope]`
      */
    def contentLengthStdio(
        framer: JsonRpcFramer = JsonRpcFramer.contentLength,
        codec: Schema[JsonRpcEnvelope] = summon[Schema[JsonRpcEnvelope]]
    )(using Frame): JsonRpcTransport < (Async & Scope & Abort[NetException]) =
        // Unsafe: Transport.stdio() is unsafe-tier; bridged once here.
        Sync.Unsafe.defer {
            NetPlatform.transport.stdio().safe.get.map { conn =>
                val wire: JsonRpcWireTransport = internal.transport.ConnectionWireTransport(conn)
                Scope.ensure(wire.close).andThen {
                    JsonRpcTransport.fromWire(wire, framer, codec)
                }
            }
        }

end JsonRpcTransport
