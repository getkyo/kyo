package kyo

import kyo.Stream

/** Envelope-level message channel between two JSON-RPC peers.
  *
  * Implement this trait to connect an existing I/O layer to the endpoint. The two lifecycle
  * methods are:
  *  - `send`: transmit an outbound [[JsonRpcEnvelope]] to the peer.
  *  - `incoming`: a stream of inbound [[JsonRpcEnvelope]] values received from the peer.
  *  - `close`: tear down the underlying connection.
  *
  * Pre-built factories in the companion cover the most common cases:
  *  - [[JsonRpcTransport.inMemory]]: paired in-memory channels for testing.
  *  - [[JsonRpcTransport.fromWire]]: wraps a [[WireTransport]] + [[Framer]] + [[JsonRpcCodec]].
  *  - [[JsonRpcTransport.stdio]]: line-delimited stdin/stdout transport for CLI servers.
  *
  * @see [[JsonRpcEndpoint]]
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
        wire: WireTransport,
        framer: Framer,
        codec: JsonRpcCodec = JsonRpcCodec.Strict2_0
    )(using Frame): JsonRpcTransport < (Async & Scope) =
        Sync.defer(new internal.transport.WireTransportAdapter(wire, framer, codec))

    /** Line-delimited stdio transport for CLI-style RPC servers. Reads `Console.readLine`
      * and writes `Console.printLine`. EOF on stdin closes `incoming`. One envelope per line.
      */
    def stdio(
        framer: Framer = Framer.lineDelimited,
        codec: JsonRpcCodec = JsonRpcCodec.Strict2_0
    )(using Frame): JsonRpcTransport < (Async & Scope) =
        Sync.defer(new internal.transport.StdioWireTransport).map { wire =>
            fromWire(wire, framer, codec)
        }
end JsonRpcTransport
