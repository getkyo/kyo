package kyo.internal

import kyo.*

/** Stateful SSE (Server-Sent Events) decoder for streaming byte chunks.
  *
  * Accumulates text across chunk boundaries and parses complete SSE events (delimited by blank lines). Each call to `decode` may return
  * zero or more events depending on how much data has arrived.
  */
final class SseDecoder[V](schema: Schema[V]):
    private val utf8   = Utf8StreamDecoder()
    private var buffer = ""

    /** Decode a chunk of bytes, returning any complete SSE events found. */
    def decode(bytes: Span[Byte]): Seq[ServerSentEvent[V]] =
        buffer += utf8.decode(Chunk.from(bytes.toArrayUnsafe))
        val events = Seq.newBuilder[ServerSentEvent[V]]

        // Split on double-newline (SSE event boundary)
        var idx = buffer.indexOf("\n\n")
        while idx >= 0 do
            val block = buffer.substring(0, idx)
            buffer = buffer.substring(idx + 2)
            parseEvent(block).foreach(events += _)
            idx = buffer.indexOf("\n\n")
        end while

        events.result()
    end decode

    private def parseEvent(block: String): Maybe[ServerSentEvent[V]] =
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
            // Lines starting with ':' are comments (keep-alive), ignored
        }

        val data = dataLines.result()
        if data.nonEmpty then
            Present(ServerSentEvent(schema.decode(data.mkString("\n")), event, id, retry))
        else
            Absent
        end if
    end parseEvent

end SseDecoder
