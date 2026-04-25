package kyo.internal

import kyo.*

/** Accumulates partial-line text across stream chunks, emitting only complete `\n`-terminated lines. Trailing text without a newline is
  * retained in an internal buffer and flushed via [[flush]] on stream termination.
  *
  * Single-threaded by design — create one assembler per stream invocation.
  */
final private[kyo] class LineAssembler:
    private val buffer = new StringBuilder

    /** Append `text` to the internal buffer and return any complete lines it now contains. Partial trailing text is retained for the next
      * call.
      */
    def feed(text: String): Chunk[String] =
        if text.isEmpty then Chunk.empty[String]
        else
            buffer.append(text)
            val acc   = Chunk.newBuilder[String]
            var start = 0
            var i     = 0
            val len   = buffer.length
            while i < len do
                if buffer.charAt(i) == '\n' then
                    acc.addOne(buffer.substring(start, i))
                    start = i + 1
                end if
                i += 1
            end while
            if start > 0 then buffer.delete(0, start)
            acc.result()
        end if
    end feed

    /** Flush the buffered residual (a partial trailing line). Returns `Absent` if nothing is buffered. */
    def flush: Maybe[String] =
        if buffer.isEmpty then Absent
        else
            val s = buffer.toString
            buffer.setLength(0)
            Present(s)
end LineAssembler
