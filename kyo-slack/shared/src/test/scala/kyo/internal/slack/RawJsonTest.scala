package kyo.internal.slack

import kyo.*
import kyo.SlackInvalidRawBlockException.Problem

class RawJsonTest extends kyo.test.Test[Any]:

    "a unicode escape with non-hex digits is a positioned failure" in {
        assert(RawJson.parse("""{"a":{"b":"\uZZZZ"}}""") == Result.fail(RawJson.ParseFailure(13, Problem.InvalidUnicodeEscape)))
    }

    "a number with no digits is a positioned failure" in {
        assert(RawJson.parse("""{"a":{"b":-}}""") == Result.fail(RawJson.ParseFailure(11, Problem.InvalidNumber)))
        assert(RawJson.parse("""{"a":{"b":1e}}""") == Result.fail(RawJson.ParseFailure(12, Problem.InvalidNumber)))
    }

    "nesting deeper than the parser's bound is a failure, not a stack overflow" in {
        val deep = "[" * 1000000 + "]" * 1000000
        assert(RawJson.parse(s"""{"a":$deep}""") == Result.fail(RawJson.ParseFailure(516, Problem.TooDeep(RawJson.MaxDepth))))
    }

    "text that is not RFC 8259 JSON is a failure" in {
        assert(RawJson.parse("""{"a":{"b":01}}""") == Result.fail(RawJson.ParseFailure(11, Problem.InvalidNumber)))
        assert(RawJson.parse("{\"a\":{\"b\":\"x\ny\"}}") == Result.fail(RawJson.ParseFailure(12, Problem.UnescapedControlCharacter('\n'))))
    }

    "a number beyond the reader's bound is a failure" in {
        val bound = Problem.NumberOutOfRange(RawJson.MaxNumberDigits, RawJson.MaxExponent)
        assert(RawJson.parse("""{"a":{"b":1e99999999999}}""") == Result.fail(RawJson.ParseFailure(10, bound)))
        assert(RawJson.parse(s"""{"a":{"b":${"9" * 1001}}}""") == Result.fail(RawJson.ParseFailure(10, bound)))
    }

    "at is Absent when the path is absent, and the value there otherwise" in {
        val tree = RawJson.parse("""{"a":{"b":[1,"x",true,null]}}""").toMaybe
        assert(tree.flatMap(RawJson.at(_, "c")) == Absent)
        assert(tree.flatMap(RawJson.at(_, "a", "b", "c")) == Absent)
        assert(tree.flatMap(RawJson.at(_, "a")).map(Json.encode(_)) == Present("""{"b":[1,"x",true,null]}"""))
    }

    /** Every number as a `BigDecimal`: kyo-schema's decoder holds an exactly representable decimal as a `Decimal`
      * and the parser holds every decimal as a `BigNum`, both exact, so the trees are compared by value.
      */
    private def byValue(v: Structure.Value): Structure.Value =
        v match
            case Structure.Value.Record(fields) => Structure.Value.Record(fields.map((k, x) => k -> byValue(x)))
            case Structure.Value.Sequence(xs)   => Structure.Value.Sequence(xs.map(byValue))
            case Structure.Value.Integer(n)     => Structure.Value.BigNum(BigDecimal(n))
            case Structure.Value.Decimal(d)     => Structure.Value.BigNum(BigDecimal(d.toString))
            case other                          => other

    "on valid JSON the parser yields the values kyo-schema's Structure.Value decoder yields" in {
        val frames = Chunk(
            """{"type":"events_api","envelope_id":"E1","payload":{"event":{"type":"message","text":"hi é\n","ts":"1.2"}}}""",
            """[1,-2,0.5,1e3,-1.5E-7,0.1000000000000000000001,1e999,9223372036854775808,true,false,null,"",{},[]]""",
            """{"a":{"b":[{"c":[]}]},"d":"\"quoted\" \\ \/"}"""
        )
        frames.foreach { frame =>
            val parsed  = RawJson.parse(frame).toMaybe.map(byValue)
            val decoded = Json.decode[Structure.Value](frame).toMaybe.map(byValue)
            assert(parsed.isDefined && parsed == decoded, s"$frame: $parsed vs $decoded")
        }
        succeed
    }

    "a malformed number is a positioned failure, never a panic" in {
        assert(RawJson.parse("""{"a":1-2}""") == Result.fail(RawJson.ParseFailure(6, Problem.Expected(Chunk(',', '}'), '-'))))
        assert(RawJson.parse("""[1e+]""") == Result.fail(RawJson.ParseFailure(4, Problem.InvalidNumber)))
    }

end RawJsonTest
