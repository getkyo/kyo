package kyo.internal

import kyo.*
import scala.annotation.tailrec

/** Stateful SSE (Server-Sent Events) decoder for streaming byte chunks.
  *
  * Accumulates text across chunk boundaries and parses complete SSE events (delimited by blank lines). Each call to `decode` may return
  * zero or more events depending on how much data has arrived.
  */
final private[kyo] class SseDecoder[V](schema: Schema[V]):
    private val utf8   = Utf8StreamDecoder()
    private var buffer = ""

    /** Decode a chunk of bytes, returning any complete SSE events found. */
    def decode(bytes: Span[Byte]): Seq[HttpEvent[V]] =
        buffer += utf8.decode(Chunk.from(bytes.toArrayUnsafe)).replace("\r\n", "\n").replace("\r", "\n")
        val events = Seq.newBuilder[HttpEvent[V]]

        // Split on double-newline (SSE event boundary)
        @tailrec def loop(): Unit =
            val idx = buffer.indexOf("\n\n")
            if idx >= 0 then
                val block = buffer.substring(0, idx)
                buffer = buffer.substring(idx + 2)
                parseEvent(block).foreach(events += _)
                loop()
            end if
        end loop
        loop()

        events.result()
    end decode

    private def parseEvent(block: String): Maybe[HttpEvent[V]] =
        val dataLines              = Seq.newBuilder[String]
        var event: Maybe[String]   = Absent
        var id: Maybe[String]      = Absent
        var retry: Maybe[Duration] = Absent

        block.split('\n').foreach { line =>
            if line.startsWith("data: ") then
                dataLines += line.substring(6)
            else if line.startsWith("event: ") then
                event = Present(line.substring(7))
            else if line.startsWith("id: ") then
                id = Present(line.substring(4))
            else if line.startsWith("retry: ") then
                line.substring(7).toLongOption.foreach(ms => retry = Present(Duration.fromUnits(ms, Duration.Units.Millis)))
            // Per SSE spec, unknown fields and comment lines (starting with ':') are silently ignored
        }

        val data = dataLines.result()
        Maybe.when(data.nonEmpty)(HttpEvent(schema.decode(data.mkString("\n")), event, id, retry))
    end parseEvent

end SseDecoder
