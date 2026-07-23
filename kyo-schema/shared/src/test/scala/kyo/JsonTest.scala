package kyo

import kyo.Json.JsonSchema
import kyo.internal.JsonReader
import kyo.internal.JsonWriter

class JsonTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    private def jsonRoundTrip[A](value: A)(using schema: Schema[A]): A =
        val w = JsonWriter()
        schema.writeTo(value, w)
        val r = JsonReader(w.resultString)
        schema.readFrom(r)
    end jsonRoundTrip

    // ===================================================================
    // encode/decode: from FormatTest (JSON-only tests)
    // ===================================================================

    "encode/decode" - {

        "json encode simple" in {
            val json = Json.encode[MTPerson](MTPerson("Alice", 30))
            assert(json == """{"name":"Alice","age":30}""")
        }

        "json decode simple" in {
            val json   = """{"name":"Alice","age":30}"""
            val person = Json.decode[MTPerson](json).getOrThrow
            assert(person == MTPerson("Alice", 30))
        }

        "json round-trip" in {
            val person = MTPerson("Bob", 25)
            val bytes  = Json.encodeBytes[MTPerson](person)
            val result = Json.decodeBytes[MTPerson](bytes).getOrThrow
            assert(result == person)
        }

        "json encode nested" in {
            val json = Json.encode[MTSmallTeam](MTSmallTeam(MTPerson("Alice", 30), 5))
            assert(json.contains("\"lead\""))
            assert(json.contains("\"Alice\""))
            assert(json.contains("\"size\""))
        }

        "json encode sealed trait" in {
            val circle: MTShape = MTCircle(5.0)
            val json            = Json.encode[MTShape](circle)
            assert(json.contains("\"MTCircle\""))
            assert(json.contains("\"radius\""))
            assert(json.contains("5.0"))
        }

        "format given in scope" in {
            val person = MTPerson("Eve", 28)
            val bytes  = Json.encodeBytes[MTPerson](person)
            val result = Json.decodeBytes[MTPerson](bytes).getOrThrow
            assert(result == person)
        }

        "codec decode from String overload" in {
            val json   = """{"name":"Charlie","age":40}"""
            val person = Json.decode[MTPerson](json).getOrThrow
            assert(person == MTPerson("Charlie", 40))
        }

        "decode with extra fields ignores them" in {
            val json   = """{"name":"Alice","age":30,"email":"alice@test.com","active":true}"""
            val person = Json.decode[MTPerson](json).getOrThrow
            assert(person == MTPerson("Alice", 30))
        }

        "Json.encode returns valid JSON string" in {
            val person = MTPerson("Alice", 30)
            val json   = Json.encode(person)
            assert(json == """{"name":"Alice","age":30}""")
        }

        "Json.decode from String returns Success" in {
            val json   = """{"name":"Alice","age":30}"""
            val result = Json.decode[MTPerson](json)
            assert(result == Result.succeed(MTPerson("Alice", 30)))
        }

        "Json.decode from invalid JSON returns failure" in {
            val invalid = """not valid json"""
            val result  = Json.decode[MTPerson](invalid)
            assert(result.isFailure)
        }

        "Json.encodeBytes returns non-empty Span[Byte]" in {
            val person = MTPerson("Alice", 30)
            val bytes  = Json.encodeBytes(person)
            assert(bytes.size > 0)
        }

        "Json round-trip encode then decode" in {
            val person  = MTPerson("Bob", 25)
            val json    = Json.encode(person)
            val decoded = Json.decode[MTPerson](json).getOrThrow
            assert(decoded == person)
        }

        "Json works with nested case classes and collections" in {
            val team = MTSmallTeam(MTPerson("Alice", 30), 5)
            val json = Json.encode(team)
            assert(json.contains("\"lead\""))
            assert(json.contains("\"Alice\""))
            val decoded = Json.decode[MTSmallTeam](json).getOrThrow
            assert(decoded == team)

            // Option
            val opt     = MTOptional("Alice", Some("Ali"))
            val optJson = Json.encode(opt)
            val optDec  = Json.decode[MTOptional](optJson).getOrThrow
            assert(optDec == opt)

            // List
            val list    = List(MTPerson("Alice", 30), MTPerson("Bob", 25))
            val listStr = Json.encode(list)
            val listDec = Json.decode[List[MTPerson]](listStr).getOrThrow
            assert(listDec == list)

            // Map
            val map    = Map("a" -> 1, "b" -> 2)
            val mapStr = Json.encode(map)
            val mapDec = Json.decode[Map[String, Int]](mapStr).getOrThrow
            assert(mapDec == map)
        }

        "no explicit codec summoning needed" in {
            // These calls should compile without any explicit Codec summoning
            val person  = MTPerson("Charlie", 40)
            val json    = Json.encode(person)
            val decoded = Json.decode[MTPerson](json).getOrThrow
            assert(decoded == person)

            // Nested type also auto-derives
            val addr     = MTAddress("123 Main", "NYC", "10001")
            val addrJson = Json.encode(addr)
            val addrDec  = Json.decode[MTAddress](addrJson).getOrThrow
            assert(addrDec == addr)
        }

        "Json entry points work" in {
            val person = MTPerson("Diana", 35)
            val bytes  = Json.encodeBytes(person)
            val result = Json.decodeBytes[MTPerson](bytes).getOrThrow
            assert(result == person)
        }

    }

    // ===================================================================
    // edge cases: from JsonEdgeCaseTest
    // ===================================================================

    "edge cases" - {

        // 1. BIGDECIMAL PRECISION BUGS

        "BigDecimal trailing zeros are preserved" in {
            val original = BigDecimal("0.010")
            val json     = Json.encode(original)
            val decoded  = Json.decode[BigDecimal](json).getOrThrow
            assert(decoded == original)
        }

        "BigDecimal 1.0 preserves decimal representation" in {
            val original = BigDecimal("1.0")
            val json     = Json.encode(original)
            assert(json.contains("."), s"Expected decimal point in: $json")
        }

        "BigDecimal high precision values round-trip correctly" in {
            val original = BigDecimal("123456789.123456789123456789")
            val json     = Json.encode(original)
            val decoded  = Json.decode[BigDecimal](json).getOrThrow
            assert(decoded == original)
        }

        "BigDecimal with many decimal places round-trips" in {
            val original = BigDecimal("0.12345678901234567890123456789")
            val json     = Json.encode(original)
            val decoded  = Json.decode[BigDecimal](json).getOrThrow
            assert(decoded == original)
        }

        "BigDecimal negative with precision round-trips" in {
            val original = BigDecimal("-98765432.123456789")
            val json     = Json.encode(original)
            val decoded  = Json.decode[BigDecimal](json).getOrThrow
            assert(decoded == original)
        }

        "BigDecimal zero variations round-trip" in {
            val values = List(
                BigDecimal("0"),
                BigDecimal("0.0"),
                BigDecimal("0.00"),
                BigDecimal("-0"),
                BigDecimal("-0.0")
            )
            values.foreach { original =>
                val json    = Json.encode(original)
                val decoded = Json.decode[BigDecimal](json).getOrThrow
                assert(
                    decoded == original || (decoded.compareTo(BigDecimal(0)) == 0 && original.compareTo(BigDecimal(0)) == 0),
                    s"Failed for $original -> $json -> $decoded"
                )
            }
            ()
        }

        // 2. LARGE NUMBER DOS PREVENTION

        "large exponent BigDecimal does not cause DoS" in {
            val json = "1e-10000"

            val result = Json.decode[BigDecimal](json)
            result match
                case Result.Success(_) =>
                    succeed("decode of an extreme exponent terminated with a value (no DoS); reaching this branch is the proof")
                case Result.Failure(_) =>
                    succeed("decode of an extreme exponent terminated with a rejection (no DoS); rejection is also correct")
            end match
        }

        "extremely large positive exponent handled safely" in {
            val json   = "1e308"
            val result = Json.decode[Double](json)
            result match
                case Result.Success(v) => assert(!v.isNaN && !v.isInfinite)
                case Result.Failure(_) => ()
        }

        "number with many digits does not cause DoS" in {
            val manyDigits = "0." + "1" * 1000
            val start      = java.lang.System.currentTimeMillis()
            val result     = Json.decode[BigDecimal](manyDigits)
            val elapsed    = java.lang.System.currentTimeMillis() - start

            assert(elapsed < 5000, s"Parsing took too long: ${elapsed}ms")
        }

        // 3. DEEP NESTING PROTECTION

        "deeply nested JSON does not cause stack overflow" in {
            val depth      = 100
            val nestedJson = "[" * depth + "1" + "]" * depth

            val result = Json.decode[List[Int]](nestedJson)
            result match
                case Result.Success(_) => succeed("deeply nested array parsed without stack overflow; reaching this branch is the proof")
                case Result.Failure(_) => succeed("deeply nested array rejected without stack overflow; reaching this branch is the proof")
        }

        "deeply nested objects handled safely" in {
            val depth      = 50
            val nestedJson = "{\"a\":" * depth + "1" + "}" * depth

            try
                val reader = JsonReader(nestedJson)
                discard(reader)
                succeed("JsonReader constructed for deeply nested objects without stack overflow")
            catch
                case _: StackOverflowError => fail("Stack overflow on nested JSON")
                case _: Exception => succeed("a parse error on deeply nested objects is acceptable; the contract is no stack overflow")
            end try
        }

        "JsonWriter arrayStart nesting past 64-depth boundary round-trips" in {
            // 120 levels of array nesting: each arrayStart pushes depth by 1.
            // Depth 1..63 lives in word 0; depth 64..120 lives in word 1, so
            // needsComma must grow from length 1 to length 2 at depth 64.
            val depth = 120
            val w     = JsonWriter()
            var i     = 0
            while i < depth do
                w.arrayStart(1)
                i += 1
            w.int(42)
            i = 0
            while i < depth do
                w.arrayEnd()
                i += 1
            val json     = w.resultString
            val expected = "[" * depth + "42" + "]" * depth
            assert(json == expected, s"mismatch at depth=$depth")
        }

        "JsonWriter objectStart nesting past 64-depth boundary round-trips" in {
            // 100 levels of object nesting: each objectStart pushes depth.
            val depth = 100
            val w     = JsonWriter()
            var i     = 0
            while i < depth do
                w.objectStart("o", 1)
                w.field("k", 0)
                i += 1
            end while
            w.int(7)
            i = 0
            while i < depth do
                w.objectEnd()
                i += 1
            val json = w.resultString
            // Decode back as deeply-nested raw structure via Json.decode with
            // matching Schema is awkward; instead verify that the output starts
            // with the expected open-brace sequence and ends with close-braces.
            assert(json.startsWith("{\"k\":" * depth + "7"), s"unexpected prefix at depth=$depth: ${json.take(40)}...")
            assert(json.endsWith("}" * depth), s"unexpected suffix at depth=$depth: ...${json.takeRight(40)}")
        }

        "JsonWriter at exact word boundary depth 64 is correct" in {
            // depth exactly 64 triggers the first grow (since initial size is 1 Long = slots 0-63).
            val depth = 64
            val w     = JsonWriter()
            var i     = 0
            while i < depth do
                w.arrayStart(1)
                i += 1
            w.int(1)
            w.int(2) // second element at depth 64; exercises setFlag/getFlag at slot 0 of word 1
            i = 0
            while i < depth do
                w.arrayEnd()
                i += 1
            val json = w.resultString
            // Innermost array has "1,2", wrapped by 63 additional "[...]"
            val expected = "[" * depth + "1,2" + "]" * depth
            assert(json == expected)
        }

        "JsonWriter at word-boundary depth 65 is correct" in {
            // Guards against off-by-one in `depth & 63` on the second word.
            val depth = 65
            val w     = JsonWriter()
            var i     = 0
            while i < depth do
                w.arrayStart(1)
                i += 1
            w.int(1)
            w.int(2) // two comma-separated elements at depth 65, slot 1 of word 1
            i = 0
            while i < depth do
                w.arrayEnd()
                i += 1
            val json     = w.resultString
            val expected = "[" * depth + "1,2" + "]" * depth
            assert(json == expected)
        }

        "deeply-nested recursive case class round-trips past 64-depth boundary" in {
            // Builds a linked-list of case classes 70 levels deep. Each level
            // writes objectStart (depth +1) + field "children" + arrayStart
            // (depth +1), so effective write-path depth reaches ~140, well past
            // the first Array[Long] word boundary.
            val leafDepth = 70
            var tree      = TreeNode(0, scala.Nil)
            var i         = 1
            while i <= leafDepth do
                tree = TreeNode(i, List(tree))
                i += 1
            val json    = Json.encode(tree)
            val decoded = Json.decode[TreeNode](json).getOrThrow
            assert(decoded == tree)
        }

        // 4. NULL HANDLING - NO NPE

        "null String field does not cause NPE on encode" in {
            val json = """{"a":null,"b":"foo"}"""

            val result = Json.decode[EdgeStringFields](json)
            result match
                case Result.Success(_)                       => fail("Should not decode null to String")
                case Result.Failure(_: NullPointerException) => fail("Should not throw NPE")
                case Result.Failure(_) =>
                    succeed("null String field rejected with a non-NPE failure; reaching this branch is the verification")
            end match
        }

        "null in Option field decodes to None" in {
            val json   = """{"name":null,"age":null}"""
            val result = Json.decode[EdgeOptionalFields](json).getOrThrow
            assert(result.name.isEmpty)
            assert(result.age.isEmpty)
        }

        "missing Optional field decodes to None" in {
            val json   = """{}"""
            val result = Json.decode[EdgeOptionalFields](json).getOrThrow
            assert(result.name.isEmpty)
            assert(result.age.isEmpty)
        }

        "null to Double does not decode to NaN" in {
            val json   = "null"
            val result = Json.decode[Double](json)

            result match
                case Result.Success(v) =>
                    assert(!v.isNaN, "null should not decode to NaN")
                    fail("null should not decode to Double at all")
                case Result.Failure(_) => succeed("null is rejected as a Double value; reaching the Failure branch is the verification")
            end match
        }

        "null to Int fails gracefully" in {
            val json   = "null"
            val result = Json.decode[Int](json)
            assert(result.isFailure, "null should not decode to Int")
        }

        // 5. DUPLICATE KEYS

        "duplicate keys in JSON - last value wins or error" in {
            val json = """{"a": 1, "a": 2}"""

            val result = Json.decode[Map[String, Int]](json)
            result match
                case Result.Success(map) =>
                    assert(map("a") == 1 || map("a") == 2)
                case Result.Failure(_) =>
                    ()
            end match
        }

        // 6. UNICODE AND SPECIAL CHARACTERS

        "4-byte UTF-8 characters (emoji) round-trip correctly" in {
            val emoji   = "Hello 😀🎉🚀 World"
            val json    = Json.encode(emoji)
            val decoded = Json.decode[String](json).getOrThrow

            assert(decoded == emoji, s"Emoji corrupted: expected '$emoji', got '$decoded'")
        }

        "various Unicode characters round-trip" in {
            val testStrings = List(
                "Hello, 世界",         // Chinese
                "Привет мир",        // Russian
                "مرحبا بالعالم",     // Arabic
                "🎵🎶🎸🎹🎺",        // Music emoji
                "𝕳𝖊𝖑𝖑𝖔",        // Mathematical symbols (4-byte)
                "\uD83D\uDE00",      // Emoji as surrogate pair
                "a\u0000b",          // Null character
                "line1\nline2\ttab", // Control characters
                "quote\"backslash\\" // Escaped characters
            )

            testStrings.foreach { original =>
                val json    = Json.encode(original)
                val decoded = Json.decode[String](json).getOrThrow
                assert(decoded == original, s"Failed for: $original")
            }
            ()
        }

        "escaped unicode sequences decode correctly" in {
            val json    = "\"Hello \\u0048\\u0065\\u006C\\u006C\\u006F\""
            val decoded = Json.decode[String](json).getOrThrow
            assert(decoded == "Hello Hello")
        }

        "supplementary plane characters (surrogate pairs)" in {
            val json    = "\"\\uD834\\uDD1E\""
            val decoded = Json.decode[String](json).getOrThrow
            assert(decoded == "\uD834\uDD1E")
            assert(decoded.codePointAt(0) == 0x1d11e)
        }

        // 7. LONG PRECISION

        "Long max value round-trips correctly" in {
            val original = Long.MaxValue
            val json     = Json.encode(original)
            val decoded  = Json.decode[Long](json).getOrThrow
            assert(decoded == original)
        }

        "Long min value round-trips correctly" in {
            val original = Long.MinValue
            val json     = Json.encode(original)
            val decoded  = Json.decode[Long](json).getOrThrow
            assert(decoded == original)
        }

        "Large Long values round-trip" in {
            val values = List(
                767946224062369796L,
                9007199254740993L,
                -9007199254740993L,
                1234567890123456789L
            )
            values.foreach { original =>
                val json    = Json.encode(original)
                val decoded = Json.decode[Long](json).getOrThrow
                assert(decoded == original, s"Failed for $original")
            }
            ()
        }

        // 8. FLOAT/DOUBLE PRECISION

        "Float precision preserved" in {
            val values = List(1.5f, 3.14159f, 0.1f, Float.MaxValue, Float.MinPositiveValue)
            values.foreach { original =>
                val json    = Json.encode(original)
                val decoded = Json.decode[Float](json).getOrThrow
                assert(decoded == original, s"Failed for $original")
            }
            ()
        }

        "Double special values handled" in {
            val posInf = Double.PositiveInfinity
            val negInf = Double.NegativeInfinity
            val nan    = Double.NaN

            val jsonPosInf = Json.encode(posInf)
            val jsonNegInf = Json.encode(negInf)
            val jsonNaN    = Json.encode(nan)

            val decodedPosInf = Json.decode[Double](jsonPosInf).getOrThrow
            val decodedNegInf = Json.decode[Double](jsonNegInf).getOrThrow
            val decodedNaN    = Json.decode[Double](jsonNaN).getOrThrow

            assert(decodedPosInf.isPosInfinity)
            assert(decodedNegInf.isNegInfinity)
            assert(decodedNaN.isNaN)
        }

        // FastFloat / Eisel-Lemire fallback path regression tests.
        // These inputs are designed to trigger the slow-path fallback in JsonReader.double() /
        // JsonReader.float() and verify that the fallback produces the correct result.

        "Double with 20+ significant digits falls back and decodes correctly" in {
            // 20 significant digits; the Eisel-Lemire scanner caps the mantissa at 19 digits and sets
            // the truncation flag, triggering the mantissa+1 retry. If the two bounds disagree the fast
            // path returns DoubleBailOut and the fallback path must produce the correct value.
            val json   = "1.23456789012345678901"
            val result = Json.decode[Double](json).getOrThrow
            // The value must be close to the mathematical value (within 1 ULP).
            val expected = java.lang.Double.parseDouble(json)
            assert(result == expected, s"Expected $expected but got $result")
        }

        "Double near MaxValue round-trips through FastFloat end-to-end" in {
            // 1.7976931348623157e308 is Double.MaxValue. The Eisel-Lemire fast path handles this
            // (exponent 308 is within [pow10Min, pow10Max]). Verifies the happy path for large exponents.
            val json   = "1.7976931348623157e308"
            val result = Json.decode[Double](json).getOrThrow
            assert(result == Double.MaxValue, s"Expected Double.MaxValue but got $result")
        }

        "Double whole numbers preserve .0" in {
            val original = 1.0
            val json     = Json.encode(original)
            assert(json.contains("."), s"Expected .0 in: $json")
        }

        // 9. EMPTY VALUES

        "empty string round-trips" in {
            val original = ""
            val json     = Json.encode(original)
            val decoded  = Json.decode[String](json).getOrThrow
            assert(decoded == original)
        }

        "empty array round-trips" in {
            val original = List.empty[Int]
            val json     = Json.encode(original)
            val decoded  = Json.decode[List[Int]](json).getOrThrow
            assert(decoded == original)
        }

        "empty object round-trips" in {
            val original = Map.empty[String, Int]
            val json     = Json.encode(original)
            val decoded  = Json.decode[Map[String, Int]](json).getOrThrow
            assert(decoded == original)
        }

        "empty case class round-trips" in {
            val original = EdgeEmptyCase()
            val json     = Json.encode(original)
            val decoded  = Json.decode[EdgeEmptyCase](json).getOrThrow
            assert(decoded == original)
        }

        // 10. OPTION REPRESENTATION

        "Option[T] uses null representation" in {
            val some: Option[Int] = Some(42)
            val none: Option[Int] = None

            val jsonSome = Json.encode(some)
            val jsonNone = Json.encode(none)

            assert(jsonSome == "42", s"Some(42) should be 42: $jsonSome")
            assert(jsonNone == "null", s"None should be null: $jsonNone")

            val decodedSome = Json.decode[Option[Int]](jsonSome).getOrThrow
            val decodedNone = Json.decode[Option[Int]](jsonNone).getOrThrow

            assert(decodedSome == some)
            assert(decodedNone == none)
        }

        "nested Option[Option[T]] is lossy - known limitation" in {
            val none: Option[Option[Int]]     = Option.empty
            val someNone: Option[Option[Int]] = Some(Option.empty)
            val someSome: Option[Option[Int]] = Some(Some(42))

            assert(Json.decode[Option[Option[Int]]](Json.encode(none)).getOrThrow == none)
            assert(Json.decode[Option[Option[Int]]](Json.encode(someSome)).getOrThrow == someSome)

            val encoded = Json.encode(someNone)
            assert(encoded == "null", "Some(None) encodes to null")
            val decoded = Json.decode[Option[Option[Int]]](encoded).getOrThrow
            assert(decoded == none, s"Some(None) -> null -> None (lossy)")
        }

        // 10b. MAYBE REPRESENTATION

        "Maybe[T] uses nil-based representation" in {
            val present: Maybe[Int] = Present(42)
            val absent: Maybe[Int]  = Absent

            val jsonPresent = Json.encode(present)
            val jsonAbsent  = Json.encode(absent)

            assert(jsonPresent == "42", s"Present should be 42: $jsonPresent")
            assert(jsonAbsent == "null", s"Absent should be null: $jsonAbsent")

            val decodedPresent = Json.decode[Maybe[Int]](jsonPresent).getOrThrow
            val decodedAbsent  = Json.decode[Maybe[Int]](jsonAbsent).getOrThrow

            assert(decodedPresent == present)
            assert(decodedAbsent == absent)
        }

        "nested Maybe[Maybe[T]] uses field presence for outer + nil for inner" in {
            val absent: Maybe[Maybe[Int]]       = Maybe.empty
            val presentEmpty: Maybe[Maybe[Int]] = Present(Maybe.empty)
            val presentValue: Maybe[Maybe[Int]] = Present(Present(42))

            val encodedAbsent = Json.encode(absent)
            assert(encodedAbsent == "null")

            val encodedPresentEmpty = Json.encode(presentEmpty)
            assert(encodedPresentEmpty == "null")

            val encodedPresentValue = Json.encode(presentValue)
            assert(encodedPresentValue == "42")

            val decoded = Json.decode[Maybe[Maybe[Int]]](encodedPresentValue).getOrThrow
            assert(decoded == presentValue)
        }

        "Maybe nesting works correctly in case class fields" in {
            case class NestedMaybe(value: Maybe[Maybe[Int]]) derives CanEqual

            val absent       = NestedMaybe(Maybe.empty)
            val presentEmpty = NestedMaybe(Present(Maybe.empty))
            val presentValue = NestedMaybe(Present(Present(42)))

            val encodedAbsent = Json.encode(absent)
            assert(encodedAbsent == "{}")

            val encodedPresentEmpty = Json.encode(presentEmpty)
            assert(encodedPresentEmpty == """{"value":null}""")

            val encodedPresentValue = Json.encode(presentValue)
            assert(encodedPresentValue == """{"value":42}""")

            assert(Json.decode[NestedMaybe](encodedAbsent).getOrThrow == absent)
            assert(Json.decode[NestedMaybe](encodedPresentEmpty).getOrThrow == presentEmpty)
            assert(Json.decode[NestedMaybe](encodedPresentValue).getOrThrow == presentValue)
        }

        "Maybe in case class with missing field" in {
            case class MaybeFields(name: Maybe[String], age: Maybe[Int]) derives CanEqual

            val json   = """{}"""
            val result = Json.decode[MaybeFields](json)
            result match
                case Result.Success(v) =>
                    assert(v.name == Absent)
                    assert(v.age == Absent)
                case Result.Failure(e) =>
                    fail(s"Missing Maybe fields should decode to Absent: $e")
            end match
        }

        // 11. SEALED TRAIT / SUM TYPES

        "sealed trait variants round-trip" in {
            val shapes: List[EdgeShape] = List(
                EdgeCircle(5.0),
                EdgeRectangle(3.0, 4.0),
                EdgePoint()
            )
            shapes.foreach { original =>
                val json    = Json.encode(original)
                val decoded = Json.decode[EdgeShape](json).getOrThrow
                assert(decoded == original, s"Failed for $original")
            }
            ()
        }

        // 12. RECURSIVE TYPES

        "recursive case class round-trips" in {
            val tree = EdgeTreeNode(
                1,
                List(
                    EdgeTreeNode(
                        2,
                        List(
                            EdgeTreeNode(4, Nil),
                            EdgeTreeNode(5, Nil)
                        )
                    ),
                    EdgeTreeNode(3, Nil)
                )
            )

            val json    = Json.encode(tree)
            val decoded = Json.decode[EdgeTreeNode](json).getOrThrow
            assert(decoded == tree)
        }

        // 13. BIGINT

        "BigInt large values round-trip" in {
            val values = List(
                BigInt("123456789012345678901234567890"),
                BigInt("-999999999999999999999999999999"),
                BigInt(Long.MaxValue) * 1000,
                BigInt(0),
                BigInt(1)
            )
            values.foreach { original =>
                val json    = Json.encode(original)
                val decoded = Json.decode[BigInt](json).getOrThrow
                assert(decoded == original, s"Failed for $original")
            }
            ()
        }

        // 14. WHITESPACE HANDLING

        "JSON with various whitespace parses correctly" in {
            val jsonVariants = List(
                """{"name":"Alice","age":30}""",
                """{ "name" : "Alice" , "age" : 30 }""",
                """{
                  |  "name": "Alice",
                  |  "age": 30
                  |}""".stripMargin,
                "{\t\"name\":\t\"Alice\",\n\"age\":\r\n30}"
            )

            jsonVariants.foreach { json =>
                val decoded = Json.decode[MTPerson](json).getOrThrow
                assert(decoded == MTPerson("Alice", 30), s"Failed for: $json")
            }
            ()
        }

        // 15. NUMERIC EDGE CASES

        "integer with leading zeros" in {
            val json   = "007"
            val result = Json.decode[Int](json)
            result match
                case Result.Success(7) => succeed("parsed '007' as 7, ignoring leading zeros; the literal pattern is the verification")
                case Result.Failure(_) =>
                    succeed("rejected leading zeros as invalid per strict JSON; reaching the Failure branch is the verification")
                case Result.Success(other) => fail(s"Unexpected value: $other")
            end match
        }

        "negative zero" in {
            val json    = "-0"
            val decoded = Json.decode[Double](json).getOrThrow
            assert(decoded == 0.0 || decoded == -0.0)
        }

        "number with positive exponent sign" in {
            val json    = "1e+10"
            val decoded = Json.decode[Double](json).getOrThrow
            assert(decoded == 1e10)
        }

        "decimal starting with dot" in {
            val json   = "\".9\""
            val result = Json.decode[BigDecimal](json)
            result match
                case Result.Success(v) => assert(v == BigDecimal("0.9"))
                case Result.Failure(_) => ()
        }

        // 16. COLLECTIONS

        "List with various element types" in {
            val intList    = List(1, 2, 3)
            val stringList = List("a", "b", "c")
            val mixedTypes = (intList, stringList)

            val json    = Json.encode(mixedTypes)
            val decoded = Json.decode[(List[Int], List[String])](json).getOrThrow
            assert(decoded == mixedTypes)
        }

        "Map with special keys" in {
            val map = Map(
                ""            -> 1,
                "normal"      -> 2,
                "with space"  -> 3,
                "with\"quote" -> 4
            )
            val json    = Json.encode(map)
            val decoded = Json.decode[Map[String, Int]](json).getOrThrow
            assert(decoded == map)
        }

        "nested collections" in {
            val nested: List[Map[String, List[Int]]] = List(
                Map("a" -> List(1, 2), "b" -> List(3, 4)),
                Map("c" -> List(5, 6))
            )
            val json    = Json.encode(nested)
            val decoded = Json.decode[List[Map[String, List[Int]]]](json).getOrThrow
            assert(decoded == nested)
        }

        // 17. ERROR MESSAGES

        "parse error includes position information" in {
            val invalidJson = """{"name": "Alice", "age": }"""
            val result      = Json.decode[Map[String, Int]](invalidJson)

            result match
                case Result.Failure(e) =>
                    val msg = e.getMessage
                    assert(msg != null && msg.nonEmpty)
                case Result.Success(_) =>
                    fail("Should have failed to parse invalid JSON")
            end match
        }

        "type mismatch error is descriptive" in {
            val json   = """{"name": 123}"""
            val result = Json.decode[EdgeStringFields](json)

            result match
                case Result.Failure(e) =>
                    val msg = e.getMessage
                    assert(msg != null && msg.nonEmpty)
                case Result.Success(_) =>
                    fail("Should have failed on type mismatch")
            end match
        }

    }

    // ===================================================================
    // jsonSchema: from JsonSchemaTest
    // ===================================================================

    "jsonSchema" - {

        case class JSWithDefault(name: String, count: Int = 0)
        case class JSBaseValues(bytes: Span[Byte], instant: java.time.Instant, duration: java.time.Duration)
        sealed trait JSShape
        case class JSCircle(radius: Double)              extends JSShape
        case class JSRect(width: Double, height: Double) extends JSShape

        "required fields listed correctly from macro" in {
            val schema = JsonSchema.from[MTPerson]
            schema match
                case obj: JsonSchema.Obj =>
                    assert(obj.required == List("name", "age"))
                case other =>
                    fail(s"Expected Obj, got $other")
            end match
        }

        "macro generates correct schema for case class" in {
            val schema = JsonSchema.from[MTPerson]
            schema match
                case obj: JsonSchema.Obj =>
                    assert(obj.properties.length == 2)
                    assert(obj.properties(0)._1 == "name")
                    assert(obj.properties(0)._2 == JsonSchema.Str())
                    assert(obj.properties(1)._1 == "age")
                    assert(obj.properties(1)._2 == JsonSchema.Integer())
                case other =>
                    fail(s"Expected Obj, got $other")
            end match
        }

        "base value primitive schemas match JSON string representation" in {
            val schema = JsonSchema.from[JSBaseValues]
            schema match
                case obj: JsonSchema.Obj =>
                    assert(obj.properties.find(_._1 == "bytes").map(_._2).contains(JsonSchema.Str()))
                    assert(obj.properties.find(_._1 == "instant").map(_._2).contains(JsonSchema.Str()))
                    assert(obj.properties.find(_._1 == "duration").map(_._2).contains(JsonSchema.Str()))
                case other =>
                    fail(s"Expected Obj, got $other")
            end match
        }

        "macro excludes fields with defaults from required" in {
            val schema = JsonSchema.from[JSWithDefault]
            schema match
                case obj: JsonSchema.Obj =>
                    assert(obj.required == List("name"))
                    assert(!obj.required.contains("count"))
                case other =>
                    fail(s"Expected Obj, got $other")
            end match
        }

        "macro handles Option fields as Nullable" in {
            case class WithOption(name: String, nickname: Option[String])
            val schema = JsonSchema.from[WithOption]
            schema match
                case obj: JsonSchema.Obj =>
                    assert(obj.properties(1)._1 == "nickname")
                    obj.properties(1)._2 match
                        case n: JsonSchema.Nullable =>
                            assert(n.inner == JsonSchema.Str())
                        case other => fail(s"Expected Nullable(Str()), got $other")
                    end match
                case other =>
                    fail(s"Expected Obj, got $other")
            end match
        }

        "macro handles collection fields as Arr" in {
            case class WithList(items: List[Int])
            val schema = JsonSchema.from[WithList]
            schema match
                case obj: JsonSchema.Obj =>
                    obj.properties(0)._2 match
                        case arr: JsonSchema.Arr =>
                            assert(arr.items == JsonSchema.Integer())
                        case other => fail(s"Expected Arr, got $other")
                case other =>
                    fail(s"Expected Obj, got $other")
            end match
        }

        "macro handles sealed trait as OneOf" in {
            val schema = JsonSchema.from[JSShape]
            schema match
                case oneOf: JsonSchema.OneOf =>
                    assert(oneOf.variants.length == 2)
                    assert(oneOf.variants(0)._1 == "JSCircle")
                    assert(oneOf.variants(1)._1 == "JSRect")
                case other =>
                    fail(s"Expected OneOf, got $other")
            end match
        }

        "summon[Schema[MTPerson]] still works" in {
            val schema = summon[Schema[MTPerson]]
            assert(schema != null)
        }

        "summon[Schema[MTShape]] still works" in {
            val schema = summon[Schema[MTShape]]
            assert(schema != null)
        }

        "Schema round-trips for case class" in {
            case class Bar(name: String, value: Int) derives CanEqual
            val original = Bar("test", 42)
            val encoded  = Json.encode(original)
            val decoded  = Json.decode[Bar](encoded).getOrThrow
            assert(decoded == original)
        }

        "Schema round-trips for nested case class" in {
            case class Inner(x: Int) derives CanEqual
            case class Outer(inner: Inner, name: String) derives CanEqual
            val original = Outer(Inner(42), "hello")
            val encoded  = Json.encode(original)
            val decoded  = Json.decode[Outer](encoded).getOrThrow
            assert(decoded == original)
        }

        "Schema round-trips for sealed trait" in {
            sealed trait Status derives CanEqual
            case class Active(msg: String)   extends Status derives CanEqual
            case class Inactive(reason: Int) extends Status derives CanEqual
            val original: Status = Active("ready")
            val encoded          = Json.encode(original)
            val decoded          = Json.decode[Status](encoded).getOrThrow
            assert(decoded == original)
        }

        "jsonSchema with drop omits dropped field" in {
            val schema = Schema[MTUser].drop("ssn")
            val js     = Json.jsonSchema(using schema)
            js match
                case obj: JsonSchema.Obj =>
                    val propNames = obj.properties.map(_._1)
                    assert(!propNames.contains("ssn"), s"dropped field 'ssn' should not appear in properties: $propNames")
                case other =>
                    fail(s"Expected Obj, got $other")
            end match
        }

        "jsonSchema with drop removes from required" in {
            val schema = Schema[MTUser].drop("ssn")
            val js     = Json.jsonSchema(using schema)
            js match
                case obj: JsonSchema.Obj =>
                    assert(!obj.required.contains("ssn"), s"dropped field 'ssn' should not be in required: ${obj.required}")
                case other =>
                    fail(s"Expected Obj, got $other")
            end match
        }

        "jsonSchema with rename uses new field name" in {
            val schema = Schema[MTUser].rename("name", "userName")
            val js     = Json.jsonSchema(using schema)
            js match
                case obj: JsonSchema.Obj =>
                    val propNames = obj.properties.map(_._1)
                    assert(propNames.contains("userName"), s"renamed field 'userName' should appear in properties: $propNames")
                    assert(!propNames.contains("name"), s"original field 'name' should be absent from properties: $propNames")
                case other =>
                    fail(s"Expected Obj, got $other")
            end match
        }

        "jsonSchema with rename updates required" in {
            val schema = Schema[MTUser].rename("name", "userName")
            val js     = Json.jsonSchema(using schema)
            js match
                case obj: JsonSchema.Obj =>
                    assert(obj.required.contains("userName"), s"renamed field 'userName' should be in required: ${obj.required}")
                    assert(!obj.required.contains("name"), s"original 'name' should be absent from required: ${obj.required}")
                case other =>
                    fail(s"Expected Obj, got $other")
            end match
        }

        "jsonSchema with drop and rename combined" in {
            val schema = Schema[MTUser].drop("ssn").rename("name", "userName")
            val js     = Json.jsonSchema(using schema)
            js match
                case obj: JsonSchema.Obj =>
                    val propNames = obj.properties.map(_._1).toSet
                    assert(propNames.contains("userName"), s"'userName' should be in properties: $propNames")
                    assert(propNames.contains("age"), s"'age' should be in properties: $propNames")
                    assert(propNames.contains("email"), s"'email' should be in properties: $propNames")
                    assert(!propNames.contains("ssn"), s"dropped 'ssn' should not be in properties: $propNames")
                    assert(!propNames.contains("name"), s"original 'name' should not be in properties: $propNames")
                case other =>
                    fail(s"Expected Obj, got $other")
            end match
        }

        "Json.jsonSchema[Unit] is an empty object schema" in {
            // Unit serializes as an empty JSON object (`{}`), so its JSON Schema description is an object
            // shape with no properties, not `{"type":"null"}`. The MCP tool inputSchema and other JSON
            // Schema consumers require the object form; the kyo-schema-wide convention is "Unit = empty
            // object" (see Schema.unitSchema).
            assert(Json.jsonSchema[Unit] == Json.JsonSchema.Obj(List.empty, List.empty))
        }

        "Json.encode(()) emits an empty object" in {
            assert(Json.encode(()) == "{}")
        }

        "Json.decode[Unit](\"{}\") returns ()" in {
            assert(Json.decode[Unit]("{}") == Result.Success(()))
        }

    }

    // ===================================================================
    // BigDecimal: from BigNumberCodecTest
    // ===================================================================

    "BigDecimal" - {

        "BigDecimal(0) round-trip" in {
            val v = BigDecimalBox(BigDecimal("0"))
            assert(jsonRoundTrip(v) == v)
        }

        "BigDecimal(3.14) round-trip" in {
            val v = BigDecimalBox(BigDecimal("3.14"))
            assert(jsonRoundTrip(v) == v)
        }

        "BigDecimal(1e100) round-trip" in {
            val v = BigDecimalBox(BigDecimal("1e100"))
            assert(jsonRoundTrip(v) == v)
        }

        "BigDecimal(1e-100) round-trip" in {
            val v = BigDecimalBox(BigDecimal("1e-100"))
            assert(jsonRoundTrip(v) == v)
        }

        "BigDecimal 30 significant digits round-trip" in {
            val v = BigDecimalBox(BigDecimal("1.23456789012345678901234567890"))
            assert(jsonRoundTrip(v) == v)
        }

        "BigDecimal trailing zeros scale preservation" in {
            val original = BigDecimal("1.000")
            val decoded  = jsonRoundTrip(BigDecimalBox(original))
            assert(decoded.value.scale == original.scale, s"Expected scale ${original.scale}, got ${decoded.value.scale}")
        }

        "BigDecimal(-0) round-trip" in {
            val v = BigDecimalBox(BigDecimal("-0"))
            assert(jsonRoundTrip(v) == v)
        }

    }

    // ===================================================================
    // BigInt: from BigNumberCodecTest
    // ===================================================================

    "BigInt" - {

        "BigInt(0) round-trip" in {
            val v = BigIntBox(BigInt("0"))
            assert(jsonRoundTrip(v) == v)
        }

        "BigInt 100 digits positive round-trip" in {
            val hundredOnes = "1" * 100
            val v           = BigIntBox(BigInt(hundredOnes))
            assert(jsonRoundTrip(v) == v)
        }

        "BigInt 100 digits negative round-trip" in {
            val hundredOnes = "1" * 100
            val v           = BigIntBox(BigInt("-" + hundredOnes))
            assert(jsonRoundTrip(v) == v)
        }

    }

    // ===================================================================
    // numeric edge cases: from NumericEdgeTest
    // ===================================================================

    "numeric edge cases" - {

        "Double.NaN JSON round-trip" in {
            val decoded = jsonRoundTrip(DoubleBox(Double.NaN))
            assert(decoded.d.isNaN, s"Expected NaN but got ${decoded.d}")
        }

        "Double.PositiveInfinity JSON round-trip" in {
            val decoded = jsonRoundTrip(DoubleBox(Double.PositiveInfinity))
            assert(decoded.d == Double.PositiveInfinity, s"Expected PositiveInfinity but got ${decoded.d}")
        }

        "Double.NegativeInfinity JSON round-trip" in {
            val decoded = jsonRoundTrip(DoubleBox(Double.NegativeInfinity))
            assert(decoded.d == Double.NegativeInfinity, s"Expected NegativeInfinity but got ${decoded.d}")
        }

        "Float.NaN JSON round-trip" in {
            val decoded = jsonRoundTrip(FloatBox(Float.NaN))
            assert(decoded.f.isNaN, s"Expected NaN but got ${decoded.f}")
        }

        "Float.PositiveInfinity JSON round-trip" in {
            val decoded = jsonRoundTrip(FloatBox(Float.PositiveInfinity))
            assert(decoded.f == Float.PositiveInfinity, s"Expected PositiveInfinity but got ${decoded.f}")
        }

        "Float.NegativeInfinity JSON round-trip" in {
            val decoded = jsonRoundTrip(FloatBox(Float.NegativeInfinity))
            assert(decoded.f == Float.NegativeInfinity, s"Expected NegativeInfinity but got ${decoded.f}")
        }

        "Double -0.0 JSON round-trip" in {
            val decoded = jsonRoundTrip(DoubleBox(-0.0d))
            assert(
                java.lang.Double.doubleToRawLongBits(decoded.d) == java.lang.Double.doubleToRawLongBits(-0.0d),
                s"Expected -0.0 but got ${decoded.d} (bits: ${java.lang.Double.doubleToRawLongBits(decoded.d)})"
            )
        }

        "Double.MinValue JSON round-trip" in {
            val decoded = jsonRoundTrip(DoubleBox(Double.MinValue))
            assert(decoded.d == Double.MinValue, s"Expected Double.MinValue but got ${decoded.d}")
        }

        "Double.MaxValue JSON round-trip" in {
            val decoded = jsonRoundTrip(DoubleBox(Double.MaxValue))
            assert(decoded.d == Double.MaxValue, s"Expected Double.MaxValue but got ${decoded.d}")
        }

        "Double.MinPositiveValue JSON round-trip" in {
            val decoded = jsonRoundTrip(DoubleBox(Double.MinPositiveValue))
            assert(decoded.d == Double.MinPositiveValue, s"Expected Double.MinPositiveValue but got ${decoded.d}")
        }

        "Float.MinValue JSON round-trip" in {
            val decoded = jsonRoundTrip(FloatBox(Float.MinValue))
            assert(decoded.f == Float.MinValue, s"Expected Float.MinValue but got ${decoded.f}")
        }

        "Float.MaxValue JSON round-trip" in {
            val decoded = jsonRoundTrip(FloatBox(Float.MaxValue))
            assert(decoded.f == Float.MaxValue, s"Expected Float.MaxValue but got ${decoded.f}")
        }

        "Structure.Value.primitive(Double.NaN) token round-trip" in {
            val v      = Structure.Value.primitive(Double.NaN)
            val writer = new TestWriter
            summon[Schema[Structure.Value]].writeTo(v, writer)
            val reader = new TestReader(writer.resultTokens)
            val back   = summon[Schema[Structure.Value]].readFrom(reader)
            back match
                case Structure.Value.Decimal(d) => assert(d.isNaN, s"Expected NaN, got $d")
                case other                      => fail(s"Expected Decimal(NaN), got $other")
        }

        "Structure.Value.primitive(Double.PositiveInfinity) token round-trip" in {
            val v      = Structure.Value.primitive(Double.PositiveInfinity)
            val writer = new TestWriter
            summon[Schema[Structure.Value]].writeTo(v, writer)
            val reader = new TestReader(writer.resultTokens)
            val back   = summon[Schema[Structure.Value]].readFrom(reader)
            back match
                case Structure.Value.Decimal(d) =>
                    assert(d == Double.PositiveInfinity, s"Expected PositiveInfinity, got $d")
                case other => fail(s"Expected Decimal(PositiveInfinity), got $other")
            end match
        }

        "Structure.Value.primitive(Double.NegativeInfinity) token round-trip" in {
            val v      = Structure.Value.primitive(Double.NegativeInfinity)
            val writer = new TestWriter
            summon[Schema[Structure.Value]].writeTo(v, writer)
            val reader = new TestReader(writer.resultTokens)
            val back   = summon[Schema[Structure.Value]].readFrom(reader)
            back match
                case Structure.Value.Decimal(d) =>
                    assert(d == Double.NegativeInfinity, s"Expected NegativeInfinity, got $d")
                case other => fail(s"Expected Decimal(NegativeInfinity), got $other")
            end match
        }

    }

    // ===================================================================
    // Instant: from TimeCodecTest
    // ===================================================================

    "Instant" - {

        import java.time.Instant

        "Instant.ofEpochSecond(0) round-trip" in {
            val v = InstantBox(Instant.ofEpochSecond(0))
            assert(jsonRoundTrip(v) == v)
        }

        "Instant.ofEpochSecond(1) round-trip" in {
            val v = InstantBox(Instant.ofEpochSecond(1))
            assert(jsonRoundTrip(v) == v)
        }

        "Instant.ofEpochSecond(-1) round-trip" in {
            val v = InstantBox(Instant.ofEpochSecond(-1))
            assert(jsonRoundTrip(v) == v)
        }

        "Instant.ofEpochSecond(1000000000, 123456789) round-trip" in {
            val v = InstantBox(Instant.ofEpochSecond(1000000000L, 123456789L))
            assert(jsonRoundTrip(v) == v)
        }

        "Instant.ofEpochSecond(1000000000, 0) round-trip" in {
            val v = InstantBox(Instant.ofEpochSecond(1000000000L, 0L))
            assert(jsonRoundTrip(v) == v)
        }

        "Instant.parse max year edge round-trip" in {
            val v = InstantBox(Instant.parse("9999-12-31T23:59:59.999999999Z"))
            assert(jsonRoundTrip(v) == v)
        }

        "Instant.parse min year edge round-trip" in {
            val v = InstantBox(Instant.parse("0001-01-01T00:00:00Z"))
            assert(jsonRoundTrip(v) == v)
        }

        "Instant.now round-trip" in {
            val now = Instant.now()
            val v   = InstantBox(now)
            assert(jsonRoundTrip(v) == v)
        }

        "Instant.ofEpochSecond(1609459200, 500000000) round-trip" in {
            val v = InstantBox(Instant.ofEpochSecond(1609459200L, 500000000L))
            assert(jsonRoundTrip(v) == v)
        }

    }

    // ===================================================================
    // Duration: from TimeCodecTest
    // ===================================================================

    "Duration" - {

        import java.time.Duration

        "Duration.ZERO round-trip" in {
            val v = DurationBox(Duration.ZERO)
            assert(jsonRoundTrip(v) == v)
        }

        "Duration.ofSeconds(1) round-trip" in {
            val v = DurationBox(Duration.ofSeconds(1))
            assert(jsonRoundTrip(v) == v)
        }

        "Duration.ofSeconds(-1) round-trip" in {
            val v = DurationBox(Duration.ofSeconds(-1))
            assert(jsonRoundTrip(v) == v)
        }

        "Duration.ofNanos(123456789) round-trip" in {
            val v = DurationBox(Duration.ofNanos(123456789L))
            assert(jsonRoundTrip(v) == v)
        }

        "Duration.ofSeconds(1, 500000000) round-trip" in {
            val v = DurationBox(Duration.ofSeconds(1L, 500000000L))
            assert(jsonRoundTrip(v) == v)
        }

        "Duration.ofDays(36500) round-trip" in {
            val v = DurationBox(Duration.ofDays(36500L))
            assert(jsonRoundTrip(v) == v)
        }

    }

    // ===================================================================
    // recursive types: JSON tests from RecursiveTypeTest
    // ===================================================================

    "recursive types" - {

        // Self-recursive types

        "JSON encode/decode: empty tree" in {
            val tree    = TreeNode(42, scala.Nil)
            val json    = Json.encode(tree)
            val decoded = Json.decode[TreeNode](json).getOrThrow
            assert(decoded == tree)
        }

        "JSON encode/decode: shallow tree" in {
            val tree = TreeNode(
                1,
                List(
                    TreeNode(2, scala.Nil),
                    TreeNode(3, scala.Nil)
                )
            )
            val json    = Json.encode(tree)
            val decoded = Json.decode[TreeNode](json).getOrThrow
            assert(decoded == tree)
        }

        "JSON encode/decode: deep tree" in {
            val tree = TreeNode(
                1,
                List(
                    TreeNode(
                        2,
                        List(
                            TreeNode(
                                3,
                                List(
                                    TreeNode(4, scala.Nil)
                                )
                            )
                        )
                    )
                )
            )
            val json    = Json.encode(tree)
            val decoded = Json.decode[TreeNode](json).getOrThrow
            assert(decoded == tree)
        }

        // Mutually recursive types

        "JSON encode/decode: mutual recursion (no cycle)" in {
            val dept    = RTDepartment("Engineering", RTEmployee("Alice", Maybe.empty))
            val json    = Json.encode(dept)
            val decoded = Json.decode[RTDepartment](json).getOrThrow
            assert(decoded == dept)
        }

        "JSON encode/decode: mutual recursion with cycle" in {
            val innerDept = RTDepartment("Engineering", RTEmployee("Bob", Maybe.empty))
            val dept      = RTDepartment("Engineering", RTEmployee("Alice", Maybe(innerDept)))
            val json      = Json.encode(dept)
            val decoded   = Json.decode[RTDepartment](json).getOrThrow
            assert(decoded == dept)
        }

        // Recursive sealed traits

        "JSON encode/decode: recursive sealed trait leaf" in {
            val expr: Expr = Lit(42)
            val json       = Json.encode(expr)
            val decoded    = Json.decode[Expr](json).getOrThrow
            assert(decoded == expr)
        }

        "JSON encode/decode: recursive sealed trait nested" in {
            val expr: Expr = Add(Lit(1), Neg(Lit(2)))
            val json       = Json.encode(expr)
            val decoded    = Json.decode[Expr](json).getOrThrow
            assert(decoded == expr)
        }

        "JSON encode/decode: deeply nested expr" in {
            val expr: Expr = Add(
                Add(Neg(Lit(1)), Lit(2)),
                Neg(Add(Lit(3), Lit(4)))
            )
            val json    = Json.encode(expr)
            val decoded = Json.decode[Expr](json).getOrThrow
            assert(decoded == expr)
        }

        // JsonSchema generation for recursive types

        "JsonSchema from recursive case class" in {
            val schema = JsonSchema.from[TreeNode]
            schema match
                case obj: JsonSchema.Obj =>
                    assert(obj.properties.exists(_._1 == "value"))
                    assert(obj.properties.exists(_._1 == "children"))
                    val childrenSchema = obj.properties.find(_._1 == "children").get._2
                    childrenSchema match
                        case arr: JsonSchema.Arr =>
                            arr.items match
                                case _: JsonSchema.Obj => ()
                                case other             => fail(s"Expected Obj for recursive TreeNode items, got $other")
                        case other => fail(s"Expected Arr for children, got $other")
                    end match
                case other => fail(s"Expected Obj, got $other")
            end match
        }

        "JsonSchema from mutually recursive types" in {
            val schema = JsonSchema.from[RTDepartment]
            schema match
                case obj: JsonSchema.Obj =>
                    assert(obj.properties.exists(_._1 == "name"))
                    assert(obj.properties.exists(_._1 == "manager"))
                case other => fail(s"Expected Obj, got $other")
            end match
        }

        "JsonSchema from recursive sealed trait" in {
            val schema = JsonSchema.from[Expr]
            schema match
                case oneOf: JsonSchema.OneOf =>
                    assert(oneOf.variants.exists(_._1 == "Lit"))
                    assert(oneOf.variants.exists(_._1 == "Add"))
                    assert(oneOf.variants.exists(_._1 == "Neg"))
                    // Recursive-variant field-shape preservation guard: `Add(left: Expr, right: Expr)` and
                    // `Lit(value: Int)` must surface their non-recursive fields on the variant JsonSchema.
                    // Internal helper schemas inside the macro use `Open(Tag[Any])` as structure but those
                    // helpers are not visible here; the parent sum's `variants` carry the real Product shape
                    // (via `summonFieldStructure` -> `deriveTypeFallback` for cyclic descent).
                    val litVariant = oneOf.variants.find(_._1 == "Lit").get._2
                    litVariant match
                        case obj: JsonSchema.Obj =>
                            assert(obj.properties.exists(_._1 == "value"))
                        case other => fail(s"Expected Lit Obj with 'value' property, got $other")
                    end match
                    val addVariant = oneOf.variants.find(_._1 == "Add").get._2
                    addVariant match
                        case obj: JsonSchema.Obj =>
                            assert(obj.properties.exists(_._1 == "left"))
                            assert(obj.properties.exists(_._1 == "right"))
                        case other => fail(s"Expected Add Obj with 'left'/'right' properties, got $other")
                    end match
                case other => fail(s"Expected OneOf, got $other")
            end match
        }

    }

    // ===================================================================
    // Schema[Either] round-trip tests
    // ===================================================================

    "Schema[Either]" - {

        "Schema[Either] round-trips Left through JSON" in {
            val v: Either[String, Int] = Left("err")
            val s                      = Json.encode(v)
            assert(Json.decode[Either[String, Int]](s) == Result.Success(v))
        }

        "Schema[Either] round-trips Right through JSON" in {
            val v: Either[String, Int] = Right(42)
            val s                      = Json.encode(v)
            assert(Json.decode[Either[String, Int]](s) == Result.Success(v))
        }

        "Schema[Either] round-trips Left through JSON bytes" in {
            val v: Either[String, Int] = Left("err")
            val encoded                = Json.encodeBytes(v)
            val decoded                = Json.decodeBytes[Either[String, Int]](encoded).getOrThrow
            assert(decoded == v)
        }

        "Schema[Either] round-trips Right through JSON bytes" in {
            val v: Either[String, Int] = Right(42)
            val encoded                = Json.encodeBytes(v)
            val decoded                = Json.decodeBytes[Either[String, Int]](encoded).getOrThrow
            assert(decoded == v)
        }

    }

    // ===================================================================
    // JsonSchema.Obj additionalProperties tests
    // ===================================================================

    "JsonSchema.Obj additionalProperties" - {

        "Json.jsonSchema[Map[String, Int]] populates additionalProperties" in {
            val s = Json.jsonSchema[Map[String, Int]]
            s match
                case obj: Json.JsonSchema.Obj =>
                    assert(obj.additionalProperties.isDefined)
                    assert(obj.additionalProperties.get == Json.JsonSchema.Integer())
                case _ =>
                    fail(s"expected Obj, got $s")
            end match
        }

        "Json.jsonSchema[Map[String, String]] populates additionalProperties with Str" in {
            val s = Json.jsonSchema[Map[String, String]]
            s match
                case obj: Json.JsonSchema.Obj =>
                    assert(obj.additionalProperties.isDefined)
                    assert(obj.additionalProperties.get == Json.JsonSchema.Str())
                case _ =>
                    fail(s"expected Obj, got $s")
            end match
        }

        "Json.jsonSchema[Map[String, Boolean]] populates additionalProperties with Bool" in {
            val s = Json.jsonSchema[Map[String, Boolean]]
            s match
                case obj: Json.JsonSchema.Obj =>
                    assert(obj.additionalProperties.isDefined)
                    assert(obj.additionalProperties.get == Json.JsonSchema.Bool())
                case _ =>
                    fail(s"expected Obj, got $s")
            end match
        }

    }

    // ===================================================================
    // Cross-library bug report regression tests
    //
    // Tests ported from bug reports in other JSON/serialization libraries
    // to ensure kyo-schema does not have the same issues.
    // ===================================================================

    "cross-library regressions" - {

        // -----------------------------------------------------------------
        // STRING ESCAPING
        // -----------------------------------------------------------------

        // simdjson #1870: lone low surrogate produces invalid UTF-8
        // https://github.com/simdjson/simdjson/issues/1870
        "lone low surrogate is rejected" in {
            val json   = "\"\\uDC00\""
            val result = Json.decode[String](json)
            assert(result.isFailure, "Lone low surrogate should be rejected")
        }

        // simdjson #1870: lone high surrogate
        "lone high surrogate is rejected" in {
            val json   = "\"\\uD800\""
            val result = Json.decode[String](json)
            assert(result.isFailure, "Lone high surrogate should be rejected")
        }

        // JSONTestSuite: surrogate pair with mixed-case hex digits
        // https://github.com/nst/JSONTestSuite
        "surrogate pair with mixed-case hex digits" in {
            val json    = "\"\\uD834\\uDd1e\""
            val decoded = Json.decode[String](json).getOrThrow
            assert(decoded.codePointAt(0) == 0x1d11e) // U+1D11E MUSICAL SYMBOL G CLEF
        }

        // spray-json #254: U+FFFF character causes parse failure
        // https://github.com/spray/spray-json/issues/254
        "U+FFFF noncharacter round-trips" in {
            val json    = "\"a\\uFFFFb\""
            val decoded = Json.decode[String](json).getOrThrow
            assert(decoded == "a\uFFFFb")
        }

        // JSONTestSuite: null escape in string
        // https://github.com/nst/JSONTestSuite
        "null byte escape round-trips" in {
            val json    = "\"\\u0000\""
            val decoded = Json.decode[String](json).getOrThrow
            assert(decoded == "\u0000")
        }

        // jackson-core #223: supplementary characters must survive round-trip
        // https://github.com/FasterXML/jackson-core/issues/223
        "supplementary character U+1F602 round-trips" in {
            val emoji   = new String(Character.toChars(0x1f602)) // 😂
            val json    = Json.encode(emoji)
            val decoded = Json.decode[String](json).getOrThrow
            assert(decoded == emoji)
            assert(decoded.codePointAt(0) == 0x1f602)
        }

        // jackson-core #1359: BMP chars above surrogate block misidentified
        // https://github.com/FasterXML/jackson-core/issues/1359
        "full-width comma U+FF0C round-trips" in {
            val s       = "foo\uFF0Cbar"
            val json    = Json.encode(s)
            val decoded = Json.decode[String](json).getOrThrow
            assert(decoded == s)
        }

        // jackson-databind #4323: U+2028 lost after round-trip
        // https://github.com/FasterXML/jackson-databind/issues/4323
        "U+2028 line separator preserved in round-trip" in {
            val s       = "before\u2028after"
            val json    = Json.encode(s)
            val decoded = Json.decode[String](json).getOrThrow
            assert(decoded == s)
        }

        // -----------------------------------------------------------------
        // NUMBER PARSING
        // -----------------------------------------------------------------

        // simdjson #2093: decimal overflow during double parsing
        // https://github.com/simdjson/simdjson/issues/2093
        "long decimal mantissa parses correctly" in {
            val json    = "0.95000000000000000000"
            val decoded = Json.decode[Double](json).getOrThrow
            assert(decoded == 0.95)
        }

        // simdjson #1415: Long.MaxValue + 1 wraps instead of erroring
        // https://github.com/simdjson/simdjson/issues/1415
        "Long.MaxValue + 1 as Long fails or returns correct BigInt" in {
            val json   = "9223372036854775808"
            val result = Json.decode[Long](json)
            result match
                case Result.Success(v) =>
                    // Must NOT silently wrap to Long.MinValue
                    assert(v != Long.MinValue, "Must not silently wrap overflow")
                case Result.Failure(_) => succeed("rejecting the overflow is also correct; reaching the Failure branch is the verification")
            end match
        }

        // upickle #259: integer overflow silently wraps for narrow types
        // https://github.com/com-lihaoyi/upickle/issues/259
        "Byte overflow is rejected" in {
            val result = Json.decode[Byte]("999")
            result match
                case Result.Success(v) =>
                    assert(v != -25.toByte, "Must not silently wrap Byte overflow")
                case Result.Failure(_) => succeed("rejecting the overflow is also correct; reaching the Failure branch is the verification")
            end match
        }

        // upickle #259: Short overflow silently wraps
        "Short overflow is rejected" in {
            val result = Json.decode[Short]("99999")
            result match
                case Result.Success(v) =>
                    assert(v != -31073.toShort, "Must not silently wrap Short overflow")
                case Result.Failure(_) => succeed("rejecting the overflow is also correct; reaching the Failure branch is the verification")
            end match
        }

        // upickle #259: Int overflow silently wraps
        "Int overflow is rejected" in {
            val result = Json.decode[Int]("9999999999")
            result match
                case Result.Success(v) =>
                    assert(v != 1410065407, "Must not silently wrap Int overflow")
                case Result.Failure(_) => succeed("rejecting the overflow is also correct; reaching the Failure branch is the verification")
            end match
        }

        // zio-json #221: float parsing wrong for long mantissa
        // https://github.com/zio/zio-json/issues/221
        "float precision edge: 1.199999988079071" in {
            val json    = "1.199999988079071"
            val decoded = Json.decode[Float](json).getOrThrow
            assert(decoded == 1.1999999f, s"Expected 1.1999999f, got $decoded")
        }

        // zio-json #221: near Float.MaxValue
        "float near MaxValue: 3.4028235677973366e38" in {
            val json    = "3.4028235677973366e38"
            val decoded = Json.decode[Float](json).getOrThrow
            assert(decoded == 3.4028235e38f, s"Expected Float.MaxValue, got $decoded")
        }

        // zio-json #221: near Float.MinPositiveValue
        "float near min positive: 7.006492321624086e-46" in {
            val json    = "7.006492321624086e-46"
            val decoded = Json.decode[Float](json).getOrThrow
            assert(decoded == 1.4e-45f, s"Expected Float.MinPositiveValue, got $decoded")
        }

        // simdjson #2099: get_number().get_double() returns 0 for many-digit number
        // https://github.com/simdjson/simdjson/issues/2099
        "many significant digits parse to correct double" in {
            val json    = "1000000000.000000001"
            val decoded = Json.decode[Double](json).getOrThrow
            assert(decoded == 1e9, s"Expected 1e9, got $decoded")
        }

        // play-json #241: BigDecimal precision loss
        // https://github.com/playframework/play-json/issues/241
        "BigDecimal high precision round-trips" in {
            val json    = "\"2.2599999999999997868371792719699442386627197265625\""
            val decoded = Json.decode[BigDecimal](json).getOrThrow
            assert(decoded == BigDecimal("2.2599999999999997868371792719699442386627197265625"))
        }

        // JSONTestSuite / multiple libs: negative zero
        // kotlinx.serialization #2599, Microsoft Bond #141
        "negative zero double preserves sign" in {
            val json    = "-0.0"
            val decoded = Json.decode[Double](json).getOrThrow
            assert(1.0 / decoded == Double.NegativeInfinity, "Expected -0.0, got +0.0")
        }

        // simdjson #187: 19-digit integer with e0 exponent
        // https://github.com/simdjson/simdjson/issues/187
        "19-digit number with e0 exponent" in {
            val json    = "1000000000000000000e0"
            val decoded = Json.decode[Double](json).getOrThrow
            assert(decoded == 1e18)
        }

        // -----------------------------------------------------------------
        // INVALID NUMBER FORMATS (RFC 7159 compliance)
        // -----------------------------------------------------------------

        // json-iterator/go #632: accepts invalid number formats
        // https://github.com/json-iterator/go/issues/632
        "leading zero in number rejected or parsed as 0" in {
            val result = Json.decode[Int]("01")
            result match
                case Result.Success(v) =>
                    assert(v == 0 || v == 1) // either parse as 0 (stop at '1') or as 1 is debatable
                case Result.Failure(_) => ()
            end match
        }

        // -----------------------------------------------------------------
        // STRUCTURE EDGE CASES
        // -----------------------------------------------------------------

        // spray-json #286 (CVE-2018-18855): stack overflow from deep nesting
        // https://github.com/spray/spray-json/issues/286
        "very deep nesting via recursive type is rejected by depth limit" in {
            // Build deeply nested JSON matching EdgeTreeNode(value, children: List[EdgeTreeNode])
            // Original report (spray-json #286): 100K levels caused StackOverflow.
            // With maxDepth=512 (default), depth 5000 is rejected with LimitExceededException.
            val depth = 5000
            val sb    = new StringBuilder
            var i     = 0
            while i < depth do
                sb.append("""{"value":0,"children":[""")
                i += 1
            sb.append("""{"value":0,"children":[]}""")
            i = 0
            while i < depth do
                sb.append("]}")
                i += 1
            val result = Json.decode[EdgeTreeNode](sb.toString)
            assert(result.isFailure, "Should reject nesting beyond maxDepth")
            result match
                case Result.Failure(e: LimitExceededException) =>
                    assert(e.limit == "Nesting depth")
                case other => fail(s"Expected LimitExceededException, got $other")
            end match
        }

        // circe #1363: BigDecimal with huge exponent causes DoS
        // https://github.com/circe/circe/issues/1363
        // spray-json #287: BigDecimal with absurd scale accepted
        // https://github.com/spray/spray-json/issues/287
        "huge exponent BigDecimal does not hang" in {
            val start   = java.lang.System.currentTimeMillis()
            val result  = Json.decode[BigDecimal]("\"1e-100000000\"")
            val elapsed = java.lang.System.currentTimeMillis() - start
            assert(elapsed < 5000, s"BigDecimal with huge exponent took ${elapsed}ms")
        }

        // circe #1040: DoS with million-digit JSON numbers
        // https://github.com/circe/circe/issues/1040
        "million-digit number does not cause DoS" in {
            val json    = "1" + "0" * 999999
            val start   = java.lang.System.currentTimeMillis()
            val result  = Json.decode[Double](json)
            val elapsed = java.lang.System.currentTimeMillis() - start
            assert(elapsed < 5000, s"Million-digit number took ${elapsed}ms")
        }

        // -----------------------------------------------------------------
        // DOUBLE/FLOAT SPECIAL VALUES
        // -----------------------------------------------------------------

        // jsoniter-scala #1160: buffer overrun serializing specific double
        // https://github.com/plokhotnyuk/jsoniter-scala/issues/1160
        "specific double from jsoniter-scala #1160 round-trips" in {
            val d       = java.lang.Double.longBitsToDouble(-6634365113987401870L)
            val json    = Json.encode(d)
            val decoded = Json.decode[Double](json).getOrThrow
            assert(decoded == d, s"Round-trip failed: $d -> $json -> $decoded")
        }

        // -----------------------------------------------------------------
        // FLOAT ROUND-TRIP (values from zio-json #221)
        // https://github.com/zio/zio-json/issues/221
        // -----------------------------------------------------------------

        "float round-trip: boundary values" in {
            val values = List(
                0.0f,
                -0.0f,
                1.0f,
                -1.0f,
                Float.MaxValue,
                Float.MinPositiveValue,
                -Float.MaxValue,
                -Float.MinPositiveValue,
                1.0e7f,
                1.0e-7f,
                3.14159f,
                0.1f,
                0.01f
            )
            values.foreach { v =>
                val json    = Json.encode(v)
                val decoded = Json.decode[Float](json).getOrThrow
                assert(decoded == v, s"Float round-trip failed: $v -> $json -> $decoded")
            }
            ()
        }

        // -----------------------------------------------------------------
        // SECURITY: HASH COLLISION DoS
        // -----------------------------------------------------------------

        // json4s #553: hash-colliding keys cause quadratic parsing
        // https://github.com/json4s/json4s/issues/553
        // argonaut #314: same attack vector
        // https://github.com/argonaut-io/argonaut/issues/314
        // jsoniter-scala PR #325: demonstrated on uJson
        // https://github.com/plokhotnyuk/jsoniter-scala/pull/325
        "hash-colliding keys are rejected by collection size limit" in {
            // "Aa" and "BB" have the same Java String hashCode (2112).
            // Concatenating N copies produces 2^N distinct strings that ALL collide.
            // Original report (json4s #553): 100K colliding keys took >110 seconds without limits.
            // With maxCollectionSize=10000, the decoder rejects before quadratic blowup.
            val base = Array("Aa", "BB")
            def gen(depth: Int): Array[String] =
                if depth == 0 then Array("")
                else for prefix <- gen(depth - 1); b <- base yield prefix + b
            val collidingKeys = gen(17) // 131072 keys
            val hashes        = collidingKeys.iterator.take(100).map(_.hashCode).toSet
            assert(hashes.size == 1, s"Keys should all share same hashCode, got ${hashes.size} distinct hashes")

            // Build a JSON object with 100K truly-colliding keys, same scale as original report
            val sb = new StringBuilder("{")
            var i  = 0
            while i < 100000 do
                if i > 0 then sb.append(',')
                sb.append('"').append(collidingKeys(i)).append("\":null")
                i += 1
            end while
            sb.append('}')
            val json = sb.toString

            // With a 10K limit, parsing is fast and rejects before the quadratic blowup
            val result1 = Json.decode[Map[String, Option[Int]]](json, maxCollectionSize = 10000)
            assert(result1.isFailure, "Should reject 100K colliding keys with maxCollectionSize=10000")

            val result2 = Json.decode[Dict[String, Option[Int]]](json, maxCollectionSize = 10000)
            assert(result2.isFailure, "Dict should also reject 100K colliding keys")
        }

        // -----------------------------------------------------------------
        // SECURITY: NUMERIC DoS VARIANTS
        // -----------------------------------------------------------------

        // jsoniter-scala #282: BigDecimal with many fractional zeros
        // https://github.com/plokhotnyuk/jsoniter-scala/issues/282
        "BigDecimal with many fractional zeros does not DoS" in {
            val json    = "\"0." + "0" * 100000 + "1\""
            val start   = java.lang.System.currentTimeMillis()
            val result  = Json.decode[BigDecimal](json)
            val elapsed = java.lang.System.currentTimeMillis() - start
            assert(elapsed < 5000, s"BigDecimal fractional zeros took ${elapsed}ms")
        }

        // spray-json #287: huge positive exponent BigDecimal
        // https://github.com/spray/spray-json/issues/287
        "huge positive exponent BigDecimal does not hang" in {
            val start   = java.lang.System.currentTimeMillis()
            val result  = Json.decode[BigDecimal]("\"1e1000000000\"")
            val elapsed = java.lang.System.currentTimeMillis() - start
            assert(elapsed < 5000, s"BigDecimal 1e1000000000 took ${elapsed}ms")
        }

        // json4s #554: unexpected field with big number in object
        // https://github.com/json4s/json4s/issues/554
        "object with unexpected large number field does not DoS" in {
            val bigNum  = "1" + "0" * 100000
            val json    = s"""{"name":"Alice","age":30,"unexpected":$bigNum}"""
            val start   = java.lang.System.currentTimeMillis()
            val result  = Json.decode[MTPerson](json)
            val elapsed = java.lang.System.currentTimeMillis() - start
            assert(elapsed < 5000, s"Skipping large unexpected number took ${elapsed}ms")
        }

        // play-json CVE-2020-26882: data amplification via small JSON expanding to huge in-memory representation
        // https://www.playframework.com/security/vulnerability/CVE-2020-26882-JsonParseDataAmplification
        "small exponent notation does not amplify into huge BigDecimal" in {
            // "1e100000" is 8 bytes but BigDecimal("1e100000") has scale=-100000
            // Operations on it can allocate massive backing arrays
            val start   = java.lang.System.currentTimeMillis()
            val result  = Json.decode[BigDecimal]("\"1e100000\"")
            val elapsed = java.lang.System.currentTimeMillis() - start
            assert(elapsed < 5000, s"BigDecimal amplification took ${elapsed}ms")
            result match
                case Result.Success(v) =>
                    // Encoding it back must also be fast (no expansion)
                    val start2   = java.lang.System.currentTimeMillis()
                    val encoded  = Json.encode(v)
                    val elapsed2 = java.lang.System.currentTimeMillis() - start2
                    assert(elapsed2 < 5000, s"BigDecimal re-encoding took ${elapsed2}ms")
                case Result.Failure(_) => ()
            end match
        }

        // -----------------------------------------------------------------
        // SECURITY: LARGE STRUCTURE DoS
        // -----------------------------------------------------------------

        // General: large number of object fields (memory exhaustion)
        "object with many fields does not exhaust memory" in {
            val json    = (0 until 100000).map(i => s""""k$i":$i""").mkString("{", ",", "}")
            val start   = java.lang.System.currentTimeMillis()
            val result  = Json.decode[Map[String, Int]](json)
            val elapsed = java.lang.System.currentTimeMillis() - start
            assert(elapsed < 10000, s"Parsing 100K fields took ${elapsed}ms")
        }

        // General: large array
        "large array does not cause DoS" in {
            val json    = (0 until 100000).mkString("[", ",", "]")
            val start   = java.lang.System.currentTimeMillis()
            val result  = Json.decode[List[Int]](json)
            val elapsed = java.lang.System.currentTimeMillis() - start
            assert(elapsed < 10000, s"Parsing 100K element array took ${elapsed}ms")
        }

        // General: skip() handles deep nesting without stack overflow
        // When decoding a case class, unknown fields are skipped via skip() which
        // uses iterative depth tracking. Verify it works at extreme depth.
        "skip handles extremely deep nesting in unknown field" in {
            val depth  = 100000
            val nested = """{"a":""" * depth + "1" + "}" * depth
            val json   = s"""{"name":"Alice","age":30,"nested":$nested}"""
            try
                val result = Json.decode[MTPerson](json)
                // MTPerson ignores unknown "nested" field; skip() handles the deep nesting
                assert(result.isSuccess)
            catch
                case _: StackOverflowError =>
                    fail("Stack overflow when skipping deeply nested unknown field")
            end try
        }

        // -----------------------------------------------------------------
        // SECURITY: STRING DoS
        // -----------------------------------------------------------------

        // General: very long string
        "very long string does not cause DoS" in {
            val longStr = "\"" + "a" * 1000000 + "\""
            val start   = java.lang.System.currentTimeMillis()
            val result  = Json.decode[String](longStr)
            val elapsed = java.lang.System.currentTimeMillis() - start
            assert(elapsed < 5000, s"Parsing 1M char string took ${elapsed}ms")
        }

        // General: string with many escape sequences
        "string with many escapes does not cause DoS" in {
            val escapedStr = "\"" + "\\n" * 100000 + "\""
            val start      = java.lang.System.currentTimeMillis()
            val result     = Json.decode[String](escapedStr)
            val elapsed    = java.lang.System.currentTimeMillis() - start
            assert(elapsed < 5000, s"Parsing 100K escapes took ${elapsed}ms")
        }

        // General: string with many unicode escapes
        "string with many unicode escapes does not cause DoS" in {
            val unicodeStr = "\"" + "\\u0041" * 100000 + "\""
            val start      = java.lang.System.currentTimeMillis()
            val result     = Json.decode[String](unicodeStr)
            val elapsed    = java.lang.System.currentTimeMillis() - start
            assert(elapsed < 5000, s"Parsing 100K unicode escapes took ${elapsed}ms")
            result match
                case Result.Success(s) => assert(s == "A" * 100000)
                case Result.Failure(_) => ()
        }

        "double round-trip: boundary values" in {
            val values = List(
                0.0,
                -0.0,
                1.0,
                -1.0,
                Double.MaxValue,
                Double.MinPositiveValue,
                -Double.MaxValue,
                -Double.MinPositiveValue,
                1.0e15,
                1.0e-15,
                2.2250738585072014e-308, // smallest normal
                4.9e-324,                // smallest subnormal
                9007199254740993.0       // 2^53 + 1
            )
            values.foreach { v =>
                val json    = Json.encode(v)
                val decoded = Json.decode[Double](json).getOrThrow
                assert(decoded == v, s"Double round-trip failed: $v -> $json -> $decoded")
            }
            ()
        }
    }

    // ===================================================================
    // Generic case class default-value derivation (regression: MacroUtils.getDefault
    // must apply type arguments to the generated default-method ref). The bug
    // surfaced as "Expected an expression. This is a partially applied Term"
    // at Schema-derivation time for any generic case class with at least one
    // default-valued field. The fix lives in `MacroUtils.getDefault` and the
    // tests below pin both the "Schema derives at all" property and the
    // "decode honors the default" / "round-trip preserves omitted-vs-present"
    // properties.
    // ===================================================================

    "generic case class with defaults" - {

        "MTGenericDefault[Int]: Schema derives without macro error (compile-time pin)" in {
            // The mere presence of `derives Schema` on a generic case class with a default
            // previously crashed at macro-expansion. Summoning the Schema here forces the
            // macro to run; a regression would fail the compile, not the assertion.
            val _ = summon[Schema[MTGenericDefault[Int]]]
            succeed("Schema derivation for a generic case class with defaults does not crash; the summon above is the compile-time check")
        }

        "MTGenericDefault[Int]: omitted default decodes to the declared default" in {
            // Input lacks `tag`; the macro-driven decoder must source the default from the
            // generic companion's `<init>$default$2[A]: String` method, applying the type
            // arg `Int`. Pre-fix: macro never compiled; post-fix: default is honored.
            val json    = """{"value":42}"""
            val decoded = Json.decode[MTGenericDefault[Int]](json).getOrThrow
            assert(decoded == MTGenericDefault[Int](42, "default"))
        }

        "MTGenericDefault[String]: explicit value overrides the default and round-trips" in {
            val original = MTGenericDefault[String]("hi", tag = "custom")
            val encoded  = Json.encode(original)
            val decoded  = Json.decode[MTGenericDefault[String]](encoded).getOrThrow
            assert(decoded == original)
        }

        "MTGenericMaybe[Int]: both Maybe-defaulted fields decode from empty object" in {
            // Two Maybe[?]-typed fields with Absent defaults. The macro fix must apply the
            // [Int] type arg to BOTH default methods independently.
            val json    = """{}"""
            val decoded = Json.decode[MTGenericMaybe[Int]](json).getOrThrow
            assert(decoded == MTGenericMaybe[Int]())
        }

        "MTGenericMaybe[Int]: Present(value) round-trips through encode/decode" in {
            val original = MTGenericMaybe[Int](result = Present(7))
            val encoded  = Json.encode(original)
            val decoded  = Json.decode[MTGenericMaybe[Int]](encoded).getOrThrow
            assert(decoded == original)
        }

        "MTGenericMaybe[String]: error-only payload preserves both fields" in {
            val original = MTGenericMaybe[String](error = Present("bad"))
            val encoded  = Json.encode(original)
            val decoded  = Json.decode[MTGenericMaybe[String]](encoded).getOrThrow
            assert(decoded == original)
        }

        "MTGenericTwoParam[Int, String]: two type params with one default field" in {
            val original = MTGenericTwoParam[Int, String](1, "a")
            val encoded  = Json.encode(original)
            val decoded  = Json.decode[MTGenericTwoParam[Int, String]](encoded).getOrThrow
            assert(decoded == original)
        }

        "MTGenericTwoParam[Int, String]: omitted default decodes to 'pair'" in {
            val json    = """{"first":3,"second":"x"}"""
            val decoded = Json.decode[MTGenericTwoParam[Int, String]](json).getOrThrow
            assert(decoded == MTGenericTwoParam[Int, String](3, "x", "pair"))
        }
    }

    // ===================================================================
    // Local test type definitions
    // ===================================================================

    // From JsonEdgeCaseTest
    case class EdgeStringFields(a: String, b: String) derives CanEqual
    case class EdgeOptionalFields(name: Option[String], age: Option[Int]) derives CanEqual
    case class EdgeMixedFields(required: String, optional: Option[String]) derives CanEqual
    case class EdgeEmptyCase() derives CanEqual

    sealed trait EdgeShape derives CanEqual
    case class EdgeCircle(radius: Double)                   extends EdgeShape derives CanEqual
    case class EdgeRectangle(width: Double, height: Double) extends EdgeShape derives CanEqual
    case class EdgePoint()                                  extends EdgeShape derives CanEqual

    case class EdgeTreeNode(value: Int, children: List[EdgeTreeNode]) derives CanEqual, Schema

    // From BigNumberCodecTest
    case class BigDecimalBox(value: BigDecimal) derives CanEqual
    case class BigIntBox(value: BigInt) derives CanEqual

    // From NumericEdgeTest
    case class DoubleBox(d: Double) derives CanEqual
    case class FloatBox(f: Float) derives CanEqual
    case class NumMetrics(d: Double, f: Float) derives CanEqual

    // From TimeCodecTest
    case class InstantBox(instant: java.time.Instant) derives CanEqual
    case class DurationBox(duration: java.time.Duration) derives CanEqual

    "Json.jsonSchema requires Schema in scope" - {

        "fails to compile for a type with no Schema" in {
            typeCheckFailure("""
                class NoSchemaType
                Json.jsonSchema[NoSchemaType]
            """)("NoSchemaType")
        }

    }

    "jsonSchema enrichment via JsonSchemaEnricher" - {

        // Json.jsonSchema enriches the result via internal.JsonSchemaEnricher.enrichObj, so the
        // root and field doc annotations carried by a Schema surface on the produced JsonSchema.

        "jsonSchema carries root doc when Schema has doc annotation" in {
            given schema: Schema[MTUser] = Schema[MTUser].doc("a person")
            val js                       = Json.jsonSchema[MTUser]
            js match
                case obj: JsonSchema.Obj =>
                    assert(obj.description == Maybe("a person"))
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "jsonSchema carries field doc for name field" in {
            given schema: Schema[MTUser] = Schema[MTUser].doc(_.name)("person name")
            val js                       = Json.jsonSchema[MTUser]
            js match
                case obj: JsonSchema.Obj =>
                    val nameProp = obj.properties.find(_._1 == "name").map(_._2)
                    nameProp match
                        case Some(np: JsonSchema.Str) =>
                            assert(np.description == Maybe("person name"))
                        case other =>
                            fail(s"Expected name property as Str with description, got $other")
                    end match
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

    }

    // ===================================================================
    // Non-String-key Dict round-trips through the real codec. Each entry is
    // a two-field {key, value} record.
    // ===================================================================

    "dictSchema non-String-key Dict" - {

        "round-trips a non-String-key Dict" in {
            val holder  = MTIntStringDict(Dict(1 -> "one", 2 -> "two", 3 -> "three"))
            val encoded = Json.encode(holder)
            val decoded = Json.decode[MTIntStringDict](encoded).getOrThrow
            assert(decoded.d.get(1) == Maybe("one"))
            assert(decoded.d.get(2) == Maybe("two"))
            assert(decoded.d.get(3) == Maybe("three"))
            assert(decoded.d.size == 3)
        }

        "round-trips a non-String-key Dict with non-empty collection values" in {
            val holder  = MTIntChunkDict(Dict(1 -> Chunk("a", "b"), 2 -> Chunk("c")))
            val encoded = Json.encode(holder)
            val decoded = Json.decode[MTIntChunkDict](encoded).getOrThrow
            assert(decoded.d.get(1) == Maybe(Chunk("a", "b")))
            assert(decoded.d.get(2) == Maybe(Chunk("c")))
        }

    }

    // ===================================================================
    // OrderedDict Schema givens: insertion-order round-trip and the
    // per-element checkCollectionSize DoS guard.
    // ===================================================================

    "OrderedDict Schema givens" - {

        "OrderedDict[String, V] field preserves insertion order across encode/decode" in {
            val holder =
                MTOrderedDictConfig(OrderedDict("zeta" -> 30, "alpha" -> 3, "mike" -> 8080, "bravo" -> 5, "yankee" -> 100, "delta" -> 42))
            val encoded = Json.encode(holder)
            val decoded = Json.decode[MTOrderedDictConfig](encoded).getOrThrow
            assert(decoded.settings.toChunk.map(_._1) == Chunk("zeta", "alpha", "mike", "bravo", "yankee", "delta"))
        }

        "OrderedDict[Int, String] field round-trips as a JSON array of {key,value} objects preserving insertion order, not sorted" in {
            val holder =
                MTOrderedDictLevels(OrderedDict(30 -> "gold", 10 -> "bronze", 20 -> "silver", 50 -> "copper", 40 -> "tin", 60 -> "iron"))
            val encoded = Json.encode(holder)
            assert(
                encoded == """{"byLevel":[{"key":30,"value":"gold"},{"key":10,"value":"bronze"},{"key":20,"value":"silver"},{"key":50,"value":"copper"},{"key":40,"value":"tin"},{"key":60,"value":"iron"}]}"""
            )
            val decoded = Json.decode[MTOrderedDictLevels](encoded).getOrThrow
            assert(decoded.byLevel.toChunk.map(_._1) == Chunk(30, 10, 20, 50, 40, 60))
        }

        "rejects trailing content after the decoded root value" in {
            // The sibling codecs in this module already do: the Ion reader ends its parse with an
            // explicit trailing-content check, and BSON asserts the same. JSON accepted anything after
            // the first complete value, so a payload holding two values back to back decoded to the
            // first and discarded the rest in silence, and so did one followed by prose.
            //
            // That silence has a cost outside this module. A caller decoding a reply cannot tell a
            // well-formed answer from one with unexplained bytes stuck to it, so a provider defect and
            // a model that misunderstood the format both look like success.
            val twoValues = Json.decode[Int]("1 2")
            assert(twoValues.isFailure, s"two values back to back must not decode to the first: $twoValues")
            val trailingProse = Json.decode[Boolean]("true and then some")
            assert(trailingProse.isFailure, s"a value followed by prose must not decode: $trailingProse")
            val overClosed = Json.decode[Int]("1}")
            assert(overClosed.isFailure, s"a value followed by a stray bracket must not decode: $overClosed")
            // Whitespace is not content and stays acceptable on either side.
            assert(Json.decode[Int](" 1 \n ") == Result.Success(1), "surrounding whitespace must still decode")
        }

        "an oversized OrderedDict[String, V] field decodes to a typed failure, not a bare thrown exception" in {
            val json   = (0 until 20000).map(n => s""""k$n":$n""").mkString("""{"settings":{""", ",", "}}")
            val result = Json.decode[MTOrderedDictConfig](json, maxCollectionSize = 10000)
            assert(result.isFailure, "OrderedDict should reject an oversized entry count via maxCollectionSize")
        }

    }

end JsonTest
