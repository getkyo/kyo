package kyo.internal

import kyo.*
import scala.annotation.tailrec

/** Stateful NDJSON (Newline-Delimited JSON) decoder for streaming byte chunks.
  *
  * Accumulates text across chunk boundaries and parses complete lines. Each call to `decode` may return zero or more values depending on
  * how much data has arrived.
  */
final private[kyo] class NdjsonDecoder[V](schema: Schema[V]):
    private val utf8   = Utf8StreamDecoder()
    private var buffer = ""

    /** Decode a chunk of bytes, returning any complete NDJSON values found. */
    def decode(bytes: Span[Byte]): Seq[V] =
        buffer += utf8.decode(Chunk.from(bytes.toArrayUnsafe))
        val values = Seq.newBuilder[V]

        @tailrec def loop(): Unit =
            val idx = buffer.indexOf('\n')
            if idx >= 0 then
                val line = buffer.substring(0, idx).trim
                buffer = buffer.substring(idx + 1)
                if line.nonEmpty then
                    values += schema.decode(line)
                loop()
            end if
        end loop
        loop()

        values.result()
    end decode

end NdjsonDecoder
