// PUBLIC framer preset library for byte-stream transports (line-delimited stdio, Content-Length envelopes)
package kyo

import kyo.Stream
import kyo.Sync

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
            internal.FramerImpl.parseLineDelimited(stream)

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
            internal.FramerImpl.parseContentLength(stream)
end Framer
