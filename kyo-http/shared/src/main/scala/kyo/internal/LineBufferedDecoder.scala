package kyo.internal

import kyo.*
import scala.annotation.tailrec

/** Stateful decoder that buffers incoming bytes, converts to text, and extracts complete delimited sections.
  *
  * Used by both SseDecoder (delimiter = "\n\n") and NdjsonDecoder (delimiter = "\n").
  */
final private[kyo] class LineBufferedDecoder private (delimiter: String, normalize: Boolean, utf8: Utf8StreamDecoder):
    private var buffer = ""

    /** Decode bytes and return all complete sections found between delimiters. */
    def extract(bytes: Span[Byte])(using AllowUnsafe): Seq[String] =
        val raw  = utf8.decode(Chunk.from(bytes.toArrayUnsafe))
        val text = if normalize then raw.replace("\r\n", "\n").replace("\r", "\n") else raw
        buffer += text
        val results = Seq.newBuilder[String]
        @tailrec def loop(): Unit =
            val idx = buffer.indexOf(delimiter)
            if idx >= 0 then
                results += buffer.substring(0, idx)
                buffer = buffer.substring(idx + delimiter.length)
                loop()
            end if
        end loop
        loop()
        results.result()
    end extract

end LineBufferedDecoder

private[kyo] object LineBufferedDecoder:
    def init(delimiter: String, normalize: Boolean = false)(using AllowUnsafe): LineBufferedDecoder =
        new LineBufferedDecoder(delimiter, normalize, Utf8StreamDecoder.init())
end LineBufferedDecoder
