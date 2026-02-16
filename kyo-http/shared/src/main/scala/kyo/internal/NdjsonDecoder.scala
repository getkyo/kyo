package kyo.internal

import kyo.*

/** Stateful NDJSON (Newline-Delimited JSON) decoder for streaming byte chunks.
  *
  * Accumulates text across chunk boundaries and parses complete lines. Each call to `decode` may return zero or more values depending on
  * how much data has arrived.
  */
final private[kyo] class NdjsonDecoder[V](schema: Schema[V]):
    private val lineDecoder = LineBufferedDecoder("\n")

    /** Decode a chunk of bytes, returning any complete NDJSON values found. */
    def decode(bytes: Span[Byte]): Seq[V] =
        lineDecoder.extract(bytes).flatMap { line =>
            val trimmed = line.trim
            if trimmed.nonEmpty then Seq(schema.decode(trimmed)) else Seq.empty
        }

end NdjsonDecoder
