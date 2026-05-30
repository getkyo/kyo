package kyo

import kyo.Stream

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
  *  - [[JsonRpcTransport.fromWire]]: wraps a [[JsonRpcWireTransport]] + [[JsonRpcFramer]] + [[JsonRpcCodec]].
  *  - [[JsonRpcTransport.stdio]]: line-delimited stdin/stdout transport for CLI servers.
  *
  * @see [[JsonRpcHandler]]
  */
trait JsonRpcTransport:
    def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed])
    def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]]
    def close(using Frame): Unit < Async
end JsonRpcTransport

object JsonRpcTransport:

    // --- Backward-compat type aliases for the 2 hoisted types (Phase G) ---
    // These allow existing callers using the qualified form to continue compiling.

    /** @see [[JsonRpcWireTransport]] */
    type WireTransport = JsonRpcWireTransport

    /** @see [[JsonRpcWireTransport]] */
    val WireTransport: JsonRpcWireTransport.type = JsonRpcWireTransport

    /** @see [[JsonRpcFramer]] */
    type Framer = JsonRpcFramer

    /** @see [[JsonRpcFramer]] */
    val Framer: JsonRpcFramer.type = JsonRpcFramer

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
        codec: JsonRpcCodec = JsonRpcCodec.Strict2_0
    )(using Frame): JsonRpcTransport < (Async & Scope) =
        Sync.defer(new internal.transport.WireTransportAdapter(wire, framer, codec))

    /** Line-delimited stdio transport for CLI-style RPC servers. Reads `Console.readLine`
      * and writes `Console.printLine`. EOF on stdin closes `incoming`. One envelope per line.
      */
    def stdio(
        framer: JsonRpcFramer = JsonRpcFramer.lineDelimited,
        codec: JsonRpcCodec = JsonRpcCodec.Strict2_0
    )(using Frame): JsonRpcTransport < (Async & Scope) =
        Sync.defer(new internal.transport.StdioWireTransport).map { wire =>
            fromWire(wire, framer, codec)
        }

    /** Unix domain socket transport.
      *
      * On JVM, binds a `ServerSocketChannel` using the native NIO Unix-domain-socket API
      * (`StandardProtocolFamily.UNIX`), registers a [[Scope]] cleanup that closes the
      * channel and deletes the socket file, and exposes the connection as a
      * [[JsonRpcTransport]].
      *
      * On Scala.js and Scala Native, this method immediately fails with an
      * [[UnsupportedOperationException]] because the NIO UDS APIs are not available on
      * those platforms.
      *
      * @param sockPath path to the socket file (must not already exist)
      * @param framer   byte-stream framing strategy; defaults to [[JsonRpcFramer.lineDelimited]]
      * @param codec    envelope serialisation; defaults to [[JsonRpcCodec.Strict2_0]]
      */
    def unixDomain(
        sockPath: java.nio.file.Path,
        framer: JsonRpcFramer = JsonRpcFramer.lineDelimited,
        codec: JsonRpcCodec = JsonRpcCodec.Strict2_0
    )(using Frame): JsonRpcTransport < (Async & Scope & Abort[Throwable]) =
        internal.transport.UdsBackend.connect(sockPath, framer, codec)

end JsonRpcTransport
