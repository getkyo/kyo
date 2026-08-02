package kyo.internal.mysql

import kyo.Chunk
import kyo.discard

/** The MySQL array wire: a `JSON` column holding a one-dimensional array, formatted and parsed here so the backend owns its own wire.
  *
  * Encoding matches the canonical compact JSON form (`[1,2,3]`, `["a","b"]`): no whitespace, standard string escapes (`\"`, `\\`, control
  * characters as their short escapes or `\u00XX`), non-ASCII text left raw in UTF-8. Parsing accepts standard JSON arrays, including
  * whitespace and `\uXXXX` escapes; a malformed document is reported through `fail`, which the reader binds to its own typed decode
  * exception and frame.
  */
private[kyo] object MysqlJsonArray:

    def encodeInts(values: Chunk[Int]): String =
        values.mkString("[", ",", "]")

    def encodeStrings(values: Chunk[String]): String =
        val sb = new java.lang.StringBuilder
        discard(sb.append('['))
        var i = 0
        while i < values.size do
            if i > 0 then discard(sb.append(','))
            quoteInto(sb, values(i))
            i += 1
        end while
        sb.append(']').toString
    end encodeStrings

    def decodeInts(text: String)(fail: String => Nothing): Chunk[Int] =
        elements(text)(fail).map { e =>
            try e.toInt
            catch case _: NumberFormatException => fail(s"expected an integer array element, got '${e.take(20)}'")
        }

    def decodeStrings(text: String)(fail: String => Nothing): Chunk[String] =
        elements(text)(fail).map(e => unquote(e)(fail))

    /** Splits a JSON array into its top-level element texts, string-aware and nesting-aware, each trimmed. */
    def elements(text: String)(fail: String => Nothing): Chunk[String] =
        val t = text.trim
        if t.length < 2 || t.charAt(0) != '[' || t.charAt(t.length - 1) != ']' then
            fail(s"expected a JSON array, got '${t.take(20)}'")
        val body = t.substring(1, t.length - 1).trim
        if body.isEmpty then Chunk.empty
        else
            val out      = Chunk.newBuilder[String]
            var depth    = 0
            var inString = false
            var escaped  = false
            var start    = 0
            var i        = 0
            while i < body.length do
                val c = body.charAt(i)
                if inString then
                    if escaped then escaped = false
                    else if c == '\\' then escaped = true
                    else if c == '"' then inString = false
                else
                    c match
                        case '"'       => inString = true
                        case '[' | '{' => depth += 1
                        case ']' | '}' => depth -= 1
                        case ',' if depth == 0 =>
                            out += body.substring(start, i).trim
                            start = i + 1
                        case _ => ()
                end if
                i += 1
            end while
            if inString || depth != 0 then fail("unterminated JSON array")
            out += body.substring(start).trim
            out.result()
        end if
    end elements

    /** Decodes one JSON string literal, including the `\uXXXX` escapes. */
    def unquote(s: String)(fail: String => Nothing): String =
        val t = s.trim
        if t.length < 2 || t.charAt(0) != '"' || t.charAt(t.length - 1) != '"' then
            fail(s"expected a JSON string element, got '${t.take(20)}'")
        val sb = new java.lang.StringBuilder(t.length - 2)
        var i  = 1
        val e  = t.length - 1
        while i < e do
            val c = t.charAt(i)
            if c == '\\' then
                if i + 1 >= e then fail("dangling escape in JSON string")
                t.charAt(i + 1) match
                    case '"'  => sb.append('"'); i += 2
                    case '\\' => sb.append('\\'); i += 2
                    case '/'  => sb.append('/'); i += 2
                    case 'b'  => sb.append('\b'); i += 2
                    case 'f'  => sb.append('\f'); i += 2
                    case 'n'  => sb.append('\n'); i += 2
                    case 'r'  => sb.append('\r'); i += 2
                    case 't'  => sb.append('\t'); i += 2
                    case 'u' =>
                        if i + 6 > e then fail("truncated unicode escape in JSON string")
                        val hex = t.substring(i + 2, i + 6)
                        val cp =
                            try Integer.parseInt(hex, 16)
                            catch case _: NumberFormatException => fail(s"invalid unicode escape '\\u$hex'")
                        sb.append(cp.toChar); i += 6
                    case other => fail(s"invalid escape '\\$other' in JSON string")
                end match
            else
                sb.append(c)
                i += 1
            end if
        end while
        sb.toString
    end unquote

    private def quoteInto(sb: java.lang.StringBuilder, s: String): Unit =
        sb.append('"')
        var i = 0
        while i < s.length do
            val c = s.charAt(i)
            (c: @annotation.switch) match
                case '"'  => discard(sb.append("\\\""))
                case '\\' => sb.append("\\\\")
                case '\b' => sb.append("\\b")
                case '\f' => sb.append("\\f")
                case '\n' => sb.append("\\n")
                case '\r' => sb.append("\\r")
                case '\t' => sb.append("\\t")
                case c =>
                    if c < 0x20 then sb.append(f"\\u${c.toInt}%04x")
                    else sb.append(c)
            end match
            i += 1
        end while
        sb.append('"')
        ()
    end quoteInto

end MysqlJsonArray
