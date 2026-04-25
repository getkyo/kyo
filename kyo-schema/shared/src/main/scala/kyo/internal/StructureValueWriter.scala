package kyo.internal

import kyo.*
import kyo.Codec.Writer

/** Writer that builds an in-memory [[kyo.Structure.Value]] tree instead of a byte stream.
  *
  * Used by the structure subsystem to convert a typed Scala value into the universal Structure.Value representation via the standard Writer
  * protocol. The resulting tree can then be inspected, diffed, or transformed without knowledge of the original type.
  *
  *   - Produces [[kyo.Structure.Value.Record]], [[kyo.Structure.Value.Sequence]], and typed primitive nodes (Str, Bool, Integer, Decimal,
  *     BigNum)
  *   - Maintains a stack of frames to track nested object/array construction
  *   - `result()` returns `Span.empty`; use `getResult` to obtain the built value tree
  *
  * @see
  *   [[StructureValueReader]] for the deserialization counterpart
  * @see
  *   [[kyo.Structure.Value]] for the value tree data model
  */
final class StructureValueWriter(using Frame) extends Writer:

    sealed private trait StackFrame
    private case class ObjectFrame(
        name: String,
        var currentField: String,
        fields: scala.collection.mutable.ListBuffer[(String, Structure.Value)]
    ) extends StackFrame
    private case class ArrayFrame(elements: scala.collection.mutable.ListBuffer[Structure.Value]) extends StackFrame

    private var stack: List[StackFrame]      = Nil
    private var resultValue: Structure.Value = Structure.Value.Null

    private def addValue(dv: Structure.Value): Unit =
        stack match
            case (f: ObjectFrame) :: _ =>
                f.fields += ((f.currentField, dv))
            case (f: ArrayFrame) :: _ =>
                f.elements += dv
            case Nil =>
                resultValue = dv
    end addValue

    def objectStart(name: String, size: Int): Unit =
        stack = ObjectFrame(name, "", scala.collection.mutable.ListBuffer.empty) :: stack

    def objectEnd(): Unit =
        stack match
            case (f: ObjectFrame) :: rest =>
                stack = rest
                addValue(Structure.Value.Record(Chunk.from(f.fields)))
            case _ =>
                throw TypeMismatchException(Seq.empty, "ObjectFrame", "no active object")
    end objectEnd

    def arrayStart(size: Int): Unit =
        stack = ArrayFrame(scala.collection.mutable.ListBuffer.empty) :: stack

    def arrayEnd(): Unit =
        stack match
            case (f: ArrayFrame) :: rest =>
                stack = rest
                addValue(Structure.Value.Sequence(Chunk.from(f.elements)))
            case _ =>
                throw TypeMismatchException(Seq.empty, "ArrayFrame", "no active array")
    end arrayEnd

    def fieldBytes(nameBytes: Array[Byte], index: Int): Unit =
        stack match
            case (f: ObjectFrame) :: _ =>
                f.currentField = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)
            case _ =>
                throw TypeMismatchException(Seq.empty, "ObjectFrame", "no active object")
    end fieldBytes

    def string(value: String): Unit   = addValue(Structure.Value.Str(value))
    def int(value: Int): Unit         = addValue(Structure.Value.Integer(value.toLong))
    def long(value: Long): Unit       = addValue(Structure.Value.Integer(value))
    def float(value: Float): Unit     = addValue(Structure.Value.Decimal(value.toDouble))
    def double(value: Double): Unit   = addValue(Structure.Value.Decimal(value))
    def boolean(value: Boolean): Unit = addValue(Structure.Value.Bool(value))
    def short(value: Short): Unit     = addValue(Structure.Value.Integer(value.toLong))
    def byte(value: Byte): Unit       = addValue(Structure.Value.Integer(value.toLong))
    def char(value: Char): Unit       = addValue(Structure.Value.Str(value.toString))
    def nil(): Unit                   = addValue(Structure.Value.Null)

    def mapStart(size: Int): Unit =
        stack = ObjectFrame("", "", scala.collection.mutable.ListBuffer.empty) :: stack

    def mapEnd(): Unit = objectEnd()

    def bytes(value: Span[Byte]): Unit            = addValue(Structure.Value.Str(java.util.Base64.getEncoder.encodeToString(value.toArray)))
    def bigInt(value: BigInt): Unit               = addValue(Structure.Value.BigNum(BigDecimal(value)))
    def bigDecimal(value: BigDecimal): Unit       = addValue(Structure.Value.BigNum(value))
    def instant(value: java.time.Instant): Unit   = addValue(Structure.Value.Str(value.toString))
    def duration(value: java.time.Duration): Unit = addValue(Structure.Value.Str(value.toString))

    def getResult: Structure.Value = resultValue

    def result(): Span[Byte] = Span.empty

end StructureValueWriter
