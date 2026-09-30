package kyo.internal.mime

import kyo.*

/** The lexical rules the MIME header grammars share (RFC 2045 section 5.1, RFC 5322 section 3.2.2, RFC 2231 section 7): tokens, quoted
  * strings with quoted pairs, comments that nest, and the attribute characters an RFC 2231 encoded value carries unescaped.
  */
private[kyo] object Grammar:

    // RFC 2045 section 5.1: tspecials.
    private val TokenSpecials = "()<>@,;:\\\"/[]?="

    /** A token character: any US-ASCII character other than SP, controls and tspecials. */
    def isTokenChar(c: Char): Boolean = c > ' ' && c < '\u007f' && TokenSpecials.indexOf(c.toInt) < 0

    def isToken(text: String): Boolean = text.nonEmpty && text.forall(isTokenChar)

    /** The end of the token starting at `from`, which is `from` itself when no token starts there. */
    def tokenEnd(text: String, from: Int): Int =
        var at = from
        while at < text.length && isTokenChar(text.charAt(at)) do at += 1
        at
    end tokenEnd

    /** RFC 2231 section 7: an attribute character is a token character other than `*`, `'` and `%`. */
    def isAttributeChar(b: Int): Boolean = b < 0x80 && isTokenChar(b.toChar) && b != '*' && b != '\'' && b != '%'

    def isWhiteSpace(c: Char): Boolean = c == ' ' || c == '\t'

    def isHex(c: Char): Boolean =
        (c >= '0' && c <= '9') ||
            (c >= 'a' && c <= 'f') ||
            (c >= 'A' && c <= 'F')

    def hexValue(c: Char): Int = if c <= '9' then c - '0' else (c | 0x20) - 'a' + 10

    val HexDigits = "0123456789ABCDEF"

    /** A quoted string opening at `open`: its content with quoted pairs resolved, and where the text after it starts. An unclosed quoted
      * string runs to the end of the text.
      */
    def quotedString(text: String, open: Int): (String, Int) =
        @scala.annotation.tailrec
        def count(at: Int, size: Int): (Int, Int) =
            if at >= text.length || text.charAt(at) == '"' then (at, size)
            else if text.charAt(at) == '\\' && at + 1 < text.length then count(at + 2, size + 1)
            else count(at + 1, size + 1)
        val (close, size) = count(open + 1, 0)
        val out           = new java.lang.StringBuilder(size)
        @scala.annotation.tailrec
        def fill(at: Int): Unit =
            if at < close then
                if text.charAt(at) == '\\' && at + 1 < text.length then
                    discard(out.append(text.charAt(at + 1)))
                    fill(at + 2)
                else
                    discard(out.append(text.charAt(at)))
                    fill(at + 1)
        fill(open + 1)
        (out.toString, if close < text.length then close + 1 else text.length)
    end quotedString

    /** CFWS from `from`, where a comment left unclosed runs to the end of the text (RFC 5322 section 3.2.2: comments nest). */
    def skipCfws(text: String, from: Int): Int =
        val end = cfwsEnd(text, from, text.length)
        if end < text.length && text.charAt(end) == '(' then text.length else end

    /** White space and closed comments from `from`, before `limit`: where they stop, which is an unclosed `(` when one stops them. */
    def cfwsEnd(text: String, from: Int, limit: Int): Int =
        @scala.annotation.tailrec
        def loop(at: Int): Int =
            if at >= limit then at
            else
                val c = text.charAt(at)
                if isWhiteSpace(c) then loop(at + 1)
                else if c == '(' then
                    val close = commentEnd(text, at, limit)
                    if close < 0 then at else loop(close)
                else at
                end if
        loop(from)
    end cfwsEnd

    /** The offset after the `)` closing the comment opening at `open`, nesting counted and quoted pairs skipped, or -1 when it is not
      * closed before `limit`.
      */
    def commentEnd(text: String, open: Int, limit: Int): Int =
        @scala.annotation.tailrec
        def loop(at: Int, depth: Int): Int =
            if at >= limit then -1
            else
                text.charAt(at) match
                    case '\\' => loop(at + 2, depth)
                    case '('  => loop(at + 1, depth + 1)
                    case ')'  => if depth == 1 then at + 1 else loop(at + 1, depth - 1)
                    case _    => loop(at + 1, depth)
        loop(open, 0)
    end commentEnd

    def semicolonOrEnd(text: String, from: Int): Int =
        val at = text.indexOf(';', from)
        if at < 0 then text.length else at

    def withoutTrailingWhiteSpace(text: String): String = text.substring(0, withoutTrailingWhiteSpace(text, 0, text.length))

    /** The end of `[from, until)` with trailing white space removed. */
    def withoutTrailingWhiteSpace(text: String, from: Int, until: Int): Int =
        var end = until
        while end > from && isWhiteSpace(text.charAt(end - 1)) do end -= 1
        end
    end withoutTrailingWhiteSpace

    def quoted(text: String): String = "\"" + quotedContent(text) + "\""

    def quotedLength(text: String): Int = text.length + 2 + text.count(c => c == '"' || c == '\\')

    def quotedContent(text: String): String =
        val out = new java.lang.StringBuilder(quotedLength(text) - 2)
        text.foreach { c =>
            if c == '"' || c == '\\' then discard(out.append('\\'))
            discard(out.append(c))
        }
        out.toString
    end quotedContent

    /** `text` as an exception message shows it: controls and non-ASCII escaped, cut at 200 characters, never a header body. */
    def printable(text: String): String =
        val cut     = if text.length > 200 then text.substring(0, 200) else text
        val escaped = cut.flatMap {
            case '\r'                    => "\\r"
            case '\n'                    => "\\n"
            case '\t'                    => "\\t"
            case '\\'                    => "\\\\"
            case c if c < ' ' || c > '~' => f"\\u${c.toInt}%04x"
            case c                       => c.toString
        }
        if text.length > 200 then escaped + "..." else escaped
    end printable

end Grammar
