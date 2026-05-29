package kyo.internal

import kyo.*

/** JSON-RPC envelope text-encoding bridge: converts between raw JSON strings and [[Structure.Value]] trees.
  *
  * Used by [[WireTransportAdapter]] to convert incoming wire bytes into the structural
  * representation expected by [[JsonRpcCodec.decode]], and to convert outgoing
  * [[Structure.Value]] values back to standard JSON-RPC wire bytes.
  * This is distinct from `Json.decode[Structure.Value]` and `Json.encode[Structure.Value]`
  * which use the kyo-schema encoding format (discriminated union wrappers like `{"Record":{...}}`)
  * rather than plain JSON.
  *
  * JSON mapping:
  *   - `{}` / `{"k":"v"}` <-> `Structure.Value.Record`
  *   - `[]` / `[1,2]`     <-> `Structure.Value.Sequence`
  *   - `"hello"`           <-> `Structure.Value.Str`
  *   - `42`                <-> `Structure.Value.Integer`
  *   - `3.14`              <-> `Structure.Value.Decimal`
  *   - `true` / `false`    <-> `Structure.Value.Bool`
  *   - `null`              <-> `Structure.Value.Null`
  */
private[kyo] object RawJsonParser:

    def parse(jsonStr: String)(using Frame): Result[ParseException, Structure.Value] =
        // Result.catching converts JSON parse errors to typed ParseException
        Result.catching[ParseException] {
            val p = new Parser(jsonStr)
            val v = p.readValue()
            p.skipWs()
            if p.pos < p.input.length then
                p.parseError(s"trailing content at position ${p.pos}")
            v
        }

    def encode(sv: Structure.Value): String =
        val sb = new java.lang.StringBuilder
        writeValue(sv, sb)
        sb.toString
    end encode

    private def writeValue(sv: Structure.Value, sb: java.lang.StringBuilder): Unit =
        sv match
            case Structure.Value.Record(fields) =>
                discard(sb.append('{'))
                var first = true
                fields.foreach { case (k, v) =>
                    if !first then discard(sb.append(','))
                    writeJsonString(k, sb)
                    discard(sb.append(':'))
                    writeValue(v, sb)
                    first = false
                }
                discard(sb.append('}'))
            case Structure.Value.Sequence(elems) =>
                discard(sb.append('['))
                var first = true
                elems.foreach { e =>
                    if !first then discard(sb.append(','))
                    writeValue(e, sb)
                    first = false
                }
                discard(sb.append(']'))
            case Structure.Value.MapEntries(entries) =>
                discard(sb.append('{'))
                var first = true
                entries.foreach { case (k, v) =>
                    if !first then discard(sb.append(','))
                    discard(sb.append('"'))
                    discard(sb.append(k.toString))
                    discard(sb.append('"'))
                    discard(sb.append(':'))
                    writeValue(v, sb)
                    first = false
                }
                discard(sb.append('}'))
            case Structure.Value.VariantCase(name, value) =>
                discard(sb.append('{'))
                writeJsonString(name, sb)
                discard(sb.append(':'))
                writeValue(value, sb)
                discard(sb.append('}'))
            case Structure.Value.Str(s) =>
                writeJsonString(s, sb)
            case Structure.Value.Bool(b) =>
                discard(sb.append(if b then "true" else "false"))
            case Structure.Value.Integer(n) =>
                discard(sb.append(n))
            case Structure.Value.Decimal(d) =>
                discard(sb.append(d))
            case Structure.Value.BigNum(bd) =>
                discard(sb.append(bd.toString))
            case Structure.Value.Null =>
                discard(sb.append("null"))

    private def writeJsonString(s: String, sb: java.lang.StringBuilder): Unit =
        discard(sb.append('"'))
        var i = 0
        while i < s.length do
            s.charAt(i) match
                case '"'  => discard(sb.append("\\\""))
                case '\\' => discard(sb.append("\\\\"))
                case '\n' => discard(sb.append("\\n"))
                case '\r' => discard(sb.append("\\r"))
                case '\t' => discard(sb.append("\\t"))
                case c if c < 0x20 =>
                    discard(sb.append("\\u"))
                    discard(sb.append(Integer.toHexString(0x10000 | c).substring(1)))
                case c => discard(sb.append(c))
            end match
            i += 1
        end while
        discard(sb.append('"'))
    end writeJsonString

    final private class Parser(val input: String)(using frame: Frame):
        var pos: Int = 0

        def parseError(detail: String): Nothing =
            throw ParseException(Json(), input, s"Structure.Value ($detail)")

        def skipWs(): Unit =
            while pos < input.length && (input.charAt(pos) == ' ' || input.charAt(pos) == '\t' ||
                    input.charAt(pos) == '\n' || input.charAt(pos) == '\r')
            do
                pos += 1

        def readValue(): Structure.Value =
            skipWs()
            if pos >= input.length then parseError("unexpected end of input")
            input.charAt(pos) match
                case '{' => readObject()
                case '[' => readArray()
                case '"' => Structure.Value.Str(readString())
                case 't' => readLiteral("true"); Structure.Value.Bool(true)
                case 'f' => readLiteral("false"); Structure.Value.Bool(false)
                case 'n' => readLiteral("null"); Structure.Value.Null
                case _   => readNumber()
            end match
        end readValue

        def readObject(): Structure.Value =
            expect('{')
            skipWs()
            val fields = scala.collection.mutable.ArrayBuffer.empty[(String, Structure.Value)]
            if pos < input.length && input.charAt(pos) != '}' then
                var continue = true
                while continue do
                    skipWs()
                    val key = readString()
                    skipWs()
                    expect(':')
                    val value = readValue()
                    fields += (key -> value)
                    skipWs()
                    if pos < input.length && input.charAt(pos) == ',' then
                        pos += 1
                    else
                        continue = false
                    end if
                end while
            end if
            skipWs()
            expect('}')
            Structure.Value.Record(Chunk.from(fields.toSeq))
        end readObject

        def readArray(): Structure.Value =
            expect('[')
            skipWs()
            val elems = scala.collection.mutable.ArrayBuffer.empty[Structure.Value]
            if pos < input.length && input.charAt(pos) != ']' then
                var continue = true
                while continue do
                    elems += readValue()
                    skipWs()
                    if pos < input.length && input.charAt(pos) == ',' then
                        pos += 1
                    else
                        continue = false
                    end if
                end while
            end if
            skipWs()
            expect(']')
            Structure.Value.Sequence(Chunk.from(elems.toSeq))
        end readArray

        def readString(): String =
            expect('"')
            val sb = new java.lang.StringBuilder
            while pos < input.length && input.charAt(pos) != '"' do
                if input.charAt(pos) == '\\' then
                    pos += 1
                    if pos >= input.length then parseError("unterminated escape")
                    input.charAt(pos) match
                        case '"'  => discard(sb.append('"'))
                        case '\\' => discard(sb.append('\\'))
                        case '/'  => discard(sb.append('/'))
                        case 'b'  => discard(sb.append('\b'))
                        case 'f'  => discard(sb.append('\f'))
                        case 'n'  => discard(sb.append('\n'))
                        case 'r'  => discard(sb.append('\r'))
                        case 't'  => discard(sb.append('\t'))
                        case 'u' =>
                            if pos + 4 >= input.length then parseError("incomplete unicode escape")
                            val hex = input.substring(pos + 1, pos + 5)
                            discard(sb.append(Integer.parseInt(hex, 16).toChar))
                            pos += 4
                        case c => discard(sb.append(c))
                    end match
                    pos += 1
                else
                    discard(sb.append(input.charAt(pos)))
                    pos += 1
            end while
            expect('"')
            sb.toString
        end readString

        def readNumber(): Structure.Value =
            val start = pos
            if pos < input.length && input.charAt(pos) == '-' then pos += 1
            while pos < input.length && input.charAt(pos) >= '0' && input.charAt(pos) <= '9' do pos += 1
            val isFloat =
                pos < input.length && (input.charAt(pos) == '.' || input.charAt(pos) == 'e' || input.charAt(pos) == 'E')
            if isFloat then
                if pos < input.length && input.charAt(pos) == '.' then
                    pos += 1
                    while pos < input.length && input.charAt(pos) >= '0' && input.charAt(pos) <= '9' do pos += 1
                if pos < input.length && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E') then
                    pos += 1
                    if pos < input.length && (input.charAt(pos) == '+' || input.charAt(pos) == '-') then pos += 1
                    while pos < input.length && input.charAt(pos) >= '0' && input.charAt(pos) <= '9' do pos += 1
                end if
                val numStr = input.substring(start, pos)
                Structure.Value.Decimal(numStr.toDouble)
            else
                val numStr = input.substring(start, pos)
                if numStr.isEmpty || numStr == "-" then
                    parseError(s"invalid number at position $start")
                Structure.Value.Integer(numStr.toLong)
            end if
        end readNumber

        def readLiteral(expected: String): Unit =
            if !input.startsWith(expected, pos) then
                parseError(s"expected '$expected' at position $pos")
            pos += expected.length
        end readLiteral

        def expect(c: Char): Unit =
            if pos >= input.length || input.charAt(pos) != c then
                parseError(s"expected '$c' at position $pos but got '${if pos < input.length then input.charAt(pos).toString else "EOF"}'")
            pos += 1
        end expect
    end Parser

end RawJsonParser
