package kyo.internal

import kyo.*

/** Stateful NDJSON (Newline-Delimited JSON) decoder for streaming byte chunks.
  *
  * Accumulates text across chunk boundaries and parses complete lines. Each call to `decode` may return zero or more values depending on
  * how much data has arrived.
  */
final class NdjsonDecoder[V](schema: Schema[V]):
    private val utf8   = Utf8StreamDecoder()
    private var buffer = ""

    /** Decode a chunk of bytes, returning any complete NDJSON values found. */
    def decode(bytes: Span[Byte]): Seq[V] =
        buffer += utf8.decode(Chunk.from(bytes.toArrayUnsafe))
        val values = Seq.newBuilder[V]

        var idx = buffer.indexOf('\n')
        while idx >= 0 do
            val line = buffer.substring(0, idx).trim
            buffer = buffer.substring(idx + 1)
            if line.nonEmpty then
                values += schema.decode(line)
            idx = buffer.indexOf('\n')
        end while

        values.result()
    end decode

end NdjsonDecoder
