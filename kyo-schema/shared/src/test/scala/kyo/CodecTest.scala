package kyo

import kyo.Codec.Reader
import kyo.Codec.Writer
import kyo.Schema.*
import kyo.internal.JsonReader
import kyo.internal.JsonWriter
import scala.annotation.tailrec

// --- Local test types ---

case class CodecTree(value: Int, children: List[CodecTree]) derives CanEqual, Schema

case class CodecWithDefaults(name: String, age: Int = 25, active: Boolean = true) derives CanEqual

// --- Cross-format representative product: bytes, instant, duration, nested list, nested map,
// option, sum, and a renamed field, exercised through every self-describing codec ---

sealed trait CFSKind derives CanEqual, Schema
case class CFSAlpha(tag: String) extends CFSKind derives CanEqual
case class CFSBeta(count: Int)   extends CFSKind derives CanEqual

case class CFSItem(label: String, weight: Int) derives CanEqual
given cfsItemSchema: Schema[CFSItem] = Schema[CFSItem].rename("label", "item_label")

case class CFSKitchenSink(
    fullName: String,
    payload: Span[Byte],
    at: java.time.Instant,
    span: java.time.Duration,
    items: List[CFSItem],
    tags: Map[String, Int],
    nickname: Maybe[String],
    kind: CFSKind
) derives CanEqual, Schema

// --- Schema-level field transform (rename / drop / denyUnknownFields) directly alongside a
// Map[String, V] field on the SAME schema, exercised through multiple binary and self-describing
// codecs. A field transform must never disturb an unrelated Map field's own entry keys. ---

case class TransformMapRename(fullName: String, tags: Map[String, Int]) derives CanEqual
given transformMapRenameSchema: Schema[TransformMapRename] = Schema[TransformMapRename].rename("fullName", "full_name")

case class TransformMapDrop(fullName: String, secret: String, tags: Map[String, Int]) derives CanEqual
given transformMapDropSchema: Schema[TransformMapDrop] = Schema[TransformMapDrop].drop("secret")

case class TransformMapDeny(fullName: String, tags: Map[String, Int]) derives CanEqual
given transformMapDenySchema: Schema[TransformMapDeny] = Schema[TransformMapDeny].denyUnknownFields

// --- The same field-transform-plus-Map regression, on a richer MIXED product (a scalar, an
// optional scalar, and a list, alongside the Map field) so the transform-aware guard alignment
// is proven on a shape closer to a real-world record, not only the minimal two-field case. ---

case class TransformMixedRename(fullName: String, count: Int, nickname: Maybe[Int], scores: List[Int], tags: Map[String, Int])
    derives CanEqual
given transformMixedRenameSchema: Schema[TransformMixedRename] = Schema[TransformMixedRename].rename("fullName", "full_name")

case class TransformMixedDrop(
    fullName: String,
    secret: String,
    count: Int,
    nickname: Maybe[Int],
    scores: List[Int],
    tags: Map[String, Int]
) derives CanEqual
given transformMixedDropSchema: Schema[TransformMixedDrop] = Schema[TransformMixedDrop].drop("secret")

case class TransformMixedDeny(fullName: String, count: Int, nickname: Maybe[Int], scores: List[Int], tags: Map[String, Int])
    derives CanEqual
given transformMixedDenySchema: Schema[TransformMixedDeny] = Schema[TransformMixedDeny].denyUnknownFields

// --- A schema carrying ONLY an explicit Schema.fieldId pin: no rename, no drop, no computed
// field, no discriminator, so hasTransforms is false and the schema routes through the raw
// macro-generated writer/reader at every entry point except the specialized Protobuf.encode/decode
// helpers, which thread the pin directly onto the writer/reader themselves. ---

case class PinOnlyPerson(name: String, age: Int) derives CanEqual
given pinOnlyPersonSchema: Schema[PinOnlyPerson] = Schema[PinOnlyPerson].fieldId(_.age)(42)

// --- Nested field-id pins: the pin lives on a NESTED schema, embedded as a product field or inside a
// container element, rather than on the schema passed to encode/decode directly. Neither the
// outer product nor the container carries a transform of its own. ---

case class PinOnlyInner(x: Int, y: String) derives CanEqual
given pinOnlyInnerSchema: Schema[PinOnlyInner] = Schema[PinOnlyInner].fieldId(_.x)(77)

case class PinOnlyOuter(label: String, inner: PinOnlyInner) derives CanEqual, Schema

case class PinOnlyListHolder(items: List[PinOnlyInner]) derives CanEqual, Schema

case class PinOnlyMapHolder(entries: Map[String, PinOnlyInner]) derives CanEqual, Schema

// --- Nested pin and rename composition: a NESTED schema carrying BOTH its own field-id pin and its
// own rename, so the nested schema itself carries a structural transform and reaches the writer
// via transformedWrite/writeWithTransforms rather than the raw macro-generated writer. The
// nested pin must resolve identically to the pin-only case above, verifying the two nested
// threading sites (Schema.init's serializeWrite and writeWithTransforms's own thread call)
// compose without conflict. ---

case class PinAndRenameInner(x: Int, y: String) derives CanEqual
given pinAndRenameInnerSchema: Schema[PinAndRenameInner] =
    Schema[PinAndRenameInner].fieldId(_.x)(88).rename("y", "y_renamed")

case class PinAndRenameOuter(label: String, inner: PinAndRenameInner) derives CanEqual, Schema

// --- Nested-pin ordering: the nested-pinned schema field is NOT the outer's last field, and the
// outer schema ALSO carries its own field-id pin on a field written AFTER the nested one. The
// Protobuf writer/reader field-id override state must be scoped per nested serializeWrite/
// serializeRead call (save/restore), not a single var that a nested schema's pin permanently
// replaces with no restore on return: otherwise the outer's own later pin is lost to the nested
// schema's override map for the rest of the outer write. ---

case class PinOrderInner(x: Int, y: String) derives CanEqual
given pinOrderInnerSchema: Schema[PinOrderInner] = Schema[PinOrderInner].fieldId(_.x)(501)

case class PinOrderOuter(first: String, inner: PinOrderInner, last: Int) derives CanEqual
given pinOrderOuterSchema: Schema[PinOrderOuter] = Schema[PinOrderOuter].fieldId(_.last)(777)

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

class TestReader(tokens: List[Token])(using _frame: Frame) extends Codec.IntrospectingReader:
    // Reads a token list handed to it, so there is no input stream for anything to be left in.
    private[kyo] def requireEndOfInput(): Unit = ()

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

    override def readStructure(): Structure.Value =
        peek() match
            case _: Token.ObjectStart =>
                discard(objectStart())
                val acc = scala.collection.mutable.ArrayBuffer.empty[(String, Structure.Value)]
                while hasNextField() do
                    val name  = field()
                    val value = readStructure()
                    discard(acc.addOne((name, value)))
                end while
                objectEnd()
                Structure.Value.Record(Chunk.from(acc.toSeq))
            case _: Token.ArrayStart =>
                discard(arrayStart())
                val acc = scala.collection.mutable.ArrayBuffer.empty[Structure.Value]
                while hasNextElement() do
                    discard(acc.addOne(readStructure()))
                arrayEnd()
                Structure.Value.Sequence(Chunk.from(acc.toSeq))
            case _: Token.MapStart =>
                // writeStructureValue emits string-keyed MapEntries via writer.fieldBytes (a FieldName token), not as a
                // separate value-token. Read each entry as `field() -> readStructure()` so the symmetric round-trip
                // reconstructs a MapEntries with Str keys.
                discard(mapStart())
                val acc = scala.collection.mutable.ArrayBuffer.empty[(Structure.Value, Structure.Value)]
                while hasNextEntry() do
                    val k = field()
                    val v = readStructure()
                    discard(acc.addOne((Structure.Value.Str(k), v)))
                end while
                mapEnd()
                Structure.Value.MapEntries(Chunk.from(acc.toSeq))
            case _: Token.Str           => Structure.Value.Str(string())
            case _: Token.IntVal        => Structure.Value.Integer(int().toLong)
            case _: Token.LongVal       => Structure.Value.Integer(long())
            case _: Token.FloatVal      => Structure.Value.Decimal(float().toDouble)
            case _: Token.DoubleVal     => Structure.Value.Decimal(double())
            case _: Token.BoolVal       => Structure.Value.Bool(boolean())
            case _: Token.ShortVal      => Structure.Value.Integer(short().toLong)
            case _: Token.ByteVal       => Structure.Value.Integer(byte().toLong)
            case _: Token.CharVal       => Structure.Value.Str(char().toString)
            case Token.Nil              => discard(isNil()); Structure.Value.Null
            case _: Token.BigIntVal     => Structure.Value.BigNum(BigDecimal(bigInt()))
            case _: Token.BigDecimalVal => Structure.Value.BigNum(bigDecimal())
            case _: Token.Bytes         => Structure.Value.Bytes(bytes())
            case _: Token.InstantVal    => Structure.Value.Instant(instant())
            case _: Token.DurationVal   => Structure.Value.Duration(duration())
            case other                  => throw TypeMismatchException(scala.Nil, "value-shaped token", other.toString)
    end readStructure

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

class CodecTest extends kyo.test.Test[Any]:

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
        assert(CodecTestSupport.sameBytes(got, value))
    }

    "writeStructureValue writes bytes event" in {
        val value  = Span.from(Array[Byte](1, 2, 3))
        val writer = new TestWriter
        Schema.writeStructureValue(writer, Structure.Value.Bytes(value))
        assert(writer.resultTokens == List(Token.Bytes(value)))
    }

    "writeStructureValue writes instant event" in {
        val value  = java.time.Instant.parse("2024-06-15T12:00:00Z")
        val writer = new TestWriter
        Schema.writeStructureValue(writer, Structure.Value.Instant(value))
        assert(writer.resultTokens == List(Token.InstantVal(value)))
    }

    "writeStructureValue writes duration event" in {
        val value  = java.time.Duration.ofHours(2).plusMinutes(30)
        val writer = new TestWriter
        Schema.writeStructureValue(writer, Structure.Value.Duration(value))
        assert(writer.resultTokens == List(Token.DurationVal(value)))
    }

    "readStructure preserves typed base events" in {
        val bytes    = Span.from(Array[Byte](1, 2, 3))
        val instant  = java.time.Instant.parse("2024-06-15T12:00:00Z")
        val duration = java.time.Duration.ofHours(2).plusMinutes(30)
        val tokens = List(
            Token.ObjectStart("", 3),
            Token.FieldName("bytes"),
            Token.Bytes(bytes),
            Token.FieldName("instant"),
            Token.InstantVal(instant),
            Token.FieldName("duration"),
            Token.DurationVal(duration),
            Token.ObjectEnd
        )
        val reader = new TestReader(tokens)
        val value  = reader.readStructure()
        val expected = Structure.Value.Record(Chunk(
            "bytes"    -> Structure.Value.Bytes(bytes),
            "instant"  -> Structure.Value.Instant(instant),
            "duration" -> Structure.Value.Duration(duration)
        ))
        assert(value == expected)
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

    // =========================================================================
    // Codec.Capabilities projection
    // =========================================================================

    "capable writer projects Capabilities(true)" in {
        val writer = new Codec.Writer:
            override def canWriteTopLevelNonObject: Boolean            = true
            def objectStart(name: String, size: Int): Unit             = ()
            def objectEnd(): Unit                                      = ()
            def arrayStart(size: Int): Unit                            = ()
            def arrayEnd(): Unit                                       = ()
            def fieldBytes(nameBytes: Array[Byte], fieldId: Int): Unit = ()
            def string(value: String): Unit                            = ()
            def int(value: Int): Unit                                  = ()
            def long(value: Long): Unit                                = ()
            def float(value: Float): Unit                              = ()
            def double(value: Double): Unit                            = ()
            def boolean(value: Boolean): Unit                          = ()
            def short(value: Short): Unit                              = ()
            def byte(value: Byte): Unit                                = ()
            def char(value: Char): Unit                                = ()
            def nil(): Unit                                            = ()
            def mapStart(size: Int): Unit                              = ()
            def mapEnd(): Unit                                         = ()
            def bytes(value: Span[Byte]): Unit                         = ()
            def bigInt(value: BigInt): Unit                            = ()
            def bigDecimal(value: BigDecimal): Unit                    = ()
            def instant(value: java.time.Instant): Unit                = ()
            def duration(value: java.time.Duration): Unit              = ()
            def result(): Span[Byte]                                   = Span.empty
        end writer
        assert(writer.capabilities == Codec.Capabilities(true))
    }

    "default writer projects Capabilities(false)" in {
        val writer = new Codec.Writer:
            def objectStart(name: String, size: Int): Unit             = ()
            def objectEnd(): Unit                                      = ()
            def arrayStart(size: Int): Unit                            = ()
            def arrayEnd(): Unit                                       = ()
            def fieldBytes(nameBytes: Array[Byte], fieldId: Int): Unit = ()
            def string(value: String): Unit                            = ()
            def int(value: Int): Unit                                  = ()
            def long(value: Long): Unit                                = ()
            def float(value: Float): Unit                              = ()
            def double(value: Double): Unit                            = ()
            def boolean(value: Boolean): Unit                          = ()
            def short(value: Short): Unit                              = ()
            def byte(value: Byte): Unit                                = ()
            def char(value: Char): Unit                                = ()
            def nil(): Unit                                            = ()
            def mapStart(size: Int): Unit                              = ()
            def mapEnd(): Unit                                         = ()
            def bytes(value: Span[Byte]): Unit                         = ()
            def bigInt(value: BigInt): Unit                            = ()
            def bigDecimal(value: BigDecimal): Unit                    = ()
            def instant(value: java.time.Instant): Unit                = ()
            def duration(value: java.time.Duration): Unit              = ()
            def result(): Span[Byte]                                   = Span.empty
        end writer
        assert(writer.capabilities == Codec.Capabilities(false))
    }

    // --- Cross-format kitchen sink round trip ---

    private val kitchenSinkValue = CFSKitchenSink(
        fullName = "Alice Example",
        payload = Span.from(Array[Byte](1, 2, 3)),
        at = java.time.Instant.parse("2026-07-09T12:00:00.500Z"),
        span = java.time.Duration.ofSeconds(12, 345),
        items = List(CFSItem("first", 1), CFSItem("second", 2)),
        tags = Map("a" -> 1, "b" -> 2),
        nickname = Present("Ally"),
        kind = CFSAlpha("primary")
    )

    private def sameKitchenSink(a: CFSKitchenSink, b: CFSKitchenSink): Boolean =
        a.fullName == b.fullName &&
            CodecTestSupport.sameBytes(a.payload, b.payload) &&
            a.at == b.at &&
            a.span == b.span &&
            a.items == b.items &&
            a.tags == b.tags &&
            a.nickname == b.nickname &&
            a.kind == b.kind

    "kitchen sink product round trips through StructureValue identity" in {
        val schema  = summon[Schema[CFSKitchenSink]]
        val dynVal  = Structure.encode(kitchenSinkValue)(using schema)
        val decoded = Structure.decode(dynVal)(using schema)
        assert(sameKitchenSink(decoded.getOrThrow, kitchenSinkValue))
    }

    // One assertion per self-describing codec, table-driven so a new codec is one row, not a
    // copy-pasted block. StructureValue stays separate above: it round trips through
    // Structure.encode/decode, not the schema.encode[C]/decode[C] path these share.
    for (codecName, decode) <- List[(String, () => CFSKitchenSink)](
            "Ion text"   -> (() => CodecTestSupport.roundTrip[CFSKitchenSink, Ion](kitchenSinkValue)),
            "Ion Binary" -> (() => CodecTestSupport.roundTrip[CFSKitchenSink, IonBinary](kitchenSinkValue)),
            "BSON"       -> (() => CodecTestSupport.roundTrip[CFSKitchenSink, Bson](kitchenSinkValue)),
            "MsgPack"    -> (() => CodecTestSupport.roundTrip[CFSKitchenSink, MsgPack](kitchenSinkValue)),
            "JSON"       -> (() => CodecTestSupport.roundTrip[CFSKitchenSink, Json](kitchenSinkValue)),
            "YAML"       -> (() => CodecTestSupport.roundTrip[CFSKitchenSink, Yaml](kitchenSinkValue))
        )
    do
        s"kitchen sink product round trips through $codecName" in {
            assert(sameKitchenSink(decode(), kitchenSinkValue))
        }
    end for

    // --- Field transform + Map field regression matrix -------------------------------------

    private val transformMapRenameValue = TransformMapRename("Alice", Map("a" -> 1, "b" -> 2, "c" -> 3))

    // JSON/MsgPack/BSON share the same helper-backed matrix as the Yaml/Ion/Protobuf rows below;
    // assertRenameOrDenyRoundTrip and assertDropRoundTripSurvives are defined later in the class.
    "schema-level rename alongside a Map field decodes the Map correctly through JSON" in {
        assertRenameOrDenyRoundTrip[TransformMapRename, Json](transformMapRenameValue)
    }

    "schema-level rename alongside a Map field decodes the Map correctly through MsgPack" in {
        assertRenameOrDenyRoundTrip[TransformMapRename, MsgPack](transformMapRenameValue)
    }

    "schema-level rename alongside a Map field decodes the Map correctly through BSON" in {
        assertRenameOrDenyRoundTrip[TransformMapRename, Bson](transformMapRenameValue)
    }

    private val transformMapDropValue = TransformMapDrop("Bob", "shh", Map("x" -> 10, "y" -> 20, "z" -> 30))

    private def assertDropMapPreserved(decoded: TransformMapDrop)(using kyo.test.AssertScope): Unit =
        assert(decoded.fullName == "Bob", s"fullName survives drop: $decoded")
        assert(decoded.tags == Map("x" -> 10, "y" -> 20, "z" -> 30), s"Map field must decode uncorrupted: $decoded")
    end assertDropMapPreserved

    "schema-level drop alongside a Map field decodes the Map correctly through JSON" in {
        assertDropRoundTripSurvives[TransformMapDrop, Json](transformMapDropValue)(assertDropMapPreserved)
    }

    "schema-level drop alongside a Map field decodes the Map correctly through MsgPack" in {
        assertDropRoundTripSurvives[TransformMapDrop, MsgPack](transformMapDropValue)(assertDropMapPreserved)
    }

    "schema-level drop alongside a Map field decodes the Map correctly through BSON" in {
        assertDropRoundTripSurvives[TransformMapDrop, Bson](transformMapDropValue)(assertDropMapPreserved)
    }

    private val transformMapDenyValue = TransformMapDeny("Carol", Map("p" -> 1, "q" -> 2))

    "schema-level denyUnknownFields alongside a Map field decodes the Map correctly through JSON" in {
        assertRenameOrDenyRoundTrip[TransformMapDeny, Json](transformMapDenyValue)
    }

    "schema-level denyUnknownFields alongside a Map field decodes the Map correctly through MsgPack" in {
        assertRenameOrDenyRoundTrip[TransformMapDeny, MsgPack](transformMapDenyValue)
    }

    "schema-level denyUnknownFields alongside a Map field decodes the Map correctly through BSON" in {
        assertRenameOrDenyRoundTrip[TransformMapDeny, Bson](transformMapDenyValue)
    }

    // --- Field transform + Map/mixed product regression matrix, extended to every representable
    // codec (Yaml, Ion text, Ion Binary, alongside the JSON/MsgPack/BSON matrix above) and to a
    // richer mixed-field product, so the transform-aware Map guard alignment is proven
    // systematically rather than on three codecs and one minimal shape. Protobuf covers
    // rename and drop alongside a Map field too: a schema with any field transform (rename, drop,
    // computed, discriminator) writes through the generic Structure.Value replay path, which must
    // tell a genuine Map field apart from an ordinary object field so Protobuf encodes it as a
    // repeated MapEntry rather than a single nested message. ---

    private def assertRenameOrDenyRoundTrip[A, C <: Codec](value: A)(using
        schema: Schema[A],
        codec: C,
        ce: CanEqual[A, A],
        scope: kyo.test.AssertScope
    ): Unit =
        val decoded = schema.decode[C](schema.encode[C](value))
        assert(decoded == Result.Success(value), s"round trip: $decoded")
    end assertRenameOrDenyRoundTrip

    private def assertDropRoundTripSurvives[A, C <: Codec](value: A)(using
        schema: Schema[A],
        codec: C,
        scope: kyo.test.AssertScope
    )(
        check: A => Unit
    ): Unit =
        val decoded = schema.decode[C](schema.encode[C](value)).getOrThrow
        check(decoded)
    end assertDropRoundTripSurvives

    "schema-level rename alongside a Map field decodes the Map correctly through Yaml" in {
        assertRenameOrDenyRoundTrip[TransformMapRename, Yaml](transformMapRenameValue)
    }

    "schema-level rename alongside a Map field decodes the Map correctly through Ion text" in {
        assertRenameOrDenyRoundTrip[TransformMapRename, Ion](transformMapRenameValue)
    }

    "schema-level rename alongside a Map field decodes the Map correctly through Ion Binary" in {
        assertRenameOrDenyRoundTrip[TransformMapRename, IonBinary](transformMapRenameValue)
    }

    "schema-level drop alongside a Map field decodes the Map correctly through Yaml" in {
        assertDropRoundTripSurvives[TransformMapDrop, Yaml](transformMapDropValue) { decoded =>
            assert(decoded.fullName == "Bob", s"fullName survives drop: $decoded")
            assert(decoded.tags == Map("x" -> 10, "y" -> 20, "z" -> 30), s"Map field must decode uncorrupted: $decoded")
        }
    }

    "schema-level drop alongside a Map field decodes the Map correctly through Ion text" in {
        assertDropRoundTripSurvives[TransformMapDrop, Ion](transformMapDropValue) { decoded =>
            assert(decoded.fullName == "Bob", s"fullName survives drop: $decoded")
            assert(decoded.tags == Map("x" -> 10, "y" -> 20, "z" -> 30), s"Map field must decode uncorrupted: $decoded")
        }
    }

    "schema-level drop alongside a Map field decodes the Map correctly through Ion Binary" in {
        assertDropRoundTripSurvives[TransformMapDrop, IonBinary](transformMapDropValue) { decoded =>
            assert(decoded.fullName == "Bob", s"fullName survives drop: $decoded")
            assert(decoded.tags == Map("x" -> 10, "y" -> 20, "z" -> 30), s"Map field must decode uncorrupted: $decoded")
        }
    }

    "schema-level denyUnknownFields alongside a Map field decodes the Map correctly through Yaml" in {
        assertRenameOrDenyRoundTrip[TransformMapDeny, Yaml](transformMapDenyValue)
    }

    "schema-level denyUnknownFields alongside a Map field decodes the Map correctly through Protobuf" in {
        assertRenameOrDenyRoundTrip[TransformMapDeny, Protobuf](transformMapDenyValue)
    }

    "schema-level rename alongside a Map field decodes the Map correctly through Protobuf" in {
        assertRenameOrDenyRoundTrip[TransformMapRename, Protobuf](transformMapRenameValue)
    }

    "schema-level drop alongside a Map field decodes the Map correctly through Protobuf" in {
        assertDropRoundTripSurvives[TransformMapDrop, Protobuf](transformMapDropValue) { decoded =>
            assert(decoded.fullName == "Bob", s"fullName survives drop: $decoded")
            assert(decoded.tags == Map("x" -> 10, "y" -> 20, "z" -> 30), s"Map field must decode uncorrupted: $decoded")
        }
    }

    "schema-level rename alongside a Map field round trips through the specialized Protobuf.encode/decode entry points" in {
        val bytes   = Protobuf.encode[TransformMapRename](transformMapRenameValue)
        val decoded = Protobuf.decode[TransformMapRename](bytes)
        assert(decoded == Result.Success(transformMapRenameValue), s"round trip: $decoded")
    }

    // --- A field-id pin alone (no rename/drop) must thread identically through the generic
    // Schema.encode[C]/decode[C] entry point and the specialized Protobuf.encode/decode entry
    // point, since a pin-only schema has no structural transform and never reaches
    // writeWithTransforms/readWithTransforms. ---

    "a field-id pin alone round trips: generic encode, specialized decode" in {
        val bytes   = pinOnlyPersonSchema.encode[Protobuf](PinOnlyPerson("Alice", 30))
        val decoded = Protobuf.decode[PinOnlyPerson](bytes)
        assert(decoded == Result.Success(PinOnlyPerson("Alice", 30)), s"generic encode, specialized decode: $decoded")
    }

    "a field-id pin alone round trips: specialized encode, generic decode" in {
        val bytes   = Protobuf.encode[PinOnlyPerson](PinOnlyPerson("Bob", 25))
        val decoded = pinOnlyPersonSchema.decode[Protobuf](bytes)
        assert(decoded == Result.Success(PinOnlyPerson("Bob", 25)), s"specialized encode, generic decode: $decoded")
    }

    "a field-id pin alone: generic encode produces the same wire bytes as the specialized encode entry point" in {
        val value        = PinOnlyPerson("Carol", 42)
        val genericBytes = pinOnlyPersonSchema.encode[Protobuf](value)
        val specialBytes = Protobuf.encode[PinOnlyPerson](value)
        assert(
            CodecTestSupport.sameBytes(genericBytes, specialBytes),
            s"generic encode must write the pinned field id identically to the specialized entry point: " +
                s"generic=${genericBytes.toArray.toSeq} specialized=${specialBytes.toArray.toSeq}"
        )
        val decoded = pinOnlyPersonSchema.decode[Protobuf](pinOnlyPersonSchema.encode[Protobuf](value))
        assert(decoded == Result.Success(value), s"generic encode, generic decode: $decoded")
    }

    // --- Nested field-id pins: a field-id pin declared on a NESTED schema (a product field's own schema, or a
    // container element's own schema), rather than on the schema passed to encode/decode. A
    // wire-functional pin must produce the same field number the pinned schema emits when encoded
    // standalone, at every nesting depth: the outer message's embedded sub-message bytes for the
    // pinned schema must be byte-identical to that schema's own standalone encoding. ---

    "a field-id pin on a nested product field is honored through the outer product: generic encode" in {
        val innerValue      = PinOnlyInner(5, "z")
        val standaloneInner = pinOnlyInnerSchema.encode[Protobuf](innerValue)
        val outerValue      = PinOnlyOuter("A", innerValue)
        val genericBytes    = summon[Schema[PinOnlyOuter]].encode[Protobuf](outerValue)
        assert(
            genericBytes.toArray.toSeq.containsSlice(standaloneInner.toArray.toSeq),
            s"nested pin must match the standalone-pinned encoding: outer=${genericBytes.toArray.toSeq} inner=${standaloneInner.toArray.toSeq}"
        )
    }

    "a field-id pin on a nested product field is honored through the outer product: specialized Protobuf.encode" in {
        val innerValue      = PinOnlyInner(5, "z")
        val standaloneInner = pinOnlyInnerSchema.encode[Protobuf](innerValue)
        val outerValue      = PinOnlyOuter("A", innerValue)
        val specialBytes    = Protobuf.encode[PinOnlyOuter](outerValue)
        assert(
            specialBytes.toArray.toSeq.containsSlice(standaloneInner.toArray.toSeq),
            s"nested pin must match the standalone-pinned encoding: outer=${specialBytes.toArray.toSeq} inner=${standaloneInner.toArray.toSeq}"
        )
    }

    "a field-id pin on a nested product field round trips consistently across the generic and specialized entry points" in {
        val outerValue       = PinOnlyOuter("A", PinOnlyInner(5, "z"))
        val genericBytes     = summon[Schema[PinOnlyOuter]].encode[Protobuf](outerValue)
        val specializedBytes = Protobuf.encode[PinOnlyOuter](outerValue)
        assert(
            CodecTestSupport.sameBytes(genericBytes, specializedBytes),
            s"generic and specialized encode must agree: generic=${genericBytes.toArray.toSeq} specialized=${specializedBytes.toArray.toSeq}"
        )
        val genericDecoded = summon[Schema[PinOnlyOuter]].decode[Protobuf](genericBytes)
        assert(genericDecoded == Result.Success(outerValue), s"generic decode: $genericDecoded")
        val crossDecoded = Protobuf.decode[PinOnlyOuter](genericBytes)
        assert(crossDecoded == Result.Success(outerValue), s"specialized decode of generic-encoded bytes: $crossDecoded")
    }

    "a field-id pin on a container element schema is honored inside a List element: generic encode" in {
        val innerValue      = PinOnlyInner(9, "w")
        val standaloneInner = pinOnlyInnerSchema.encode[Protobuf](innerValue)
        val holderValue     = PinOnlyListHolder(List(innerValue))
        val genericBytes    = summon[Schema[PinOnlyListHolder]].encode[Protobuf](holderValue)
        assert(
            genericBytes.toArray.toSeq.containsSlice(standaloneInner.toArray.toSeq),
            s"nested pin inside a List element must match the standalone-pinned encoding: holder=${genericBytes.toArray.toSeq} inner=${standaloneInner.toArray.toSeq}"
        )
    }

    "a field-id pin on a container element schema is honored inside a Map value: generic encode" in {
        val innerValue      = PinOnlyInner(11, "v")
        val standaloneInner = pinOnlyInnerSchema.encode[Protobuf](innerValue)
        val holderValue     = PinOnlyMapHolder(Map("k" -> innerValue))
        val genericBytes    = summon[Schema[PinOnlyMapHolder]].encode[Protobuf](holderValue)
        assert(
            genericBytes.toArray.toSeq.containsSlice(standaloneInner.toArray.toSeq),
            s"nested pin inside a Map value must match the standalone-pinned encoding: holder=${genericBytes.toArray.toSeq} inner=${standaloneInner.toArray.toSeq}"
        )
    }

    "a container-nested field-id pin round trips consistently across the generic and specialized entry points" in {
        val holderValue      = PinOnlyListHolder(List(PinOnlyInner(9, "w")))
        val genericBytes     = summon[Schema[PinOnlyListHolder]].encode[Protobuf](holderValue)
        val specializedBytes = Protobuf.encode[PinOnlyListHolder](holderValue)
        assert(
            CodecTestSupport.sameBytes(genericBytes, specializedBytes),
            s"generic and specialized encode must agree: generic=${genericBytes.toArray.toSeq} specialized=${specializedBytes.toArray.toSeq}"
        )
        val decoded = Protobuf.decode[PinOnlyListHolder](genericBytes)
        assert(decoded == Result.Success(holderValue), s"round trip: $decoded")
    }

    "a field-id pin on a nested schema that also carries its own rename is honored through the outer product" in {
        val innerValue      = PinAndRenameInner(13, "q")
        val standaloneInner = pinAndRenameInnerSchema.encode[Protobuf](innerValue)
        val outerValue      = PinAndRenameOuter("A", innerValue)
        val genericBytes    = summon[Schema[PinAndRenameOuter]].encode[Protobuf](outerValue)
        assert(
            genericBytes.toArray.toSeq.containsSlice(standaloneInner.toArray.toSeq),
            s"nested pin+rename must match the standalone-pinned encoding: outer=${genericBytes.toArray.toSeq} inner=${standaloneInner.toArray.toSeq}"
        )
        val specializedBytes = Protobuf.encode[PinAndRenameOuter](outerValue)
        assert(
            CodecTestSupport.sameBytes(genericBytes, specializedBytes),
            s"generic and specialized encode must agree: generic=${genericBytes.toArray.toSeq} specialized=${specializedBytes.toArray.toSeq}"
        )
        val decoded = summon[Schema[PinAndRenameOuter]].decode[Protobuf](genericBytes)
        assert(decoded == Result.Success(outerValue), s"round trip: $decoded")
    }

    // --- Nested-pin ordering: a nested schema's own field-id pin must not clobber the OUTER
    // schema's own pin on a field written after the nested one. `topLevelFieldNumbers` scans only
    // the outer message's own tags, skipping every nested/length-delimited payload by its length
    // prefix, so the assertion targets the wire number the OUTER field was actually written under,
    // independent of what the nested message's own field numbers happen to be. ---

    private def readVarintAt(data: Array[Byte], start: Int): (Long, Int) =
        @tailrec def loop(pos: Int, result: Long, shift: Int): (Long, Int) =
            val b       = data(pos) & 0xff
            val updated = result | ((b & 0x7f).toLong << shift)
            if (b & 0x80) != 0 then loop(pos + 1, updated, shift + 7)
            else (updated, pos + 1)
        end loop
        loop(start, 0L, 0)
    end readVarintAt

    private def topLevelFieldNumbers(bytes: Span[Byte]): Chunk[Int] =
        val data = bytes.toArray
        @tailrec def loop(pos: Int, acc: Chunk[Int]): Chunk[Int] =
            if pos >= data.length then acc
            else
                val (tag, afterTag) = readVarintAt(data, pos)
                val fieldNumber     = (tag >>> 3).toInt
                (tag & 0x7).toInt match
                    case 0 =>
                        val (_, afterValue) = readVarintAt(data, afterTag)
                        loop(afterValue, acc.append(fieldNumber))
                    case 1 => loop(afterTag + 8, acc.append(fieldNumber))
                    case 2 =>
                        val (len, afterLen) = readVarintAt(data, afterTag)
                        loop(afterLen + len.toInt, acc.append(fieldNumber))
                    case 5 => loop(afterTag + 4, acc.append(fieldNumber))
                    case _ => acc
                end match
        loop(0, Chunk.empty)
    end topLevelFieldNumbers

    "a nested-pinned field that is not the outer's last field must not clobber the outer's own later field-id pin: generic and specialized encode agree and the outer field lands on its own pin" in {
        val value            = PinOrderOuter("A", PinOrderInner(5, "z"), 999)
        val genericBytes     = pinOrderOuterSchema.encode[Protobuf](value)
        val specializedBytes = Protobuf.encode[PinOrderOuter](value)
        assert(
            CodecTestSupport.sameBytes(genericBytes, specializedBytes),
            s"generic and specialized encode must agree: generic=${genericBytes.toArray.toSeq} specialized=${specializedBytes.toArray.toSeq}"
        )
        val topFields   = topLevelFieldNumbers(genericBytes)
        val defaultLast = kyo.internal.CodecMacro.fieldId("last")
        assert(topFields.size == 3, s"outer product has 3 top-level fields: $topFields")
        assert(
            topFields(2) == 777,
            s"outer's own pin on 'last' must survive a nested pinned field written earlier: " +
                s"wire field numbers=$topFields expected last=777 (unpinned default would be $defaultLast)"
        )
    }

    "a nested-pinned field that is not the outer's last field round trips consistently across the generic and specialized entry points" in {
        val value            = PinOrderOuter("B", PinOrderInner(6, "w"), 1000)
        val genericBytes     = pinOrderOuterSchema.encode[Protobuf](value)
        val specializedBytes = Protobuf.encode[PinOrderOuter](value)
        val genericDecoded   = pinOrderOuterSchema.decode[Protobuf](specializedBytes)
        assert(genericDecoded == Result.Success(value), s"generic decode of specialized-encoded bytes: $genericDecoded")
        val specializedDecoded = Protobuf.decode[PinOrderOuter](genericBytes)
        assert(specializedDecoded == Result.Success(value), s"specialized decode of generic-encoded bytes: $specializedDecoded")
    }

    "schema-level denyUnknownFields alongside a Map field decodes the Map correctly through Ion text" in {
        assertRenameOrDenyRoundTrip[TransformMapDeny, Ion](transformMapDenyValue)
    }

    "schema-level denyUnknownFields alongside a Map field decodes the Map correctly through Ion Binary" in {
        assertRenameOrDenyRoundTrip[TransformMapDeny, IonBinary](transformMapDenyValue)
    }

    // --- Same matrix again, on the richer mixed-field product (scalar + optional + list + Map) ---

    private val transformMixedRenameValue =
        TransformMixedRename("Alice", 7, Present(3), List(1, 2, 3), Map("a" -> 1, "b" -> 2, "c" -> 3))
    private val transformMixedDropValue =
        TransformMixedDrop("Bob", "shh", 9, Absent, List(4, 5), Map("x" -> 10, "y" -> 20, "z" -> 30))
    private val transformMixedDenyValue =
        TransformMixedDeny("Carol", 3, Present(1), List(6), Map("p" -> 1, "q" -> 2))

    private def checkMixedDropSurvivors(decoded: TransformMixedDrop)(using kyo.test.AssertScope): Unit =
        assert(decoded.fullName == "Bob", s"fullName survives drop: $decoded")
        assert(decoded.count == 9, s"count survives drop: $decoded")
        assert(decoded.scores == List(4, 5), s"scores survive drop: $decoded")
        assert(decoded.tags == Map("x" -> 10, "y" -> 20, "z" -> 30), s"Map field must decode uncorrupted: $decoded")
    end checkMixedDropSurvivors

    "schema-level rename alongside a mixed product decodes correctly through JSON" in {
        assertRenameOrDenyRoundTrip[TransformMixedRename, Json](transformMixedRenameValue)
    }

    "schema-level rename alongside a mixed product decodes correctly through Yaml" in {
        assertRenameOrDenyRoundTrip[TransformMixedRename, Yaml](transformMixedRenameValue)
    }

    "schema-level rename alongside a mixed product decodes correctly through MsgPack" in {
        assertRenameOrDenyRoundTrip[TransformMixedRename, MsgPack](transformMixedRenameValue)
    }

    "schema-level rename alongside a mixed product decodes correctly through BSON" in {
        assertRenameOrDenyRoundTrip[TransformMixedRename, Bson](transformMixedRenameValue)
    }

    "schema-level rename alongside a mixed product decodes correctly through Ion text" in {
        assertRenameOrDenyRoundTrip[TransformMixedRename, Ion](transformMixedRenameValue)
    }

    "schema-level rename alongside a mixed product decodes correctly through Ion Binary" in {
        assertRenameOrDenyRoundTrip[TransformMixedRename, IonBinary](transformMixedRenameValue)
    }

    "schema-level rename alongside a mixed product decodes correctly through Protobuf" in {
        assertRenameOrDenyRoundTrip[TransformMixedRename, Protobuf](transformMixedRenameValue)
    }

    "schema-level drop alongside a mixed product decodes correctly through JSON" in {
        assertDropRoundTripSurvives[TransformMixedDrop, Json](transformMixedDropValue)(checkMixedDropSurvivors)
    }

    "schema-level drop alongside a mixed product decodes correctly through Yaml" in {
        assertDropRoundTripSurvives[TransformMixedDrop, Yaml](transformMixedDropValue)(checkMixedDropSurvivors)
    }

    "schema-level drop alongside a mixed product decodes correctly through MsgPack" in {
        assertDropRoundTripSurvives[TransformMixedDrop, MsgPack](transformMixedDropValue)(checkMixedDropSurvivors)
    }

    "schema-level drop alongside a mixed product decodes correctly through BSON" in {
        assertDropRoundTripSurvives[TransformMixedDrop, Bson](transformMixedDropValue)(checkMixedDropSurvivors)
    }

    "schema-level drop alongside a mixed product decodes correctly through Ion text" in {
        assertDropRoundTripSurvives[TransformMixedDrop, Ion](transformMixedDropValue)(checkMixedDropSurvivors)
    }

    "schema-level drop alongside a mixed product decodes correctly through Ion Binary" in {
        assertDropRoundTripSurvives[TransformMixedDrop, IonBinary](transformMixedDropValue)(checkMixedDropSurvivors)
    }

    "schema-level drop alongside a mixed product decodes correctly through Protobuf" in {
        assertDropRoundTripSurvives[TransformMixedDrop, Protobuf](transformMixedDropValue)(checkMixedDropSurvivors)
    }

    "schema-level denyUnknownFields alongside a mixed product decodes correctly through JSON" in {
        assertRenameOrDenyRoundTrip[TransformMixedDeny, Json](transformMixedDenyValue)
    }

    "schema-level denyUnknownFields alongside a mixed product decodes correctly through Yaml" in {
        assertRenameOrDenyRoundTrip[TransformMixedDeny, Yaml](transformMixedDenyValue)
    }

    "schema-level denyUnknownFields alongside a mixed product decodes correctly through MsgPack" in {
        assertRenameOrDenyRoundTrip[TransformMixedDeny, MsgPack](transformMixedDenyValue)
    }

    "schema-level denyUnknownFields alongside a mixed product decodes correctly through Protobuf" in {
        assertRenameOrDenyRoundTrip[TransformMixedDeny, Protobuf](transformMixedDenyValue)
    }

    "schema-level denyUnknownFields alongside a mixed product decodes correctly through BSON" in {
        assertRenameOrDenyRoundTrip[TransformMixedDeny, Bson](transformMixedDenyValue)
    }

    "schema-level denyUnknownFields alongside a mixed product decodes correctly through Ion text" in {
        assertRenameOrDenyRoundTrip[TransformMixedDeny, Ion](transformMixedDenyValue)
    }

    "schema-level denyUnknownFields alongside a mixed product decodes correctly through Ion Binary" in {
        assertRenameOrDenyRoundTrip[TransformMixedDeny, IonBinary](transformMixedDenyValue)
    }

end CodecTest
