package kyo.internal

/** Parses raw HTTP header lines ("Name: Value\r\n...") into key-value pairs.
  *
  * Skips HTTP status lines (starting with "HTTP/") and lines without a colon separator.
  */
private[kyo] object RawHeaderParser:

    def parseHeaders(raw: String): Seq[(String, String)] =
        val result = Seq.newBuilder[(String, String)]
        val lines  = raw.split("\r\n")
        var i      = 0
        while i < lines.length do
            val line     = lines(i)
            val colonIdx = line.indexOf(':')
            if colonIdx > 0 then
                val name  = line.substring(0, colonIdx).trim
                val value = line.substring(colonIdx + 1).trim
                result += ((name, value))
            end if
            i += 1
        end while
        result.result()
    end parseHeaders

end RawHeaderParser
