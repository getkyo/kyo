package kyo

import kyo.Codec.Reader
import kyo.Codec.Writer
import kyo.Schema.*
import kyo.internal.JsonReader
import kyo.internal.JsonWriter

// --- Local test types ---

case class CodecTree(value: Int, children: List[CodecTree]) derives CanEqual

case class CodecWithDefaults(name: String, age: Int = 25, active: Boolean = true) derives CanEqual

// --- Phase 1 metaApply gate-coverage fixtures (top-level for macro visibility) ---

case class CodecWithLocalDate(d: java.time.LocalDate) derives CanEqual
case class CodecWithLocalTime(t: java.time.LocalTime) derives CanEqual
case class CodecWithLocalDateTime(dt: java.time.LocalDateTime) derives CanEqual
case class CodecWithUUID(id: java.util.UUID) derives CanEqual
case class CodecWithKyoInstant(at: kyo.Instant) derives CanEqual
case class CodecWithKyoDuration(d: kyo.Duration) derives CanEqual
case class CodecWithKyoText(t: kyo.Text) derives CanEqual
case class CodecWithUnit(u: Unit) derives CanEqual
case class CodecWithSeqInt(xs: Seq[Int]) derives CanEqual
case class CodecWithSpanInt(xs: Span[Int]) derives CanEqual
case class CodecWithEitherStringInt(e: Either[String, Int]) derives CanEqual
case class CodecWithTuple3(t: (Int, String, Boolean)) derives CanEqual

// --- Phase 8 fixture: a key type with no KeyCodec, so Map[CaseClassKey, V] falls back to array-of-pairs. ---
case class CaseClassKey(s: String, i: Int) derives CanEqual, Schema

// --- Token-based Writer/Reader for testing ---

enum Token derives CanEqual:
    case ObjectStart(name: String, size: Int)
    case ObjectEnd
    case ArrayStart(size: Int)
    case ArrayEnd
    case FieldName(name: String)
    case Str(value: String)
    case IntVal(value: Int)
    case LongVal(value: Long)
    case FloatVal(value: Float)
    case DoubleVal(value: Double)
    case BoolVal(value: Boolean)
    case ShortVal(value: Short)
    case ByteVal(value: Byte)
    case CharVal(value: Char)
    case Nil
    case MapStart(size: Int)
    case MapEnd
    case Bytes(value: Span[Byte])
    case BigIntVal(value: BigInt)
    case BigDecimalVal(value: BigDecimal)
    case InstantVal(value: java.time.Instant)
    case DurationVal(value: java.time.Duration)
end Token

class TestWriter extends Writer:
    val tokens = scala.collection.mutable.ListBuffer[Token]()

    def objectStart(name: String, size: Int): Unit = tokens += Token.ObjectStart(name, size)
    def objectEnd(): Unit                          = tokens += Token.ObjectEnd
    def arrayStart(size: Int): Unit                = tokens += Token.ArrayStart(size)
    def arrayEnd(): Unit                           = tokens += Token.ArrayEnd
    def fieldBytes(nameBytes: Array[Byte], index: Int): Unit =
        tokens += Token.FieldName(new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8))
    override def field(name: String, index: Int): Unit = tokens += Token.FieldName(name)
    def string(value: String): Unit                    = tokens += Token.Str(value)
    def int(value: Int): Unit                          = tokens += Token.IntVal(value)
    def long(value: Long): Unit                        = tokens += Token.LongVal(value)
    def float(value: Float): Unit                      = tokens += Token.FloatVal(value)
    def double(value: Double): Unit                    = tokens += Token.DoubleVal(value)
    def boolean(value: Boolean): Unit                  = tokens += Token.BoolVal(value)
    def short(value: Short): Unit                      = tokens += Token.ShortVal(value)
    def byte(value: Byte): Unit                        = tokens += Token.ByteVal(value)
    def char(value: Char): Unit                        = tokens += Token.CharVal(value)
    def nil(): Unit                                    = tokens += Token.Nil
    def mapStart(size: Int): Unit                      = tokens += Token.MapStart(size)
    def mapEnd(): Unit                                 = tokens += Token.MapEnd
    def bytes(value: Span[Byte]): Unit                 = tokens += Token.Bytes(value)
    def bigInt(value: BigInt): Unit                    = tokens += Token.BigIntVal(value)
    def bigDecimal(value: BigDecimal): Unit            = tokens += Token.BigDecimalVal(value)
    def instant(value: java.time.Instant): Unit        = tokens += Token.InstantVal(value)
    def duration(value: java.time.Duration): Unit      = tokens += Token.DurationVal(value)

    def resultTokens: List[Token] = tokens.toList

    def result(): Span[Byte] = Span.empty
end TestWriter

class TestReader(tokens: List[Token])(using _frame: Frame) extends Reader:
    override def frame: Frame = _frame
    private var pos           = 0
    private var _lastField    = ""

    private def next(): Token =
        val t = tokens(pos)
        pos += 1
        t
    end next

    private def peek(): Token = tokens(pos)

    def objectStart(): Int = next() match
        case Token.ObjectStart(_, size) => size
        case t                          => throw TypeMismatchException(scala.Nil, "ObjectStart", t.toString)

    def objectEnd(): Unit = next() match
        case Token.ObjectEnd => ()
        case t               => throw TypeMismatchException(scala.Nil, "ObjectEnd", t.toString)

    def arrayStart(): Int = next() match
        case Token.ArrayStart(size) => size
        case t                      => throw TypeMismatchException(scala.Nil, "ArrayStart", t.toString)

    def arrayEnd(): Unit = next() match
        case Token.ArrayEnd => ()
        case t              => throw TypeMismatchException(scala.Nil, "ArrayEnd", t.toString)

    def field(): String = next() match
        case Token.FieldName(name) =>
            _lastField = name
            name
        case t => throw TypeMismatchException(scala.Nil, "FieldName", t.toString)

    def fieldParse(): Unit =
        val _ = field()

    def matchField(nameBytes: Array[Byte]): Boolean =
        if _lastField.isEmpty then false
        else
            val expected = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)
            _lastField == expected

    def lastFieldName(): String = _lastField

    def hasNextField(): Boolean =
        pos < tokens.size && (peek() match
            case Token.FieldName(_) => true
            case _                  => false)

    def hasNextElement(): Boolean =
        pos < tokens.size && (peek() match
            case Token.ArrayEnd => false
            case _              => true)

    def string(): String = next() match
        case Token.Str(v) => v
        case t            => throw TypeMismatchException(scala.Nil, "Str", t.toString)

    def int(): Int = next() match
        case Token.IntVal(v) => v
        case t               => throw TypeMismatchException(scala.Nil, "IntVal", t.toString)

    def long(): Long = next() match
        case Token.LongVal(v) => v
        case t                => throw TypeMismatchException(scala.Nil, "LongVal", t.toString)

    def float(): Float = next() match
        case Token.FloatVal(v) => v
        case t                 => throw TypeMismatchException(scala.Nil, "FloatVal", t.toString)

    def double(): Double = next() match
        case Token.DoubleVal(v) => v
        case t                  => throw TypeMismatchException(scala.Nil, "DoubleVal", t.toString)

    def boolean(): Boolean = next() match
        case Token.BoolVal(v) => v
        case t                => throw TypeMismatchException(scala.Nil, "BoolVal", t.toString)

    def short(): Short = next() match
        case Token.ShortVal(v) => v
        case t                 => throw TypeMismatchException(scala.Nil, "ShortVal", t.toString)

    def byte(): Byte = next() match
        case Token.ByteVal(v) => v
        case t                => throw TypeMismatchException(scala.Nil, "ByteVal", t.toString)

    def char(): Char = next() match
        case Token.CharVal(v) => v
        case t                => throw TypeMismatchException(scala.Nil, "CharVal", t.toString)

    def isNil(): Boolean =
        if pos < tokens.size && peek() == Token.Nil then
            pos += 1
            true
        else
            false

    def skip(): Unit =
        // Skip one complete value (primitive, object, or array)
        if pos < tokens.size then
            next() match
                case Token.ObjectStart(_, _) =>
                    var depth = 1
                    while depth > 0 do
                        next() match
                            case Token.ObjectStart(_, _) => depth += 1
                            case Token.ObjectEnd         => depth -= 1
                            case _                       => ()
                    end while
                case Token.ArrayStart(_) =>
                    var depth = 1
                    while depth > 0 do
                        next() match
                            case Token.ArrayStart(_) => depth += 1
                            case Token.ArrayEnd      => depth -= 1
                            case _                   => ()
                    end while
                case _ => () // primitive: already consumed

    override def captureValue(): Reader =
        val startPos = pos
        skip()
        val endPos   = pos
        val captured = tokens.slice(startPos, endPos)
        new TestReader(captured)
    end captureValue

    def mapStart(): Int = next() match
        case Token.MapStart(size) => size
        case t                    => throw TypeMismatchException(scala.Nil, "MapStart", t.toString)

    def mapEnd(): Unit = next() match
        case Token.MapEnd => ()
        case t            => throw TypeMismatchException(scala.Nil, "MapEnd", t.toString)

    def hasNextEntry(): Boolean = hasNextField()

    def bytes(): Span[Byte] = next() match
        case Token.Bytes(v) => v
        case t              => throw TypeMismatchException(scala.Nil, "Bytes", t.toString)

    def bigInt(): BigInt = next() match
        case Token.BigIntVal(v) => v
        case t                  => throw TypeMismatchException(scala.Nil, "BigIntVal", t.toString)

    def bigDecimal(): BigDecimal = next() match
        case Token.BigDecimalVal(v) => v
        case t                      => throw TypeMismatchException(scala.Nil, "BigDecimalVal", t.toString)

    def instant(): java.time.Instant = next() match
        case Token.InstantVal(v) => v
        case t                   => throw TypeMismatchException(scala.Nil, "InstantVal", t.toString)

    def duration(): java.time.Duration = next() match
        case Token.DurationVal(v) => v
        case t                    => throw TypeMismatchException(scala.Nil, "DurationVal", t.toString)
end TestReader

// --- Helpers ---

object CodecTestHelper:
    def roundTrip[A](value: A)(using schema: Schema[A], ce: CanEqual[A, A]): A =
        val writer = new TestWriter
        schema.writeTo(value, writer)
        val reader = new TestReader(writer.resultTokens)
        schema.readFrom(reader)
    end roundTrip

    def encode[A](value: A)(using schema: Schema[A]): List[Token] =
        val writer = new TestWriter
        schema.writeTo(value, writer)
        writer.resultTokens
    end encode
end CodecTestHelper

// --- Tests ---

class CodecTest extends Test:

    import CodecTestHelper.*

    given CanEqual[Any, Any] = CanEqual.derived

    // --- Primitive codecs ---

    "string round-trip" in {
        assert(roundTrip("hello") == "hello")
    }

    "int round-trip" in {
        assert(roundTrip(42) == 42)
    }

    "boolean round-trip" in {
        assert(roundTrip(true) == true)
    }

    "double round-trip" in {
        assert(roundTrip(3.14) == 3.14)
    }

    "long round-trip" in {
        assert(roundTrip(123456789L) == 123456789L)
    }

    "float round-trip" in {
        assert(roundTrip(1.5f) == 1.5f)
    }

    "short round-trip" in {
        assert(roundTrip(42.toShort) == 42.toShort)
    }

    "byte round-trip" in {
        assert(roundTrip(7.toByte) == 7.toByte)
    }

    "char round-trip" in {
        assert(roundTrip('x') == 'x')
    }

    // --- Case class schemas ---

    "person round-trip" in {
        val schema = summon[Schema[MTPerson]]
        val person = MTPerson("Alice", 30)
        assert(roundTrip(person)(using schema) == person)
    }

    "team round-trip" in {
        val schema = summon[Schema[MTSmallTeam]]
        val team   = MTSmallTeam(MTPerson("Alice", 30), 5)
        assert(roundTrip(team)(using schema) == team)
    }

    "person encode produces correct tokens" in {
        val schema = summon[Schema[MTPerson]]
        val person = MTPerson("Alice", 30)
        val tokens = encode(person)(using schema)
        assert(
            tokens == List(
                Token.ObjectStart("MTPerson", 2),
                Token.FieldName("name"),
                Token.Str("Alice"),
                Token.FieldName("age"),
                Token.IntVal(30),
                Token.ObjectEnd
            )
        )
    }

    // --- Default values ---

    "missing field with default" in {
        val schema = summon[Schema[CodecWithDefaults]]
        val tokens = List(
            Token.ObjectStart("CodecWithDefaults", 1),
            Token.FieldName("name"),
            Token.Str("Bob"),
            Token.ObjectEnd
        )
        val reader = new TestReader(tokens)
        val result = schema.readFrom(reader)
        assert(result == CodecWithDefaults("Bob", 25, true))
    }

    "missing required field" in {
        val schema = summon[Schema[CodecWithDefaults]]
        val tokens = List(
            Token.ObjectStart("CodecWithDefaults", 1),
            Token.FieldName("age"),
            Token.IntVal(30),
            Token.ObjectEnd
        )
        val reader = new TestReader(tokens)
        try
            schema.readFrom(reader)
            fail("Expected MissingFieldException")
        catch
            case e: MissingFieldException =>
                assert(e.fieldName == "name")
                assert(e.getMessage.contains("name"))
        end try
    }

    // --- Collection schemas ---

    "list round-trip" in {
        val schema = summon[Schema[List[Int]]]
        assert(roundTrip(List(1, 2, 3))(using schema) == List(1, 2, 3))
    }

    "list of products" in {
        val schema = summon[Schema[List[MTPerson]]]
        val list   = List(MTPerson("Alice", 30), MTPerson("Bob", 25))
        assert(roundTrip(list)(using schema) == list)
    }

    "empty list" in {
        val schema = summon[Schema[List[Int]]]
        assert(roundTrip(List.empty[Int])(using schema) == List.empty[Int])
    }

    "option some" in {
        val schema = summon[Schema[Option[Int]]]
        assert(roundTrip(Some(42): Option[Int])(using schema) == Some(42))
    }

    "option none" in {
        val schema = summon[Schema[Option[Int]]]
        assert(roundTrip(None: Option[Int])(using schema) == None)
    }

    // --- Sum type schemas (sealed trait) ---

    "sealed trait round-trip circle" in {
        val schema: Schema[MTShape] = summon[Schema[MTShape]]
        val circle: MTShape         = MTCircle(5.0)
        assert(roundTrip(circle)(using schema) == circle)
    }

    "sealed trait round-trip rect" in {
        val schema: Schema[MTShape] = summon[Schema[MTShape]]
        val rect: MTShape           = MTRectangle(3.0, 4.0)
        assert(roundTrip(rect)(using schema) == rect)
    }

    "sealed trait encode format" in {
        val schema: Schema[MTShape] = summon[Schema[MTShape]]
        val circle: MTShape         = MTCircle(5.0)
        val tokens                  = encode(circle)(using schema)
        assert(
            tokens == List(
                Token.ObjectStart("MTShape", 1),
                Token.FieldName("MTCircle"),
                Token.ObjectStart("MTCircle", 1),
                Token.FieldName("radius"),
                Token.DoubleVal(5.0),
                Token.ObjectEnd,
                Token.ObjectEnd
            )
        )
    }

    // --- Recursive types ---

    "recursive type round-trip" in {
        val schema = summon[Schema[CodecTree]]
        val tree   = CodecTree(1, List(CodecTree(2, scala.Nil), CodecTree(3, scala.Nil)))
        assert(roundTrip(tree)(using schema) == tree)
    }

    "recursive type leaf" in {
        val schema = summon[Schema[CodecTree]]
        val leaf   = CodecTree(42, scala.Nil)
        assert(roundTrip(leaf)(using schema) == leaf)
    }

    "recursive type deep" in {
        val schema = summon[Schema[CodecTree]]
        val deep   = CodecTree(1, List(CodecTree(2, List(CodecTree(3, scala.Nil)))))
        assert(roundTrip(deep)(using schema) == deep)
    }

    // --- Error handling ---

    "unknown field skipped" in {
        val schema = summon[Schema[MTPerson]]
        val tokens = List(
            Token.ObjectStart("MTPerson", 3),
            Token.FieldName("name"),
            Token.Str("Alice"),
            Token.FieldName("extra"),
            Token.Str("ignored"),
            Token.FieldName("age"),
            Token.IntVal(30),
            Token.ObjectEnd
        )
        val reader = new TestReader(tokens)
        val result = schema.readFrom(reader)
        assert(result == MTPerson("Alice", 30))
    }

    "unknown variant" in {
        val schema: Schema[MTShape] = summon[Schema[MTShape]]
        val tokens = List(
            Token.ObjectStart("MTShape", 1),
            Token.FieldName("Triangle"),
            Token.ObjectStart("Triangle", 1),
            Token.FieldName("base"),
            Token.DoubleVal(3.0),
            Token.ObjectEnd,
            Token.ObjectEnd
        )
        val reader = new TestReader(tokens)
        try
            schema.readFrom(reader)
            fail("Expected UnknownVariantException")
        catch
            case e: UnknownVariantException =>
                assert(e.variantName == "Triangle")
                assert(e.getMessage.contains("Triangle"))
        end try
    }

    // --- Extended type schemas ---

    private def jsonRoundTrip[A](value: A)(using schema: Schema[A]): A =
        val w = JsonWriter()
        schema.writeTo(value, w)
        val r = JsonReader(w.resultString)
        schema.readFrom(r)
    end jsonRoundTrip

    "bigInt codec round-trip" in {
        val value = BigInt(123456789012345L)
        assert(jsonRoundTrip(value) == value)
    }

    "bigDecimal codec round-trip" in {
        val value = BigDecimal("3.14159265358979323846")
        assert(jsonRoundTrip(value) == value)
    }

    "instant codec round-trip" in {
        val value = java.time.Instant.parse("2024-06-15T12:00:00Z")
        assert(jsonRoundTrip(value) == value)
    }

    "duration codec round-trip" in {
        val value = java.time.Duration.ofHours(2).plusMinutes(30)
        assert(jsonRoundTrip(value) == value)
    }

    "bytes codec round-trip" in {
        val value = Span.from(Array[Byte](1, 2, 3))
        val got   = jsonRoundTrip(value)
        assert(got.toArray.toSeq == value.toArray.toSeq)
    }

    "set codec round-trip" in {
        val value = Set(1, 2, 3)
        assert(jsonRoundTrip(value) == value)
    }

    "set codec empty" in {
        val value = Set.empty[Int]
        assert(jsonRoundTrip(value) == value)
    }

    "vector codec round-trip" in {
        val value = Vector("a", "b", "c")
        assert(jsonRoundTrip(value) == value)
    }

    "chunk codec round-trip" in {
        val value = Chunk(1, 2, 3)
        assert(jsonRoundTrip(value) == value)
    }

    "maybe present codec round-trip" in {
        val value = Maybe(42)
        assert(jsonRoundTrip(value) == value)
    }

    "maybe empty codec round-trip" in {
        val value = Maybe.empty[Int]
        assert(jsonRoundTrip(value) == value)
    }

    "map codec round-trip" in {
        val value = Map("a" -> 1, "b" -> 2)
        assert(jsonRoundTrip(value) == value)
    }

    "map with complex values" in {
        val value = Map("key" -> List(1, 2, 3))
        assert(jsonRoundTrip(value) == value)
    }

    "set of case class" in {
        val schema = summon[Schema[Set[MTPerson]]]
        val value  = Set(MTPerson("Alice", 30), MTPerson("Bob", 25))
        assert(jsonRoundTrip(value)(using schema) == value)
    }

    "maybe of list" in {
        val value: Maybe[List[Int]] = Maybe(List(1, 2, 3))
        assert(jsonRoundTrip(value) == value)
    }

    "nested map" in {
        val value = Map("outer" -> Map("inner" -> 1))
        assert(jsonRoundTrip(value) == value)
    }

    "chunk of option" in {
        val value = Chunk(Some(1), None, Some(3))
        assert(jsonRoundTrip(value) == value)
    }

    // --- Phase 1: metaApply gate coverage ---
    // Each test exercises Schema[CaseClassWithField] (the apply / metaApply path) which
    // invokes SerializationMacro.isSerializableType on every field type. A type missing
    // from the gate would be silently demoted to a no-serialization Schema and round-trip
    // would either fail to compile or drop the field.

    "metaApply: LocalDate field round-trip" in {
        val schema: Schema[CodecWithLocalDate] = Schema[CodecWithLocalDate]
        val value                              = CodecWithLocalDate(java.time.LocalDate.of(2024, 6, 15))
        assert(jsonRoundTrip(value)(using schema) == value)
    }

    "metaApply: LocalTime field round-trip" in {
        val schema: Schema[CodecWithLocalTime] = Schema[CodecWithLocalTime]
        val value                              = CodecWithLocalTime(java.time.LocalTime.of(12, 30, 45))
        assert(jsonRoundTrip(value)(using schema) == value)
    }

    "metaApply: LocalDateTime field round-trip" in {
        val schema: Schema[CodecWithLocalDateTime] = Schema[CodecWithLocalDateTime]
        val value                                  = CodecWithLocalDateTime(java.time.LocalDateTime.of(2024, 6, 15, 12, 30, 45))
        assert(jsonRoundTrip(value)(using schema) == value)
    }

    "metaApply: UUID field round-trip" in {
        val schema: Schema[CodecWithUUID] = Schema[CodecWithUUID]
        val value                         = CodecWithUUID(java.util.UUID.fromString("550e8400-e29b-41d4-a716-446655440000"))
        assert(jsonRoundTrip(value)(using schema) == value)
    }

    "metaApply: kyo.Instant field round-trip" in {
        val schema: Schema[CodecWithKyoInstant] = Schema[CodecWithKyoInstant]
        val value                               = CodecWithKyoInstant(kyo.Instant.fromJava(java.time.Instant.parse("2024-06-15T12:00:00Z")))
        assert(jsonRoundTrip(value)(using schema) == value)
    }

    "metaApply: kyo.Duration field round-trip" in {
        val schema: Schema[CodecWithKyoDuration] = Schema[CodecWithKyoDuration]
        val value                                = CodecWithKyoDuration(kyo.Duration.fromNanos(123456789L))
        assert(jsonRoundTrip(value)(using schema) == value)
    }

    "metaApply: kyo.Text field round-trip (non-ASCII)" in {
        val schema: Schema[CodecWithKyoText] = Schema[CodecWithKyoText]
        val value                            = CodecWithKyoText(kyo.Text("héllo wörld 日本"))
        assert(jsonRoundTrip(value)(using schema) == value)
    }

    "metaApply: Unit field round-trip" in {
        val schema: Schema[CodecWithUnit] = Schema[CodecWithUnit]
        val value                         = CodecWithUnit(())
        val decoded                       = jsonRoundTrip(value)(using schema)
        assert(decoded.u == ())
    }

    "metaApply: Seq[Int] field round-trip" in {
        val schema: Schema[CodecWithSeqInt] = Schema[CodecWithSeqInt]
        val value                           = CodecWithSeqInt(Seq(1, 2, 3))
        val decoded                         = jsonRoundTrip(value)(using schema)
        assert(decoded.xs.toList == value.xs.toList)
    }

    "metaApply: Span[Int] field round-trip" in {
        val schema: Schema[CodecWithSpanInt] = Schema[CodecWithSpanInt]
        val value                            = CodecWithSpanInt(Span.from(Array(1, 2, 3)))
        val decoded                          = jsonRoundTrip(value)(using schema)
        assert(decoded.xs.toArray.toSeq == value.xs.toArray.toSeq)
    }

    "metaApply: Either[String, Int] Right field round-trip" in {
        val schema: Schema[CodecWithEitherStringInt] = Schema[CodecWithEitherStringInt]
        val value                                    = CodecWithEitherStringInt(Right(42))
        assert(jsonRoundTrip(value)(using schema) == value)
    }

    "metaApply: Either[String, Int] Left field round-trip" in {
        val schema: Schema[CodecWithEitherStringInt] = Schema[CodecWithEitherStringInt]
        val value                                    = CodecWithEitherStringInt(Left("oops"))
        assert(jsonRoundTrip(value)(using schema) == value)
    }

    "metaApply: (Int, String, Boolean) tuple field round-trip" in {
        val schema: Schema[CodecWithTuple3] = Schema[CodecWithTuple3]
        val value                           = CodecWithTuple3((7, "hi", true))
        assert(jsonRoundTrip(value)(using schema) == value)
    }

    // --- Phase 8: generic Map[K, V] via KeyCodec, array-of-pairs fallback ---

    private def jsonEncode[A](value: A)(using schema: Schema[A]): String =
        val w = JsonWriter()
        schema.writeTo(value, w)
        w.resultString
    end jsonEncode

    "Phase 8: Map[Int, String] serialises as JSON object keyed by stringified Int" in {
        val value = Map(1 -> "a", 2 -> "b")
        val js    = jsonEncode(value)
        // Two valid orderings since Map iteration is not key-ordered.
        assert(js == """{"1":"a","2":"b"}""" || js == """{"2":"b","1":"a"}""", js)
        assert(jsonRoundTrip(value) == value)
    }

    "Phase 8: Map[Long, String] serialises as JSON object" in {
        val value = Map(1L -> "a")
        val js    = jsonEncode(value)
        assert(js == """{"1":"a"}""", js)
        assert(jsonRoundTrip(value) == value)
    }

    "Phase 8: Map[UUID, Int] serialises as JSON object keyed by UUID string" in {
        val id    = java.util.UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        val value = Map(id -> 1)
        val js    = jsonEncode(value)
        assert(js == s"""{"${id.toString}":1}""", js)
        assert(jsonRoundTrip(value) == value)
    }

    "Phase 8: Map[CaseClassKey, String] falls back to array-of-pairs encoding" in {
        val key   = CaseClassKey("a", 1)
        val value = Map(key -> "v")
        val js    = jsonEncode(value)
        // Array of two-element [key, value] arrays; key serialised as the CaseClassKey object.
        assert(js.startsWith("[["), js)
        assert(js.contains("""{"s":"a","i":1}"""), js)
        assert(js.contains("\"v\""), js)
        assert(jsonRoundTrip(value) == value)
    }

    "Phase 8: Map[(Int, Int), String] falls back to array-of-pairs (no tuple KeyCodec)" in {
        val value = Map((1, 2) -> "v")
        val js    = jsonEncode(value)
        assert(js.startsWith("[["), js)
        assert(jsonRoundTrip(value) == value)
    }

    // --- Phase 9: Shared string-transform givens ---

    "Phase 9: java.math.BigInteger round-trip" in {
        val value = new java.math.BigInteger("99999999999999999999")
        assert(jsonRoundTrip(value) == value)
    }

    "Phase 9: java.math.BigDecimal round-trip" in {
        val value = new java.math.BigDecimal("3.1415926535897932384626")
        val got   = jsonRoundTrip(value)
        assert(got == value)
        assert(got.scale() == value.scale())
        assert(got.unscaledValue() == value.unscaledValue())
    }

    "Phase 9: Symbol round-trip" in {
        val value = Symbol("hello-world")
        val got   = jsonRoundTrip(value)
        assert(got.name == value.name)
    }

    "Phase 9: Regex round-trip" in {
        val value = "a+b".r
        val got   = jsonRoundTrip(value)
        assert(got.regex == "a+b")
    }

    "Phase 9: Throwable round-trip via getMessage" in {
        val value: Throwable = new RuntimeException("boom")
        val got              = jsonRoundTrip(value)
        assert(got.getMessage == "boom")
        assert(got.isInstanceOf[RuntimeException])
    }

    "Phase 9: Try[Int] Success round-trip" in {
        val value: scala.util.Try[Int] = scala.util.Success(42)
        assert(jsonRoundTrip(value) == value)
    }

    "Phase 9: Try[Int] Failure round-trip" in {
        val value: scala.util.Try[Int] = scala.util.Failure(new RuntimeException("x"))
        val got                        = jsonRoundTrip(value)
        assert(got.isFailure)
        got match
            case scala.util.Failure(t) =>
                assert(t.isInstanceOf[RuntimeException])
                assert(t.getMessage == "x")
            case _ => fail("expected Failure")
        end match
    }

    // --- Phase 11: java.time gap closure ---

    "Phase 11: ZoneId round-trip" in {
        // Use UTC (fixed-offset) so the test runs on JS/Native without scala-java-time-tzdb.
        // IANA regional zones (e.g. America/Los_Angeles) are covered by CodecJvmTest.
        val value = java.time.ZoneId.of("UTC")
        assert(jsonRoundTrip(value) == value)
    }

    "Phase 11: ZoneOffset round-trip" in {
        val value = java.time.ZoneOffset.of("+05:30")
        assert(jsonRoundTrip(value) == value)
    }

    "Phase 11: OffsetDateTime DST spring-forward round-trip" in {
        val value = java.time.OffsetDateTime.parse("2024-03-10T02:30:00-08:00")
        assert(jsonRoundTrip(value) == value)
    }

    "Phase 11: ZonedDateTime round-trip" in {
        // Use a fixed-offset zone (UTC) so the test runs on JS/Native without
        // scala-java-time-tzdb. IANA DST edge cases are covered by CodecJvmTest.
        val value = java.time.ZonedDateTime.parse("2024-11-03T01:30:00Z[UTC]")
        val got   = jsonRoundTrip(value)
        assert(got == value)
        assert(got.getOffset == value.getOffset)
    }

    "Phase 11: Year leap-year round-trip" in {
        val value = java.time.Year.of(2024)
        val got   = jsonRoundTrip(value)
        assert(got == value)
        assert(got.isLeap)
    }

    "Phase 11: YearMonth leap-February round-trip" in {
        val value = java.time.YearMonth.of(2024, 2)
        val got   = jsonRoundTrip(value)
        assert(got == value)
        assert(got.lengthOfMonth == 29)
    }

    "Phase 11: MonthDay leap-day round-trip" in {
        val value = java.time.MonthDay.of(2, 29)
        assert(jsonRoundTrip(value) == value)
    }

    "Phase 11: Period round-trip" in {
        val value = java.time.Period.of(1, 2, 3)
        assert(jsonRoundTrip(value) == value)
    }

    // --- Phase 12: Tuple ladder Tuple1, Tuple6..Tuple22 ---

    "Phase 12: Tuple1[Int] round-trip" in {
        val value = Tuple1(42)
        assert(jsonRoundTrip(value) == value)
    }

    "Phase 12: Tuple6 mixed-primitive round-trip" in {
        val value: (Int, String, Boolean, Long, Double, Char) = (1, "two", true, 4L, 5.0, 'x')
        assert(jsonRoundTrip(value) == value)
    }

    "Phase 12: Tuple12 mixed-primitive round-trip" in {
        val value: (Int, String, Boolean, Long, Double, Char, Short, Byte, Float, Int, String, Boolean) =
            (1, "two", true, 4L, 5.0, 'x', 6.toShort, 7.toByte, 8.0f, 9, "ten", false)
        assert(jsonRoundTrip(value) == value)
    }

    "Phase 12: Tuple22 mixed-primitive round-trip" in {
        val value: (
            Int,
            String,
            Boolean,
            Long,
            Double,
            Char,
            Short,
            Byte,
            Float,
            Int,
            String,
            Boolean,
            Long,
            Double,
            Char,
            Short,
            Byte,
            Float,
            Int,
            String,
            Boolean,
            Long
        ) = (
            1,
            "two",
            true,
            4L,
            5.0,
            'x',
            6.toShort,
            7.toByte,
            8.0f,
            9,
            "ten",
            false,
            11L,
            12.0,
            'y',
            13.toShort,
            14.toByte,
            15.0f,
            16,
            "seventeen",
            true,
            18L
        )
        assert(jsonRoundTrip(value) == value)
    }

    // --- Phase 13: Array[A] and missing immutable collections ---

    "array codec round-trip" in {
        val value   = Array(1, 2, 3)
        val decoded = jsonRoundTrip(value)
        assert(decoded.toSeq == value.toSeq)
    }

    "arraySeq codec round-trip" in {
        val value = scala.collection.immutable.ArraySeq(1, 2, 3)
        assert(jsonRoundTrip(value) == value)
    }

    "queue codec round-trip" in {
        val value = scala.collection.immutable.Queue(1, 2, 3)
        assert(jsonRoundTrip(value) == value)
    }

    "sortedSet codec round-trip" in {
        val value   = scala.collection.immutable.SortedSet(3, 1, 2)
        val decoded = jsonRoundTrip(value)
        assert(decoded == scala.collection.immutable.SortedSet(1, 2, 3))
    }

    "sortedMap codec round-trip (Int key uses object encoding via KeyCodec)" in {
        val value   = scala.collection.immutable.SortedMap(2 -> "b", 1 -> "a")
        val encoded = Json.encode(value)
        // With KeyCodec[Int] in scope, encoding matches Map[Int, V]: object form keyed by `kc.encode(k)`,
        // sorted by the SortedMap's iteration order ("1" before "2") so the wire output is stable.
        assert(encoded == """{"1":"a","2":"b"}""", encoded)
        val decoded = jsonRoundTrip(value)
        assert(decoded == scala.collection.immutable.SortedMap(1 -> "a", 2 -> "b"))
        assert(decoded.toList == List(1 -> "a", 2 -> "b"))
    }

    "sortedMap codec round-trip (tuple key falls back to array-of-pairs)" in {
        // Tuple keys lack a KeyCodec[(Int, Int)] given; `sortedMapPairsSchema` (the NotGiven-gated fallback)
        // takes over and emits the array-of-pairs form. Round-trip must preserve key ordering on decode.
        val value   = scala.collection.immutable.SortedMap((2, 1) -> "b", (1, 2) -> "a")
        val encoded = Json.encode(value)
        // Array-of-pairs: outer array, each inner element is `[key, value]`. The two keys sort as
        // `(1, 2)` before `(2, 1)` via the default tuple ordering, so the wire order is deterministic.
        assert(encoded.startsWith("["), encoded)
        assert(encoded.contains("\"a\"") && encoded.contains("\"b\""), encoded)
        val decoded = jsonRoundTrip(value)
        assert(decoded == scala.collection.immutable.SortedMap((1, 2) -> "a", (2, 1) -> "b"))
        assert(decoded.toList == List((1, 2) -> "a", (2, 1) -> "b"))
    }

end CodecTest
