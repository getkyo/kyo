package kyo.internal

import kyo.*

/** Parses raw HTTP header lines ("Name: Value\r\n...") into HttpHeaders.
  *
  * Skips HTTP status lines (starting with "HTTP/") and lines without a colon separator.
  */
private[kyo] object RawHeaderParser:

    def parseHeaders(raw: String): HttpHeaders =
        var headers = HttpHeaders.empty
        val lines   = raw.split("\r\n")
        var i       = 0
        while i < lines.length do
            val line     = lines(i)
            val colonIdx = line.indexOf(':')
            if colonIdx > 0 then
                val name  = line.substring(0, colonIdx).trim
                val value = line.substring(colonIdx + 1).trim
                headers = headers.add(name, value)
            end if
            i += 1
        end while
        headers
    end parseHeaders

end RawHeaderParser
