package kyo

import kyo.Codec.Reader
import kyo.Codec.Writer
import kyo.Schema.*

// Shared format-agnostic codec test fixtures: local schema types, the token-based
// TestWriter/TestReader pair, and the round-trip helper. Kept in kyo-schema test scope so
// suites in the format modules and kyo-schema-tests can reuse them via test->test.
// Because this file's name is outside the Frame macro's Test/Spec allowlist, top-level
// givens must pass `Frame.internal` explicitly; helpers instead propagate the caller's
// Frame via a using parameter.

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
given pinOnlyPersonSchema: Schema[PinOnlyPerson] = Schema[PinOnlyPerson].fieldId(_.age)(42)(using Frame.internal)

// --- Nested field-id pins: the pin lives on a NESTED schema, embedded as a product field or inside a
// container element, rather than on the schema passed to encode/decode directly. Neither the
// outer product nor the container carries a transform of its own. ---

case class PinOnlyInner(x: Int, y: String) derives CanEqual
given pinOnlyInnerSchema: Schema[PinOnlyInner] = Schema[PinOnlyInner].fieldId(_.x)(77)(using Frame.internal)

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
    Schema[PinAndRenameInner].fieldId(_.x)(88)(using Frame.internal).rename("y", "y_renamed")

case class PinAndRenameOuter(label: String, inner: PinAndRenameInner) derives CanEqual, Schema

// --- Nested-pin ordering: the nested-pinned schema field is NOT the outer's last field, and the
// outer schema ALSO carries its own field-id pin on a field written AFTER the nested one. The
// Protobuf writer/reader field-id override state must be scoped per nested serializeWrite/
// serializeRead call (save/restore), not a single var that a nested schema's pin permanently
// replaces with no restore on return: otherwise the outer's own later pin is lost to the nested
// schema's override map for the rest of the outer write. ---

case class PinOrderInner(x: Int, y: String) derives CanEqual
given pinOrderInnerSchema: Schema[PinOrderInner] = Schema[PinOrderInner].fieldId(_.x)(501)(using Frame.internal)

case class PinOrderOuter(first: String, inner: PinOrderInner, last: Int) derives CanEqual
given pinOrderOuterSchema: Schema[PinOrderOuter] = Schema[PinOrderOuter].fieldId(_.last)(777)(using Frame.internal)

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
    def roundTrip[A](value: A)(using schema: Schema[A], ce: CanEqual[A, A])(using Frame): A =
        val writer = new TestWriter
        schema.writeTo(value, writer)
        val reader = new TestReader(writer.resultTokens)
        schema.readFrom(reader)
    end roundTrip

    def encode[A](value: A)(using schema: Schema[A])(using Frame): List[Token] =
        val writer = new TestWriter
        schema.writeTo(value, writer)
        writer.resultTokens
    end encode
end CodecTestHelper
