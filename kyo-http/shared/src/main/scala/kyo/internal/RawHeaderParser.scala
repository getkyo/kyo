package kyo.internal

import kyo.*
import scala.annotation.tailrec

/** Parses raw HTTP header lines ("Name: Value\r\n...") into HttpHeaders.
  *
  * Skips HTTP status lines (starting with "HTTP/") and lines without a colon separator.
  */
private[kyo] object RawHeaderParser:

    def parseHeaders(raw: String): HttpHeaders =
        val lines = raw.split("\r\n")
        @tailrec def loop(i: Int, headers: HttpHeaders): HttpHeaders =
            if i >= lines.length then headers
            else
                val line     = lines(i)
                val colonIdx = line.indexOf(':')
                if colonIdx > 0 then
                    val name  = line.substring(0, colonIdx).trim
                    val value = line.substring(colonIdx + 1).trim
                    loop(i + 1, headers.add(name, value))
                else
                    loop(i + 1, headers)
                end if
        loop(0, HttpHeaders.empty)
    end parseHeaders

end RawHeaderParser
