package kyo.internal

import kyo.*
import scala.annotation.tailrec

/** Splices a raw Block Kit JSON string onto the Web API wire as a NATIVE JSON value
  * (a `[...]` array or `{...}` object), not a quoted string.
  *
  * The public `SlackMessage.blocksJson` / `SlackView.blocksJson` / `SlackView.titleJson`
  * carry Block Kit as raw JSON text (Block Kit is not typed). The Slack Web API expects
  * the request body's `blocks` key to be a real JSON array, so the raw text must be
  * parsed and re-emitted structurally rather than embedded as a string scalar.
  *
  * `Structure.Value`'s derived enum Schema serializes to a tagged shape
  * (`{"Sequence":{"elements":[...]}}`), which is NOT Slack's wire format, so this carrier
  * parses the raw JSON into a `Structure.Value` AST and provides a Schema that writes
  * that AST natively via the Writer protocol (object/array/scalar calls). A malformed raw
  * string surfaces as a typed `SlackException.SlackDecodeException` at parse time, never
  * an invalid body on the wire.
  */
final private[kyo] case class SlackRawJson(ast: Structure.Value)

private[kyo] object SlackRawJson:

    /** A Schema that writes the parsed AST as native JSON. The read side returns an empty
      * AST: this carrier is outbound-only (request bodies), never decoded from a response.
      */
    given Schema[SlackRawJson] = Schema.init[SlackRawJson](
        writeFn = (v, w) => Schema.writeStructureValue(w, v.ast),
        readFn = _ => SlackRawJson(Structure.Value.Null)
    )

    /** Parse a raw JSON string into a native `Structure.Value`, surfacing a malformed
      * string as a typed `SlackDecodeException`.
      */
    def parse(raw: String, label: String)(using Frame): SlackRawJson < Abort[SlackException] =
        Parser(raw).parseValue match
            case Result.Success(value) => SlackRawJson(value)
            case Result.Failure(msg)   => Abort.fail(new SlackException.SlackDecodeException(s"$label JSON did not parse: $msg"))

    /** Navigate a raw JSON frame to a nested object at `path` and re-emit it as a native
      * JSON string (the same shape Slack sent), or `default` when the path is absent or the
      * frame does not parse. Used to recover `payload.view.state` (a free-form object that a
      * typed Schema cannot decode) for the public `ViewSubmission.stateJson`.
      */
    def nestedJson(frame: String, path: Seq[String], default: String)(using Frame): String =
        Parser(frame).parseValue match
            case Result.Success(root) =>
                navigate(root, path) match
                    case Present(sub) => Json.encode(SlackRawJson(sub))
                    case Absent       => default
            case Result.Failure(_) => default

    @tailrec
    private def navigate(value: Structure.Value, path: Seq[String]): Maybe[Structure.Value] =
        path match
            case Seq() => Present(value)
            case head +: tail =>
                value match
                    case Structure.Value.Record(fields) =>
                        Maybe.fromOption(fields.find(_._1 == head).map(_._2)) match
                            case Present(child) => navigate(child, tail)
                            case Absent         => Absent
                    case _ => Absent

    /** A minimal recursive-descent JSON parser producing a native `Structure.Value`
      * (objects -> Record, arrays -> Sequence, scalars -> Str/Integer/Decimal/Bool/Null).
      * Pure over the input string; failure is a `Result.Failure` carrying the reason.
      */
    final private class Parser(input: String):
        private var pos = 0

        def parseValue: Result[String, Structure.Value] =
            try
                skipWs()
                val v = value()
                skipWs()
                if pos != input.length then Result.fail(s"trailing content at position $pos")
                else Result.succeed(v)
            catch case e: ParseError => Result.fail(e.getMessage)

        final private class ParseError(msg: String) extends RuntimeException(msg)

        private def fail(msg: String): Nothing = throw new ParseError(s"$msg at position $pos")

        private def skipWs(): Unit =
            while pos < input.length && (input(pos) match
                    case ' ' | '\t' | '\n' | '\r' => true
                    case _                        => false)
            do pos += 1

        private def value(): Structure.Value =
            if pos >= input.length then fail("unexpected end of input")
            input(pos) match
                case '{'                                     => obj()
                case '['                                     => arr()
                case '"'                                     => Structure.Value.Str(str())
                case 't' | 'f'                               => bool()
                case 'n'                                     => nullValue()
                case c if c == '-' || (c >= '0' && c <= '9') => number()
                case c                                       => fail(s"unexpected character '$c'")
            end match
        end value

        private def obj(): Structure.Value =
            expect('{')
            skipWs()
            val fields = Chunk.newBuilder[(String, Structure.Value)]
            if peek() == '}' then
                pos += 1
                Structure.Value.Record(fields.result())
            else
                @tailrec def loop(): Unit =
                    skipWs()
                    val name = str()
                    skipWs()
                    expect(':')
                    skipWs()
                    fields += (name -> value())
                    skipWs()
                    peek() match
                        case ',' => pos += 1; loop()
                        case '}' => pos += 1
                        case c   => fail(s"expected ',' or '}' but found '$c'")
                    end match
                end loop
                loop()
                Structure.Value.Record(fields.result())
            end if
        end obj

        private def arr(): Structure.Value =
            expect('[')
            skipWs()
            val elems = Chunk.newBuilder[Structure.Value]
            if peek() == ']' then
                pos += 1
                Structure.Value.Sequence(elems.result())
            else
                @tailrec def loop(): Unit =
                    skipWs()
                    elems += value()
                    skipWs()
                    peek() match
                        case ',' => pos += 1; loop()
                        case ']' => pos += 1
                        case c   => fail(s"expected ',' or ']' but found '$c'")
                    end match
                end loop
                loop()
                Structure.Value.Sequence(elems.result())
            end if
        end arr

        private def str(): String =
            expect('"')
            val sb = new StringBuilder
            @tailrec def loop(): Unit =
                if pos >= input.length then fail("unterminated string")
                val c = input(pos)
                pos += 1
                c match
                    case '"' => ()
                    case '\\' =>
                        if pos >= input.length then fail("unterminated escape")
                        val e = input(pos); pos += 1
                        e match
                            case '"'  => sb += '"'; loop()
                            case '\\' => sb += '\\'; loop()
                            case '/'  => sb += '/'; loop()
                            case 'b'  => sb += '\b'; loop()
                            case 'f'  => sb += '\f'; loop()
                            case 'n'  => sb += '\n'; loop()
                            case 'r'  => sb += '\r'; loop()
                            case 't'  => sb += '\t'; loop()
                            case 'u' =>
                                if pos + 4 > input.length then fail("incomplete unicode escape")
                                val hex = input.substring(pos, pos + 4)
                                pos += 4
                                sb += Integer.parseInt(hex, 16).toChar
                                loop()
                            case other => fail(s"invalid escape '\\$other'")
                        end match
                    case other => sb += other; loop()
                end match
            end loop
            loop()
            sb.toString
        end str

        private def number(): Structure.Value =
            val start = pos
            if peek() == '-' then pos += 1
            while pos < input.length && input(pos) >= '0' && input(pos) <= '9' do pos += 1
            var isDecimal = false
            if pos < input.length && input(pos) == '.' then
                isDecimal = true
                pos += 1
                while pos < input.length && input(pos) >= '0' && input(pos) <= '9' do pos += 1
            end if
            if pos < input.length && (input(pos) == 'e' || input(pos) == 'E') then
                isDecimal = true
                pos += 1
                if pos < input.length && (input(pos) == '+' || input(pos) == '-') then pos += 1
                while pos < input.length && input(pos) >= '0' && input(pos) <= '9' do pos += 1
            end if
            val text = input.substring(start, pos)
            if isDecimal then Structure.Value.Decimal(text.toDouble)
            else
                text.toLongOption match
                    case Some(l) => Structure.Value.Integer(l)
                    case None    => Structure.Value.BigNum(BigDecimal(text))
            end if
        end number

        private def bool(): Structure.Value =
            if input.startsWith("true", pos) then
                pos += 4
                Structure.Value.Bool(true)
            else if input.startsWith("false", pos) then
                pos += 5
                Structure.Value.Bool(false)
            else fail("invalid literal")

        private def nullValue(): Structure.Value =
            if input.startsWith("null", pos) then
                pos += 4
                Structure.Value.Null
            else fail("invalid literal")

        private def peek(): Char =
            if pos >= input.length then fail("unexpected end of input")
            input(pos)

        private def expect(c: Char): Unit =
            if pos >= input.length || input(pos) != c then fail(s"expected '$c'")
            pos += 1
    end Parser

end SlackRawJson
