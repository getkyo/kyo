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
  *  - [[JsonRpcTransport.fromWire]]: wraps a [[JsonRpcTransport.WireTransport]] + [[JsonRpcTransport.Framer]] + [[JsonRpcCodec]].
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

    /** Byte-level transport abstraction for carrying framed JSON-RPC messages over a byte stream.
      *
      * `WireTransport` is the lower-level seam below [[JsonRpcTransport]]. Implement this trait to
      * integrate a custom byte-stream connection (e.g., a TCP socket, a pipe, or a test double).
      * Pass the implementation to [[JsonRpcTransport.fromWire]] together with a [[JsonRpcTransport.Framer]] to
      * produce an envelope-level `JsonRpcTransport`.
      *
      * A no-op [[WireTransport.empty]] is provided for tests that do not need real I/O.
      *
      * @see [[JsonRpcTransport.fromWire]]
      * @see [[JsonRpcTransport.Framer]]
      */
    trait WireTransport:
        def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed])
        def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]]
        def close(using Frame): Unit < Async
    end WireTransport

    object WireTransport:
        /** No-op wire transport for tests: send drops bytes, incoming is empty, close is no-op. */
        val empty: WireTransport = new WireTransport:
            def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) = Kyo.unit
            def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]]     = Stream.empty
            def close(using Frame): Unit < Async                                      = Kyo.unit
    end WireTransport

    /** Encodes and decodes byte-stream boundaries for JSON-RPC messages carried over a byte-level
      * transport.
      *
      * A `Framer` controls how raw bytes are split into discrete message frames on the wire:
      *  - `frame`: wraps a single encoded message for transmission (e.g., appends `\n` or a
      *    `Content-Length` header).
      *  - `parse`: converts a raw byte stream into a stream of complete message frames.
      *
      * Two preset framers are provided:
      *  - [[Framer.lineDelimited]]: newline-delimited framing, suitable for stdio transports.
      *  - [[Framer.contentLength]]: `Content-Length` header framing used by LSP over stdio.
      *
      * Pass the chosen framer to [[JsonRpcTransport.fromWire]] or [[JsonRpcTransport.stdio]].
      *
      * @see [[JsonRpcTransport.fromWire]]
      * @see [[JsonRpcTransport.WireTransport]]
      */
    trait Framer:
        def frame(bytes: Chunk[Byte])(using Frame): Chunk[Byte] < Sync
        def parse(stream: Stream[Chunk[Byte], Async & Abort[Closed]])(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]]
    end Framer

    object Framer:

        /** One frame per LF-terminated segment. Trailing CR before LF is stripped.
          * Empty lines are skipped. EOF closes the stream without flushing a partial line.
          */
        val lineDelimited: Framer = new Framer:
            def frame(bytes: Chunk[Byte])(using Frame): Chunk[Byte] < Sync =
                Sync.defer(bytes :+ '\n'.toByte)

            def parse(stream: Stream[Chunk[Byte], Async & Abort[Closed]])(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
                internal.framing.FramerImpl.parseLineDelimited(stream)

        /** Content-Length envelope framing: `Content-Length: N\r\n\r\n<N bytes>`. Tolerant
          * of `\n\n` on parse, strict `\r\n\r\n` on emit. Header errors raise
          * `Abort.fail(JsonRpcError.parseError(reason))`.
          */
        val contentLength: Framer = new Framer:
            def frame(bytes: Chunk[Byte])(using Frame): Chunk[Byte] < Sync =
                Sync.defer {
                    val header = s"Content-Length: ${bytes.length}\r\n\r\n".getBytes("UTF-8")
                    Chunk.from(header) ++ bytes
                }

            def parse(stream: Stream[Chunk[Byte], Async & Abort[Closed]])(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
                internal.framing.FramerImpl.parseContentLength(stream)
    end Framer

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
      * @param framer   byte-stream framing strategy; defaults to [[Framer.lineDelimited]]
      * @param codec    envelope serialisation; defaults to [[JsonRpcCodec.Strict2_0]]
      */
    def unixDomain(
        sockPath: java.nio.file.Path,
        framer: Framer = Framer.lineDelimited,
        codec: JsonRpcCodec = JsonRpcCodec.Strict2_0
    )(using Frame): JsonRpcTransport < (Async & Scope & Abort[Throwable]) =
        internal.transport.UdsBackend.connect(sockPath, framer, codec)

end JsonRpcTransport
