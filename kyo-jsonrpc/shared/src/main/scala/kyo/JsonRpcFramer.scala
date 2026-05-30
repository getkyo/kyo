package kyo

import kyo.Stream

/** Encodes and decodes byte-stream boundaries for JSON-RPC messages carried over a byte-level
  * transport.
  *
  * A `JsonRpcFramer` controls how raw bytes are split into discrete message frames on the wire:
  *  - `frame`: wraps a single encoded message for transmission (e.g., appends `\n` or a
  *    `Content-Length` header).
  *  - `parse`: converts a raw byte stream into a stream of complete message frames.
  *
  * Two preset framers are provided:
  *  - [[JsonRpcFramer.lineDelimited]]: newline-delimited framing, suitable for stdio transports.
  *  - [[JsonRpcFramer.contentLength]]: `Content-Length` header framing used by LSP over stdio.
  *
  * Pass the chosen framer to [[JsonRpcTransport.fromWire]] or [[JsonRpcTransport.stdio]].
  *
  * @see [[JsonRpcTransport.fromWire]]
  * @see [[JsonRpcWireTransport]]
  */
trait JsonRpcFramer:
    def frame(bytes: Chunk[Byte])(using Frame): Chunk[Byte] < Sync
    def parse(stream: Stream[Chunk[Byte], Async & Abort[Closed]])(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]]
end JsonRpcFramer

object JsonRpcFramer:

    /** One frame per LF-terminated segment. Trailing CR before LF is stripped.
      * Empty lines are skipped. EOF closes the stream without flushing a partial line.
      */
    val lineDelimited: JsonRpcFramer = new JsonRpcFramer:
        def frame(bytes: Chunk[Byte])(using Frame): Chunk[Byte] < Sync =
            Sync.defer(bytes :+ '\n'.toByte)

        def parse(stream: Stream[Chunk[Byte], Async & Abort[Closed]])(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
            internal.framing.FramerImpl.parseLineDelimited(stream)

    /** Content-Length envelope framing: `Content-Length: N\r\n\r\n<N bytes>`. Tolerant
      * of `\n\n` on parse, strict `\r\n\r\n` on emit. Header errors raise
      * `Abort.fail(JsonRpcError.parseError(reason))`.
      */
    val contentLength: JsonRpcFramer = new JsonRpcFramer:
        def frame(bytes: Chunk[Byte])(using Frame): Chunk[Byte] < Sync =
            Sync.defer {
                val header = s"Content-Length: ${bytes.length}\r\n\r\n".getBytes("UTF-8")
                Chunk.from(header) ++ bytes
            }

        def parse(stream: Stream[Chunk[Byte], Async & Abort[Closed]])(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
            internal.framing.FramerImpl.parseContentLength(stream)
end JsonRpcFramer
