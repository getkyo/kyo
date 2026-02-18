package kyo.internal

import kyo.*

/** Stateful NDJSON (Newline-Delimited JSON) decoder for streaming byte chunks.
  *
  * Accumulates text across chunk boundaries and parses complete lines. Each call to `decode` may return zero or more values depending on
  * how much data has arrived.
  */
final private[kyo] class NdjsonDecoder[V] private (schema: Schema[V], lineDecoder: LineBufferedDecoder):

    /** Decode a chunk of bytes, returning any complete NDJSON values found. */
    def decode(bytes: Span[Byte])(using AllowUnsafe, Frame): Result[HttpError.ParseError, Seq[V]] =
        val lines                              = lineDecoder.extract(bytes)
        val builder                            = Seq.newBuilder[V]
        var error: Maybe[HttpError.ParseError] = Absent
        lines.foreach { line =>
            val trimmed = line.trim
            if trimmed.nonEmpty && error.isEmpty then
                schema.decode(trimmed) match
                    case Result.Success(v) => discard(builder += v)
                    case Result.Failure(e) => error = Present(HttpError.ParseError(e))
                    case _                 =>
            end if
        }
        error match
            case Present(e) => Result.fail(e)
            case Absent     => Result.succeed(builder.result())
    end decode

end NdjsonDecoder

private[kyo] object NdjsonDecoder:
    def init[V](schema: Schema[V])(using AllowUnsafe): NdjsonDecoder[V] =
        new NdjsonDecoder(schema, LineBufferedDecoder.init("\n"))
end NdjsonDecoder
