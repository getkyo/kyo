package kyo.internal

import java.nio.charset.StandardCharsets.UTF_8
import kyo.*
import scala.annotation.tailrec

/** Percent-encoding of text as UTF-8 octets, in the two forms kyo-http writes and reads.
  *
  * `Component` is RFC 3986 section 2.1: every octet outside the unreserved set (`A-Z a-z 0-9 - . _ ~`) is escaped, and `+` is an ordinary
  * character. It is the form of a path segment, a query name or value, and a Unix socket authority. `Form` is
  * `application/x-www-form-urlencoded` (WHATWG URL, "urlencoded serializer"): a space is `+`, and only ASCII alphanumerics and `*-._` stay.
  * It belongs to form bodies alone; in a path, its `+` for a space reads back as a literal `+`.
  *
  * Decoding never fails: an escape that is not two hex digits is kept as written, since it reaches here only from text built directly,
  * after the parser has refused malformed input. The UTF-8 decode keeps a leading byte order mark, as WHATWG's percent-decoding ("UTF-8
  * decode without BOM") does; kyo-charset's decoder removes it, which would drop a `%EF%BB%BF` a caller encoded.
  */
private[kyo] object PercentEncoding:

    enum Mode derives CanEqual:
        case Component
        case Form
    end Mode

    def encode(text: String, mode: Mode): String =
        val bytes                         = text.getBytes(UTF_8)
        val sb                            = new java.lang.StringBuilder(bytes.length)
        @tailrec def loop(i: Int): String =
            if i >= bytes.length then sb.toString
            else
                val b = bytes(i) & 0xff
                if keeps(b, mode) then discard(sb.append(b.toChar))
                else if mode == Mode.Form && b == ' ' then discard(sb.append('+'))
                else discard(sb.append('%').append(HexDigits.charAt(b >> 4)).append(HexDigits.charAt(b & 0xf)))
                loop(i + 1)
        loop(0)
    end encode

    def decode(text: String, mode: Mode): String =
        if text.indexOf('%') < 0 && (mode == Mode.Component || text.indexOf('+') < 0) then text
        else
            val src                                = text.getBytes(UTF_8)
            val out                                = new Array[Byte](src.length)
            @tailrec def loop(i: Int, j: Int): Int =
                if i >= src.length then j
                else
                    val b = src(i) & 0xff
                    if b == '%' && i + 2 < src.length && hexValue(src(i + 1)) >= 0 && hexValue(src(i + 2)) >= 0 then
                        out(j) = ((hexValue(src(i + 1)) << 4) | hexValue(src(i + 2))).toByte
                        loop(i + 3, j + 1)
                    else
                        out(j) = if mode == Mode.Form && b == '+' then ' '.toByte else src(i)
                        loop(i + 1, j + 1)
                    end if
            new String(out, 0, loop(0, 0), UTF_8)
        end if
    end decode

    private val HexDigits = "0123456789ABCDEF"

    private def keeps(b: Int, mode: Mode): Boolean =
        (b >= 'A' && b <= 'Z') ||
            (b >= 'a' && b <= 'z') ||
            (b >= '0' && b <= '9') || b == '-' || b == '.' || b == '_' ||
            (if mode == Mode.Component then b == '~' else b == '*')

    private def hexValue(b: Byte): Int =
        val c = b & 0xff
        if c >= '0' && c <= '9' then c - '0'
        else if c >= 'a' && c <= 'f' then c - 'a' + 10
        else if c >= 'A' && c <= 'F' then c - 'A' + 10
        else -1
        end if
    end hexValue

end PercentEncoding
