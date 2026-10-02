package kyo.internal

import kyo.*
import kyo.Codec.IntrospectingReader
import kyo.Codec.Reader

/** Reader that traverses an in-memory [[kyo.Structure.Value]] tree as if it were a byte stream.
  *
  * Provides the deserialization counterpart to [[StructureValueWriter]]: given a Structure.Value tree, this reader exposes it through the
  * standard Reader protocol so that Schema-derived codecs can reconstruct a typed Scala value from the universal representation.
  *
  *   - Navigates [[kyo.Structure.Value.Record]], [[kyo.Structure.Value.Sequence]], [[kyo.Structure.Value.MapEntries]], typed primitive
  *     nodes (Str, Bool, Integer, Decimal, BigNum, Bytes, Instant, Duration), and [[kyo.Structure.Value.VariantCase]] nodes
  *   - Maintains a stack of frames matching the nesting depth of the value tree
  *   - Supports `captureValue()` for deferred sub-tree reading (used by sum type codecs)
  *
  * @param root
  *   the root value tree to read from
  * @see
  *   [[StructureValueWriter]] for the serialization counterpart
  * @see
  *   [[kyo.Structure.Value]] for the value tree data model
  */
final class StructureValueReader(root: Structure.Value)(using _frame: Frame) extends IntrospectingReader:
    override def frame: Frame = _frame

    // Nothing to be left over: this reads a value that is already parsed, so there is no input after
    // it for anything to hide in.
    private[kyo] def requireEndOfInput(): Unit = ()

    sealed private trait StackFrame
    private case class ObjectFrame(fields: Iterator[(String, Structure.Value)], var current: Maybe[(String, Structure.Value)])
        extends StackFrame
    private case class ArrayFrame(elements: Iterator[Structure.Value]) extends StackFrame

    private var stack: List[StackFrame]       = Nil
    private var currentValue: Structure.Value = root
    private var _lastFieldName: String        = ""

    def objectStart(): Int =
        currentValue match
            case Structure.Value.Record(fields) =>
                val iter = fields.iterator
                stack = ObjectFrame(iter, Maybe.empty) :: stack
                fields.size
            case Structure.Value.VariantCase(name, value) =>
                // Variant is encoded as a single-field object wrapper
                val singleField = Chunk((name, value))
                val iter        = singleField.iterator
                stack = ObjectFrame(iter, Maybe.empty) :: stack
                1
            case _ => mismatch("object")
    end objectStart

    def objectEnd(): Unit =
        stack match
            case (_: ObjectFrame) :: rest =>
                stack = rest
            case _ =>
                throw TypeMismatchException(Seq.empty, "ObjectFrame", "no active object")
    end objectEnd

    def arrayStart(): Int =
        currentValue match
            case Structure.Value.Sequence(elements) =>
                stack = ArrayFrame(elements.iterator) :: stack
                elements.size
            case Structure.Value.MapEntries(entries) =>
                // A map read through the array protocol (the non-string-keyed map readFn) is
                // presented as the array-of-{key, value}-records envelope the wire uses.
                val asRecords = entries.iterator.map { (k, v) =>
                    Structure.Value.Record(Chunk(("key", k), ("value", v))): Structure.Value
                }
                stack = ArrayFrame(asRecords) :: stack
                entries.size
            case _ => mismatch("array")
    end arrayStart

    def arrayEnd(): Unit =
        stack match
            case (_: ArrayFrame) :: rest =>
                stack = rest
            case _ =>
                throw TypeMismatchException(Seq.empty, "ArrayFrame", "no active array")
    end arrayEnd

    def field(): String =
        stack match
            case (f: ObjectFrame) :: _ =>
                if f.fields.hasNext then
                    val entry = f.fields.next()
                    f.current = Maybe(entry)
                    currentValue = entry._2
                    _lastFieldName = entry._1
                    entry._1
                else
                    throw MissingFieldException(Seq.empty, "<next>")
            case _ =>
                throw TypeMismatchException(Seq.empty, "ObjectFrame", "no active object")
    end field

    def fieldParse(): Unit =
        val _ = field()

    def matchField(nameBytes: Array[Byte]): Boolean =
        if _lastFieldName.isEmpty then false
        else
            val expected = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)
            _lastFieldName == expected

    def lastFieldName(): String = _lastFieldName

    def hasNextField(): Boolean =
        stack match
            case (f: ObjectFrame) :: _ => f.fields.hasNext
            case _                     => false
    end hasNextField

    def hasNextElement(): Boolean =
        stack match
            case (f: ArrayFrame) :: _ =>
                if f.elements.hasNext then
                    currentValue = f.elements.next()
                    true
                else
                    false
            case _ => false
    end hasNextElement

    def string(): String =
        // Strict: only Str decodes as String. JSON-RPC / MCP / LSP schemas declare typed fields and the wire must
        // respect them: an integer or boolean where a string is expected is a client bug, not a coercion to absorb.
        // Coercing `Integer(42)` to `"42"` silently accepted a malformed `{"path": 42}` against a `path: String`
        // tool argument and let the server treat it as a real path; that's the correctness gap this rejects.
        currentValue match
            case Structure.Value.Str(s) => s
            case _                      => mismatch("string")

    def int(): Int = integral("Int", Int.MinValue, Int.MaxValue).toInt

    def long(): Long = integral("Long", Long.MinValue, Long.MaxValue)

    def float(): Float =
        currentValue match
            case Structure.Value.Decimal(d) => d.toFloat
            case Structure.Value.Integer(l) => l.toFloat
            case Structure.Value.BigNum(bd) => bd.toFloat
            case _                          => mismatch("number")

    def double(): Double =
        currentValue match
            case Structure.Value.Decimal(d) => d
            case Structure.Value.Integer(l) => l.toDouble
            case Structure.Value.BigNum(bd) => bd.toDouble
            case _                          => mismatch("number")

    def boolean(): Boolean =
        currentValue match
            case Structure.Value.Bool(b) => b
            case _                       => mismatch("boolean")

    def short(): Short = integral("Short", Short.MinValue, Short.MaxValue).toShort

    def byte(): Byte = integral("Byte", Byte.MinValue, Byte.MaxValue).toByte

    /** The number at the cursor as a whole number within `min` to `max`. A tree holds a number as its source wrote it, so a fraction
      * or a value past the bounds is refused here rather than truncated or wrapped into another value.
      */
    private def integral(target: String, min: Long, max: Long): Long =
        def bounded(value: Long): Long =
            if value < min || value > max then throw RangeException(value, target, min, max) else value
        def outside: Nothing  = throw TypeMismatchException(Seq.empty, target, "a number outside its range")
        def fraction: Nothing = throw TypeMismatchException(Seq.empty, target, "a number with a fraction")
        currentValue match
            case Structure.Value.Integer(l) => bounded(l)
            case Structure.Value.Decimal(d) =>
                if d.isNaN || d.isInfinite || d != Math.rint(d) then fraction
                else
                    val whole = BigDecimal(d)
                    if whole.isValidLong then bounded(whole.toLong) else outside
            case Structure.Value.BigNum(bd) =>
                if !bd.isWhole then fraction
                else if bd.isValidLong then bounded(bd.toLong)
                else outside
            case _ => mismatch("number")
        end match
    end integral

    def char(): Char =
        // Strict: a Char field requires a single-character String. Accepting multi-character strings and silently
        // discarding the tail (the prior behaviour) hides client bugs by the same argument that `string()` uses
        // to reject non-Str inputs; the symmetric strict-on-text rule applies here.
        currentValue match
            case Structure.Value.Str(s) if s.length == 1 => s.charAt(0)
            case Structure.Value.Str(s)                  =>
                throw TypeMismatchException(Seq.empty, "a single character", s"a string of length ${s.length}")
            case _ => mismatch("string")

    def isNil(): Boolean =
        currentValue match
            case Structure.Value.Null => true
            case _                    => false
    end isNil

    def skip(): Unit =
        // Value is already set via field() or hasNextElement(), just do nothing
        ()
    end skip

    override def captureValue(): Reader =
        // currentValue is already pointing to the value-to-be-read (set by field() or hasNextElement()).
        // In StructureValueReader, field() already advanced the iterator, so no additional skip is needed.
        val v = currentValue
        new StructureValueReader(v)
    end captureValue

    def readStructure(): Structure.Value = currentValue

    def mapStart(): Int =
        currentValue match
            case Structure.Value.MapEntries(entries) =>
                // A map read through the string-map protocol requires Str keys; the entries are
                // presented as object fields. A Record (the legacy spelling and every wire-decoded
                // tree) falls through to the object presentation.
                val asFields = entries.iterator.map {
                    case (Structure.Value.Str(k), v) => (k, v)
                    case (k, _)                      => throw TypeMismatchException(Seq.empty, "String map key", k.toString)
                }
                stack = ObjectFrame(asFields, Maybe.empty) :: stack
                entries.size
            case _ =>
                objectStart()
    end mapStart
    def mapEnd(): Unit          = objectEnd()
    def hasNextEntry(): Boolean = hasNextField()

    def bytes(): Span[Byte] =
        currentValue match
            case Structure.Value.Bytes(value) => value
            case Structure.Value.Str(s)       =>
                val decoded = Result.catching[IllegalArgumentException](Base64s.decodeExact(s)).mapFailure(_ => "not Base64")
                Span.fromUnsafe(parsedText(s, "Base64", decoded))
            case _ => mismatch("string")

    def bigInt(): BigInt =
        currentValue match
            case Structure.Value.BigNum(bd) =>
                if bd.isWhole then bd.toBigInt else throw TypeMismatchException(Seq.empty, "BigInt", "a number with a fraction")
            case Structure.Value.Integer(l) => BigInt(l)
            case _                          => mismatch("number")

    def bigDecimal(): BigDecimal =
        currentValue match
            case Structure.Value.BigNum(bd) => bd
            case Structure.Value.Integer(l) => BigDecimal(l)
            case Structure.Value.Decimal(d) => BigDecimal(d)
            case _                          => mismatch("number")

    def instant(): java.time.Instant =
        currentValue match
            case Structure.Value.Instant(value) => value
            case Structure.Value.Str(s)         => parsedText(s, "Instant", TimeText.instant(s))
            case _                              => mismatch("string")

    def duration(): java.time.Duration =
        currentValue match
            case Structure.Value.Duration(value) => value
            case Structure.Value.Str(s)          => parsedText(s, "Duration", TimeText.duration(s))
            case _                               => mismatch("string")

    /** The value at the cursor is not of the `expected` kind. Both kinds are named as the JSON reader names them, so a value that
      * reaches a field through a captured tree (flatten, a catch-all, a discriminator buffer) fails with the text it would have read
      * directly.
      */
    private def mismatch(expected: String): Nothing =
        throw TypeMismatchException(Seq.empty, expected, StructureValueReader.kindOf(currentValue))

    /** A value parsed from a string node. A failure is a type mismatch rather than a `ParseException`, which names the codec that read
      * the input: this reader reads a tree some codec already parsed, and does not know which.
      */
    private def parsedText[A](text: String, expected: String, parsed: Result[String, A]): A =
        parsed.foldOrThrow(identity, reason => throw TypeMismatchException(Seq.empty, s"$expected ($reason)", s"'$text'"))

end StructureValueReader

object StructureValueReader:

    /** The kind of a value, in the words a decode failure reports it with. */
    private[kyo] def kindOf(value: Structure.Value): String =
        value match
            case _: Structure.Value.Record | _: Structure.Value.MapEntries | _: Structure.Value.VariantCase => "object"
            case _: Structure.Value.Sequence                                                                => "array"
            case _: Structure.Value.Str                                                                     => "string"
            case _: Structure.Value.Bool                                                                    => "boolean"
            case _: Structure.Value.Integer | _: Structure.Value.Decimal | _: Structure.Value.BigNum        => "number"
            case _: Structure.Value.Bytes                                                                   => "bytes"
            case _: Structure.Value.Instant                                                                 => "timestamp"
            case _: Structure.Value.Duration                                                                => "duration"
            case Structure.Value.Null                                                                       => "null"
end StructureValueReader
