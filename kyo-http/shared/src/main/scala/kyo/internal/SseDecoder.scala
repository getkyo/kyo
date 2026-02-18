package kyo.internal

import kyo.*
import scala.annotation.tailrec

/** Stateful SSE (Server-Sent Events) decoder for streaming byte chunks.
  *
  * Accumulates text across chunk boundaries and parses complete SSE events (delimited by blank lines). Each call to `decode` may return
  * zero or more events depending on how much data has arrived.
  */
final private[kyo] class SseDecoder[V] private (schema: Schema[V], lineDecoder: LineBufferedDecoder):

    /** Decode a chunk of bytes, returning any complete SSE events found. */
    def decode(bytes: Span[Byte])(using AllowUnsafe, Frame): Result[HttpError.ParseError, Seq[HttpEvent[V]]] =
        val blocks                             = lineDecoder.extract(bytes)
        val builder                            = Seq.newBuilder[HttpEvent[V]]
        var error: Maybe[HttpError.ParseError] = Absent
        blocks.foreach { block =>
            if error.isEmpty then
                parseEvent(block) match
                    case Result.Success(Present(ev)) => discard(builder += ev)
                    case Result.Success(Absent)      =>
                    case Result.Failure(e)           => error = Present(e)
                    case _                           =>
        }
        error match
            case Present(e) => Result.fail(e)
            case Absent     => Result.succeed(builder.result())
    end decode

    private def parseEvent(block: String)(using Frame): Result[HttpError.ParseError, Maybe[HttpEvent[V]]] =
        val lines = block.split('\n')

        @tailrec def loop(
            i: Int,
            dataLines: List[String],
            event: Maybe[String],
            id: Maybe[String],
            retry: Maybe[Duration]
        ): (List[String], Maybe[String], Maybe[String], Maybe[Duration]) =
            if i >= lines.length then (dataLines.reverse, event, id, retry)
            else
                val line = lines(i)
                if line.startsWith("data: ") then
                    loop(i + 1, line.substring(6) :: dataLines, event, id, retry)
                else if line.startsWith("event: ") then
                    loop(i + 1, dataLines, Present(line.substring(7)), id, retry)
                else if line.startsWith("id: ") then
                    loop(i + 1, dataLines, event, Present(line.substring(4)), retry)
                else if line.startsWith("retry: ") then
                    val newRetry = Maybe.fromOption(line.substring(7).toLongOption) match
                        case Present(ms) => Present(Duration.fromUnits(ms, Duration.Units.Millis))
                        case Absent      => retry
                    loop(i + 1, dataLines, event, id, newRetry)
                else
                    // Per SSE spec, unknown fields and comment lines (starting with ':') are silently ignored
                    loop(i + 1, dataLines, event, id, retry)
                end if

        val (dataLines, event, id, retry) = loop(0, Nil, Absent, Absent, Absent)
        if dataLines.isEmpty then Result.succeed(Absent)
        else
            schema.decode(dataLines.mkString("\n")).mapFailure(HttpError.ParseError(_)).map(v => Present(HttpEvent(v, event, id, retry)))
    end parseEvent

end SseDecoder

private[kyo] object SseDecoder:
    def init[V](schema: Schema[V])(using AllowUnsafe): SseDecoder[V] =
        new SseDecoder(schema, LineBufferedDecoder.init("\n\n", normalize = true))
end SseDecoder
