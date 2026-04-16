package kyo.internal

import kyo.*
import kyo.Codec.Reader

/** Reader that traverses an in-memory [[kyo.Structure.Value]] tree as if it were a byte stream.
  *
  * Provides the deserialization counterpart to [[StructureValueWriter]]: given a Structure.Value tree, this reader exposes it through the
  * standard Reader protocol so that Schema-derived codecs can reconstruct a typed Scala value from the universal representation.
  *
  *   - Navigates [[kyo.Structure.Value.Record]], [[kyo.Structure.Value.Sequence]], typed primitive nodes (Str, Bool, Integer, Decimal,
  *     BigNum), and [[kyo.Structure.Value.VariantCase]] nodes
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
final class StructureValueReader(root: Structure.Value)(using _frame: Frame) extends Reader:
    override def frame: Frame = _frame

    sealed private trait StackFrame
    private case class ObjectFrame(fields: Iterator[(String, Structure.Value)], var current: Maybe[(String, Structure.Value)])
        extends StackFrame
    private case class ArrayFrame(elements: Iterator[Structure.Value]) extends StackFrame

    private var stack: List[StackFrame]       = Nil
    private var currentValue: Structure.Value = root

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
            case other =>
                throw TypeMismatchException(Seq.empty, "Record or Variant", other.toString)
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
            case other =>
                throw TypeMismatchException(Seq.empty, "Sequence", other.toString)
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
                    entry._1
                else
                    throw MissingFieldException(Seq.empty, "<next>")
            case _ =>
                throw TypeMismatchException(Seq.empty, "ObjectFrame", "no active object")
    end field

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
        currentValue match
            case Structure.Value.Str(s)     => s
            case Structure.Value.Integer(l) => l.toString
            case Structure.Value.Decimal(d) => d.toString
            case Structure.Value.Bool(b)    => b.toString
            case Structure.Value.BigNum(bd) => bd.toString
            case other                      => throw TypeMismatchException(Seq.empty, "String", other.toString)

    def int(): Int =
        currentValue match
            case Structure.Value.Integer(l) => l.toInt
            case Structure.Value.Decimal(d) => d.toInt
            case Structure.Value.BigNum(bd) => bd.toInt
            case other                      => throw TypeMismatchException(Seq.empty, "Int", other.toString)

    def long(): Long =
        currentValue match
            case Structure.Value.Integer(l) => l
            case Structure.Value.Decimal(d) => d.toLong
            case Structure.Value.BigNum(bd) => bd.toLong
            case other                      => throw TypeMismatchException(Seq.empty, "Long", other.toString)

    def float(): Float =
        currentValue match
            case Structure.Value.Decimal(d) => d.toFloat
            case Structure.Value.Integer(l) => l.toFloat
            case Structure.Value.BigNum(bd) => bd.toFloat
            case other                      => throw TypeMismatchException(Seq.empty, "Float", other.toString)

    def double(): Double =
        currentValue match
            case Structure.Value.Decimal(d) => d
            case Structure.Value.Integer(l) => l.toDouble
            case Structure.Value.BigNum(bd) => bd.toDouble
            case other                      => throw TypeMismatchException(Seq.empty, "Double", other.toString)

    def boolean(): Boolean =
        currentValue match
            case Structure.Value.Bool(b) => b
            case other                   => throw TypeMismatchException(Seq.empty, "Boolean", other.toString)

    def short(): Short =
        currentValue match
            case Structure.Value.Integer(l) => l.toShort
            case other                      => throw TypeMismatchException(Seq.empty, "Short", other.toString)

    def byte(): Byte =
        currentValue match
            case Structure.Value.Integer(l) => l.toByte
            case other                      => throw TypeMismatchException(Seq.empty, "Byte", other.toString)

    def char(): Char =
        currentValue match
            case Structure.Value.Str(s) if s.length == 1 => s.charAt(0)
            case Structure.Value.Str(s) if s.nonEmpty    => s.charAt(0)
            case other                                   => throw TypeMismatchException(Seq.empty, "Char", other.toString)

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

    def mapStart(): Int         = objectStart()
    def mapEnd(): Unit          = objectEnd()
    def hasNextEntry(): Boolean = hasNextField()

    def bytes(): Span[Byte] =
        currentValue match
            case Structure.Value.Str(s) =>
                Span.from(java.util.Base64.getDecoder.decode(s))
            case other => throw TypeMismatchException(Seq.empty, "Span[Byte]", other.toString)

    def bigInt(): BigInt =
        currentValue match
            case Structure.Value.BigNum(bd) => bd.toBigInt
            case Structure.Value.Integer(l) => BigInt(l)
            case other                      => throw TypeMismatchException(Seq.empty, "BigInt", other.toString)

    def bigDecimal(): BigDecimal =
        currentValue match
            case Structure.Value.BigNum(bd) => bd
            case Structure.Value.Integer(l) => BigDecimal(l)
            case Structure.Value.Decimal(d) => BigDecimal(d)
            case other                      => throw TypeMismatchException(Seq.empty, "BigDecimal", other.toString)

    def instant(): java.time.Instant =
        currentValue match
            case Structure.Value.Str(s) => java.time.Instant.parse(s)
            case other                  => throw TypeMismatchException(Seq.empty, "Instant", other.toString)

    def duration(): java.time.Duration =
        currentValue match
            case Structure.Value.Str(s) => java.time.Duration.parse(s)
            case other                  => throw TypeMismatchException(Seq.empty, "Duration", other.toString)

end StructureValueReader
