package kyo.internal.slack

import kyo.*
import kyo.SlackInvalidRawBlockException.Problem
import scala.annotation.tailrec

/** The module's JSON reader for text that arrives raw: a `SlackBlock.Raw` block, and the free-form
  * sub-objects of an inbound frame (an `Unknown` payload, a view submission's `state`). It yields a
  * `Structure.Value`, which the request DTOs carry and kyo-schema writes as native JSON.
  *
  * It holds four properties that neither a caller's block nor Slack's frame may lose, each covered by
  * a test:
  *   - RFC 8259 strictness: a leading zero, a raw control character in a string, and trailing content
  *     are rejected;
  *   - a positioned `Problem` for every rejection, which `SlackInvalidRawBlockException` reports;
  *   - numbers bounded by `MaxNumberDigits` and `MaxExponent`;
  *   - a malformed number is a `ParseFailure`, never a panic, so a frame carrying one cannot end the
  *     receive loop.
  */
private[kyo] object RawJson:

    /** The same nesting bound kyo-schema's decoders apply, so a hostile inbound frame cannot exhaust
      * the stack of this recursive reader.
      */
    inline val MaxDepth = 512

    /** The digits a number may carry before its exponent. Re-emitting a `BigDecimal` costs time
      * quadratic in its digits: measured at 1000 digits under 1 ms on JVM, JS, Native and Wasm, at
      * 100000 digits 0.26 s (JVM) to 7.6 s (Native), and at 1000000 digits over 20 s on JVM.
      */
    inline val MaxNumberDigits = 1000

    /** The exponent magnitude a number may carry. `BigDecimal`'s scale is an `Int`, and the platforms
      * disagree on the edge: JS, Native and Wasm reject an exponent string past `Int.MaxValue`, and
      * the JVM rejects `1e-2147483648`. With at most `MaxNumberDigits` digits, a magnitude up to
      * 999999999 keeps the scale inside `Int` on all four, measured.
      */
    inline val MaxExponent = 999999999

    /** Where and why a text stopped being JSON. */
    final case class ParseFailure(position: Int, problem: Problem) derives CanEqual

    /** Parse a raw JSON string into a `Structure.Value`. */
    def parse(raw: String): Result[ParseFailure, Structure.Value] =
        Parser(raw).parseValue

    /** The value at `path` inside `value`, or `Absent` when a step names no field of an object. */
    @tailrec
    def at(value: Structure.Value, path: String*): Maybe[Structure.Value] =
        path match
            case Seq()        => Present(value)
            case head +: tail =>
                value match
                    case Structure.Value.Record(fields) =>
                        Maybe.fromOption(fields.find(_._1 == head).map(_._2)) match
                            case Present(child) => at(child, tail*)
                            case Absent         => Absent
                    case _ => Absent

    /** A recursive-descent RFC 8259 reader producing a `Structure.Value`; failure is a `ParseFailure`. */
    final private class Parser(input: String):
        // One cursor per `parse` call, never shared.
        private var pos = 0

        private type Parsed[A] = Result[ParseFailure, A]

        private val unit: Parsed[Unit] = Result.succeed(())

        def parseValue: Parsed[Structure.Value] =
            skipWs()
            value(0).flatMap { v =>
                skipWs()
                if pos != input.length then failed(Problem.TrailingContent) else Result.succeed(v)
            }
        end parseValue

        private def failed(problem: Problem): Parsed[Nothing] = Result.fail(ParseFailure(pos, problem))

        private def skipWs(): Unit =
            while pos < input.length &&
                (input(pos) match
                    case ' ' | '\t' | '\n' | '\r' => true
                    case _                        => false)
            do pos += 1

        private def value(depth: Int): Parsed[Structure.Value] =
            if depth >= MaxDepth then failed(Problem.TooDeep(MaxDepth))
            else if pos >= input.length then failed(Problem.UnexpectedEnd)
            else
                input(pos) match
                    case '{'                                     => obj(depth + 1)
                    case '['                                     => arr(depth + 1)
                    case '"'                                     => str().map(Structure.Value.Str(_))
                    case 't' | 'f'                               => bool()
                    case 'n'                                     => nullValue()
                    case c if c == '-' || (c >= '0' && c <= '9') => number()
                    case c                                       => failed(Problem.UnexpectedCharacter(c))
        end value

        private def obj(depth: Int): Parsed[Structure.Value] =
            val fields              = Chunk.newBuilder[(String, Structure.Value)]
            def field: Parsed[Unit] =
                skipWs()
                str().flatMap { name =>
                    skipWs()
                    expect(':').flatMap { _ =>
                        skipWs()
                        value(depth).map(v => discard(fields += (name -> v)))
                    }
                }
            end field
            @tailrec def rest(): Parsed[Unit] =
                skipWs()
                peekChar() match
                    case Result.Success(',') =>
                        pos += 1
                        field match
                            case Result.Success(_) => rest()
                            case other             => other
                    case Result.Success('}') =>
                        pos += 1
                        unit
                    case Result.Success(c) => failed(Problem.Expected(Chunk(',', '}'), c))
                    case other             => other.unit
                end match
            end rest
            expect('{').flatMap { _ =>
                skipWs()
                peekChar().flatMap {
                    case '}' =>
                        pos += 1
                        unit
                    case _ => field.flatMap(_ => rest())
                }
            }.map(_ => Structure.Value.Record(fields.result()))
        end obj

        private def arr(depth: Int): Parsed[Structure.Value] =
            val elems                 = Chunk.newBuilder[Structure.Value]
            def element: Parsed[Unit] =
                skipWs()
                value(depth).map(v => discard(elems += v))
            @tailrec def rest(): Parsed[Unit] =
                skipWs()
                peekChar() match
                    case Result.Success(',') =>
                        pos += 1
                        element match
                            case Result.Success(_) => rest()
                            case other             => other
                    case Result.Success(']') =>
                        pos += 1
                        unit
                    case Result.Success(c) => failed(Problem.Expected(Chunk(',', ']'), c))
                    case other             => other.unit
                end match
            end rest
            expect('[').flatMap { _ =>
                skipWs()
                peekChar().flatMap {
                    case ']' =>
                        pos += 1
                        unit
                    case _ => element.flatMap(_ => rest())
                }
            }.map(_ => Structure.Value.Sequence(elems.result()))
        end arr

        private def str(): Parsed[String] =
            val sb                            = new StringBuilder
            @tailrec def loop(): Parsed[Unit] =
                if pos >= input.length then failed(Problem.UnterminatedString)
                else
                    val c = input(pos)
                    pos += 1
                    c match
                        case '"'  => unit
                        case '\\' =>
                            if pos >= input.length then failed(Problem.UnterminatedString)
                            else
                                val e = input(pos)
                                pos += 1
                                e match
                                    case '"'  => sb += '"'; loop()
                                    case '\\' => sb += '\\'; loop()
                                    case '/'  => sb += '/'; loop()
                                    case 'b'  => sb += '\b'; loop()
                                    case 'f'  => sb += '\f'; loop()
                                    case 'n'  => sb += '\n'; loop()
                                    case 'r'  => sb += '\r'; loop()
                                    case 't'  => sb += '\t'; loop()
                                    case 'u'  =>
                                        if pos + 4 > input.length || !input.substring(pos, pos + 4).forall(isHexDigit) then
                                            failed(Problem.InvalidUnicodeEscape)
                                        else
                                            sb += Integer.parseInt(input.substring(pos, pos + 4), 16).toChar
                                            pos += 4
                                            loop()
                                    case other => failed(Problem.InvalidEscape(other))
                                end match
                        case other if other < ' ' =>
                            pos -= 1
                            failed(Problem.UnescapedControlCharacter(other))
                        case other =>
                            sb += other
                            loop()
                    end match
            end loop
            expect('"').flatMap(_ => loop()).map(_ => sb.toString)
        end str

        private def isHexDigit(c: Char): Boolean =
            (c >= '0' && c <= '9') ||
                (c >= 'a' && c <= 'f') ||
                (c >= 'A' && c <= 'F')

        private def digits(): Int =
            val start = pos
            while pos < input.length && input(pos) >= '0' && input(pos) <= '9' do pos += 1
            pos - start
        end digits

        private def number(): Parsed[Structure.Value] =
            val start = pos
            if pos < input.length && input(pos) == '-' then pos += 1
            val intStart  = pos
            val intDigits = digits()
            if intDigits == 0 then failed(Problem.InvalidNumber)
            else if intDigits > 1 && input(intStart) == '0' then
                pos = intStart + 1
                failed(Problem.InvalidNumber)
            else
                val fracDigits =
                    if pos < input.length && input(pos) == '.' then
                        pos += 1
                        Present(digits())
                    else Absent
                if fracDigits.contains(0) then failed(Problem.InvalidNumber)
                else
                    val mantissaEnd                   = pos
                    val exponent: Parsed[Maybe[Long]] =
                        if pos < input.length && (input(pos) == 'e' || input(pos) == 'E') then
                            pos += 1
                            val negative = pos < input.length && input(pos) == '-'
                            if pos < input.length && (input(pos) == '+' || input(pos) == '-') then pos += 1
                            val expStart = pos
                            if digits() == 0 then failed(Problem.InvalidNumber)
                            else
                                val significant = input.substring(expStart, pos).dropWhile(_ == '0')
                                if significant.length > 9 then outOfRange(start)
                                else
                                    val magnitude = if significant.isEmpty then 0L else significant.toLong
                                    Result.succeed(Present(if negative then -magnitude else magnitude))
                                end if
                            end if
                        else Result.succeed(Absent)
                    exponent.flatMap { exp =>
                        val frac = fracDigits.getOrElse(0)
                        if intDigits + frac > MaxNumberDigits || exp.exists(e => Math.abs(e) > MaxExponent) then outOfRange(start)
                        else
                            val mantissa = input.substring(start, mantissaEnd)
                            // A decimal is kept exact: a Double would round 0.1000000000000000000001 to 0.1 and turn
                            // 1e999 into Infinity, which the writer emits as a string, so the re-emitted JSON would
                            // no longer say what the caller or Slack wrote. The exponent is handed over without its
                            // leading zeros, which BigDecimal's parsers on some platforms count against their limit.
                            Result.succeed(exp match
                                case Present(e)         => Structure.Value.BigNum(BigDecimal(s"${mantissa}e$e"))
                                case Absent if frac > 0 => Structure.Value.BigNum(BigDecimal(mantissa))
                                case Absent             =>
                                    Maybe.fromOption(mantissa.toLongOption) match
                                        case Present(l) => Structure.Value.Integer(l)
                                        case Absent     => Structure.Value.BigNum(BigDecimal(mantissa)))
                        end if
                    }
                end if
            end if
        end number

        private def outOfRange(start: Int): Parsed[Nothing] =
            pos = start
            failed(Problem.NumberOutOfRange(MaxNumberDigits, MaxExponent))

        private def bool(): Parsed[Structure.Value] =
            if input.startsWith("true", pos) then
                pos += 4
                Result.succeed(Structure.Value.Bool(true))
            else if input.startsWith("false", pos) then
                pos += 5
                Result.succeed(Structure.Value.Bool(false))
            else failed(Problem.InvalidLiteral)

        private def nullValue(): Parsed[Structure.Value] =
            if input.startsWith("null", pos) then
                pos += 4
                Result.succeed(Structure.Value.Null)
            else failed(Problem.InvalidLiteral)

        private def peekChar(): Parsed[Char] =
            if pos >= input.length then failed(Problem.UnexpectedEnd) else Result.succeed(input(pos))

        private def expect(c: Char): Parsed[Unit] =
            if pos >= input.length then failed(Problem.UnexpectedEnd)
            else if input(pos) != c then failed(Problem.Expected(Chunk(c), input(pos)))
            else
                pos += 1
                unit
        end expect
    end Parser

end RawJson
