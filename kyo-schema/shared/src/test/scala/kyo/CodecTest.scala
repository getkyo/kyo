package kyo

import kyo.Codec.Reader
import kyo.Codec.Writer
import kyo.internal.JsonReader
import kyo.internal.JsonWriter

// --- Local test types ---

case class CodecTree(value: Int, children: List[CodecTree]) derives CanEqual

case class CodecWithDefaults(name: String, age: Int = 25, active: Boolean = true) derives CanEqual

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
        case Token.FieldName(name) => name
        case t                     => throw TypeMismatchException(scala.Nil, "FieldName", t.toString)

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

end CodecTest
