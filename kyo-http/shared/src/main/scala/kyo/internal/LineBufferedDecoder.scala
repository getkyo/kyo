package kyo.internal

import kyo.*
import scala.annotation.tailrec

/** Stateful decoder that buffers incoming bytes, converts to text, and extracts complete delimited sections.
  *
  * Used by both SseDecoder (delimiter = "\n\n") and NdjsonDecoder (delimiter = "\n").
  */
final private[kyo] class LineBufferedDecoder(delimiter: String, normalize: Boolean = false):
    private val utf8   = Utf8StreamDecoder()
    private var buffer = ""

    /** Decode bytes and return all complete sections found between delimiters. */
    def extract(bytes: Span[Byte]): Seq[String] =
        var text = utf8.decode(Chunk.from(bytes.toArrayUnsafe))
        if normalize then text = text.replace("\r\n", "\n").replace("\r", "\n")
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
