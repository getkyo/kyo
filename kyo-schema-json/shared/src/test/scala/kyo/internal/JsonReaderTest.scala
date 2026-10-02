package kyo.internal

import kyo.*

class JsonReaderTest extends kyo.test.Test[Any]:

    case class JRPoint(x: Int, y: Int) derives Schema, CanEqual

    private def assertParseAt[A](input: String, position: Int)(using Schema[A], kyo.test.AssertScope) =
        Json.decode[A](input) match
            case Result.Failure(e: ParseException) => assert(e.position == position, s"$input: ${e.getMessage}")
            case other                             => fail(s"$input: expected a ParseException at $position, got $other")

    private def assertDecodes[A](input: String, expected: A)(using Schema[A], CanEqual[A, A], kyo.test.AssertScope) =
        val result = Json.decode[A](input)
        assert(result == Result.succeed(expected), s"$input: $result")

    "a number with a leading zero is a ParseException at the second digit" in {
        assertParseAt[Structure.Value]("""{"a":{"b":01}}""", 11)
        assertParseAt[Structure.Value]("-012", 2)
        assertParseAt[Structure.Value]("00.5", 1)
        assertParseAt[Int]("007", 1)
        assertParseAt[Long]("-01", 2)
        assertParseAt[Double]("01.5", 1)
        assertParseAt[Float]("01", 1)
        assertParseAt[Short]("01", 1)
        assertParseAt[JRPoint]("""{"x":1,"y":02}""", 12)
        assertDecodes[Structure.Value](
            "[0,-0,0.5,0e1,10]",
            Structure.Value.Sequence(Chunk(
                Structure.Value.Integer(0),
                Structure.Value.Integer(0),
                Structure.Value.Decimal(0.5),
                Structure.Value.Decimal(0.0),
                Structure.Value.Integer(10)
            ))
        )
        assertDecodes[Int]("0", 0)
        assertDecodes[Double]("-0.25", -0.25)
    }

    "a raw control character inside a string is a ParseException at that character" in {
        assertParseAt[Structure.Value]("{\"a\":{\"b\":\"x\ny\"}}", 12)
        assertParseAt[String]("\"x\ty\"", 2)
        assertParseAt[String]("\"\u0000\"", 1)
        assertParseAt[String]("\"a\\n\u001f\"", 4)
        assertParseAt[Map[String, Int]]("{\"a\rb\":1}", 3)
        assertParseAt[JRPoint]("{\"x\n\":1,\"y\":2}", 3)
        assertDecodes[String]("\"\u007f \\t é\"", "\u007f \t é")
    }

    "a malformed number is a ParseException, never a panic" in {
        assertParseAt[Structure.Value]("""{"a":{"b":-}}""", 11)
        assertParseAt[Structure.Value]("""{"a":{"b":1e}}""", 12)
        assertParseAt[Structure.Value]("""{"a":1-2}""", 6)
        assertParseAt[Structure.Value]("""[1e+]""", 4)
        assertParseAt[Structure.Value]("1.", 2)
        assertParseAt[Structure.Value]("1.e3", 2)
        assertParseAt[Structure.Value]("-.5", 1)
        assertParseAt[Structure.Value]("--1", 1)
        assertParseAt[Structure.Value]("+1", 0)
        assertParseAt[Structure.Value]("[1e1.5]", 4)
        assertParseAt[Double]("1e", 2)
        assertParseAt[Double]("-", 1)
        assertParseAt[Double]("+1", 0)
        assertParseAt[Double]("1.", 2)
        assertParseAt[Int]("1e+", 3)
        assertParseAt[Byte]("1-2", 1)
    }

    "a number beyond the reader's bound is a ParseException at its first character" in {
        assertParseAt[Structure.Value]("""{"a":{"b":1e99999999999}}""", 10)
        assertParseAt[Structure.Value](s"""{"a":{"b":${"9" * 1001}}}""", 10)
        assertParseAt[Structure.Value](s"""{"a":{"b":0.${"1" * 1000}}}""", 10)
        assertParseAt[Structure.Value]("1e1000000000", 0)
        assertParseAt[Structure.Value]("1e-1000000000", 0)
        assertParseAt[Double]("9" * 1001, 0)
        assertParseAt[BigDecimal]("\"" + "9" * 1001 + "\"", 1)
        assertParseAt[BigInt]("\"" + "9" * 1001 + "\"", 1)
        assertParseAt[BigDecimal]("\"1e99999999999\"", 1)
        assertDecodes[Structure.Value]("9" * 1000, Structure.Value.BigNum(BigDecimal("9" * 1000)))
        assertDecodes[Structure.Value]("1e999999999", Structure.Value.BigNum(BigDecimal("1e999999999")))
        assertDecodes[Structure.Value]("1e-000000000000000005", Structure.Value.Decimal(1e-5))
        assertDecodes[BigDecimal]("\"1e-999999999\"", BigDecimal("1e-999999999"))
    }

    "a raised maxNumberDigits accepts a number the default rejects" in {
        val long = "9" * 1001
        assertParseAt[Structure.Value](long, 0)
        assert(Json.decode[Structure.Value](long, maxNumberDigits = 1001) == Result.succeed(Structure.Value.BigNum(BigDecimal(long))))
        assert(Json.decode[BigInt]("\"" + long + "\"", maxNumberDigits = 1001) == Result.succeed(BigInt(long)))
    }

    "a number past a lowered maxNumberDigits is a ParseException at its first byte" in {
        def assertDigitsAt[A](input: String, position: Int)(using Schema[A], kyo.test.AssertScope) =
            Json.decode[A](input, maxNumberDigits = 5) match
                case Result.Failure(e: ParseException) => assert(e.position == position, s"$input: ${e.getMessage}")
                case other                             => fail(s"$input: expected a ParseException at $position, got $other")
        assertDigitsAt[Structure.Value]("""{"a":123456}""", 5)
        assertDigitsAt[Structure.Value]("1.23456", 0)
        assertDigitsAt[Int]("123456", 0)
        assertDigitsAt[Double]("-0.12345", 0)
        assertDigitsAt[JRPoint]("""{"x":1,"y":2,"z":123456}""", 17)
        assertDigitsAt[BigInt]("\"123456\"", 1)
        assert(Json.decode[Structure.Value]("1.2345", maxNumberDigits = 5) == Result.succeed(Structure.Value.Decimal(1.2345)))
        assert(Json.decode[Int]("12345", maxNumberDigits = 5) == Result.succeed(12345))
    }

    "a number past a lowered maxExponent is a ParseException at its first byte" in {
        def assertExponentAt[A](input: String, position: Int)(using Schema[A], kyo.test.AssertScope) =
            Json.decode[A](input, maxExponent = 9) match
                case Result.Failure(e: ParseException) => assert(e.position == position, s"$input: ${e.getMessage}")
                case other                             => fail(s"$input: expected a ParseException at $position, got $other")
        assertExponentAt[Structure.Value]("[1,2e10]", 3)
        assertExponentAt[Double]("1e-10", 0)
        assertExponentAt[JRPoint]("""{"x":1,"y":2,"z":1E+10}""", 17)
        assertExponentAt[BigDecimal]("\"1e10\"", 1)
        assert(Json.decode[Double]("1e9", maxExponent = 9) == Result.succeed(1e9))
        assert(Json.decode[BigDecimal]("\"1e-9\"", maxExponent = 9) == Result.succeed(BigDecimal("1e-9")))
    }

    "a number limit the reader cannot honor is a LimitExceededException before any byte is read" in {
        assert(
            Json.decode[Int]("1", maxExponent = 1000000000) ==
                Result.fail(LimitExceededException("Exponent limit", 1000000000, 999999999))
        )
        assert(
            Json.decode[Int]("1", maxNumberDigits = Int.MaxValue) ==
                Result.fail(LimitExceededException("Number digit limit", Int.MaxValue, Int.MaxValue - 999999999))
        )
        assert(Json.decode[Int]("1", maxNumberDigits = Int.MaxValue, maxExponent = 0) == Result.succeed(1))
    }

    "a lowered number limit does not outlive its decode" in {
        assert(Json.decode[Int]("12", maxNumberDigits = 1).isFailure)
        assert(Json.decode[Int]("12") == Result.succeed(12))
        assert(Json.decode[Double]("1e5", maxExponent = 1).isFailure)
        assert(Json.decode[Double]("1e5") == Result.succeed(1e5))
    }

    "values not separated by a comma are a ParseException" in {
        assertParseAt[Structure.Value]("[1 2]", 3)
        assertParseAt[Structure.Value]("""{"a":1 "b":2}""", 7)
        assertParseAt[Structure.Value]("""[[] []]""", 4)
        assertParseAt[Structure.Value]("""[{} {}]""", 4)
        assertParseAt[List[Int]]("[1 2]", 3)
        assertParseAt[JRPoint]("""{"x":1 "y":2}""", 7)
        assertParseAt[Structure.Value]("[,1]", 1)
        assertDecodes[List[List[Int]]]("[[],[1],[]]", List(Nil, List(1), Nil))
    }

    "an ignored field's value is held to the same grammar as a read one" in {
        assertParseAt[JRPoint]("""{"x":1,"y":2,"z":{"w":01}}""", 23)
        assertParseAt[JRPoint]("""{"x":1,"y":2,"z":[1 2]}""", 20)
        assertParseAt[JRPoint]("{\"x\":1,\"y\":2,\"z\":[\"a\nb\"]}", 20)
        assertParseAt[JRPoint]("""{"x":1,"y":2,"z":{"w":tru}}""", 22)
        assertParseAt[JRPoint]("""{"x":1,"y":2,"z":{1:2}}""", 18)
        assertParseAt[JRPoint]("""{"x":1,"y":2,"z":[{"a":[}]}]}""", 24)
        assertParseAt[JRPoint]("""{"x":1,"y":2,"z":""" + "[" * 100000 + "01" + "]" * 100000 + "}", 100018)
        assertParseAt[JRPoint]("""{"x":1,"y":2,"z":""" + "[" * 50000 + "1 2" + "]" * 50000 + "}", 50019)
        assertParseAt[JRPoint]("""{"x":1,"y":2,"z":""" + """{"a":""" * 50000 + "01" + "}" * 50000 + "}", 250018)
        assertDecodes[JRPoint]("""{"x":1,"y":2,"z":""" + """{"a":[""" * 100000 + "1" + "]}" * 100000 + "}", JRPoint(1, 2))
        assertDecodes[JRPoint]("""{"x":1,"z":{"w":[1,{"v":"}"}],"u":null},"y":2}""", JRPoint(1, 2))
    }

    "an escaped field name is matched by the text it decodes to" in {
        assertDecodes[JRPoint]("""{"x":1,"y":2}""", JRPoint(1, 2))
        assertDecodes[JRPoint]("""{"x":1,"a\"b":3,"y":2}""", JRPoint(1, 2))
        assertDecodes[JRPoint]("""{"x":1,"\\":3,"y":2}""", JRPoint(1, 2))
        assertParseAt[JRPoint]("""{"x":1,"a\qb":3,"y":2}""", 10)
    }

    "an escape outside RFC 8259's set is a ParseException at the escaped character" in {
        assertParseAt[String]("\"a\\qb\"", 3)
        assertParseAt[Structure.Value]("""["\x41"]""", 3)
        assertParseAt[JRPoint]("""{"x":1,"y":2,"z":"\'"}""", 19)
        assertDecodes[String]("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0041\"", "\"\\/\b\f\n\r\tA")
    }

    "an invalid unicode escape is a ParseException at its first hex digit" in {
        assertParseAt[Structure.Value]("""{"a":{"b":"\uZZZZ"}}""", 13)
    }

    "nesting deeper than the depth limit is a LimitExceededException, not a stack overflow" in {
        val deep = "[" * 1000000 + "]" * 1000000
        Json.decode[Structure.Value](s"""{"a":$deep}""") match
            case Result.Failure(e: LimitExceededException) => assert(e.limit == "Nesting depth")
            case other                                     => fail(s"expected a LimitExceededException, got $other")
    }

end JsonReaderTest
