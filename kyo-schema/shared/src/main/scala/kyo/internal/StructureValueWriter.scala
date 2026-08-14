package kyo.internal

import kyo.*
import kyo.Codec.Writer

/** Writer that builds an in-memory [[kyo.Structure.Value]] tree instead of a byte stream.
  *
  * Used by the structure subsystem to convert a typed Scala value into the universal Structure.Value representation via the standard Writer
  * protocol. The resulting tree can then be inspected, diffed, or transformed without knowledge of the original type.
  *
  *   - Produces [[kyo.Structure.Value.Record]], [[kyo.Structure.Value.VariantCase]], [[kyo.Structure.Value.Sequence]],
  *     [[kyo.Structure.Value.MapEntries]], and typed primitive nodes (Str, Bool, Integer, Decimal, BigNum, Bytes, Instant, Duration)
  *   - Maintains a stack of frames to track nested object/array construction
  *   - `result()` returns `Span.empty`; use `getResult` to obtain the built value tree
  *
  * @see
  *   [[StructureValueReader]] for the deserialization counterpart
  * @see
  *   [[kyo.Structure.Value]] for the value tree data model
  */
final class StructureValueWriter extends Writer:

    sealed private trait StackFrame
    private case class ObjectFrame(
        name: String,
        var currentField: String,
        fields: scala.collection.mutable.ListBuffer[(String, Structure.Value)]
    ) extends StackFrame
    private case class ArrayFrame(elements: scala.collection.mutable.ListBuffer[Structure.Value]) extends StackFrame
    private case class VariantFrame(name: String, var value: Structure.Value)                     extends StackFrame
    private case class MapStringFrame(
        var currentField: String,
        entries: scala.collection.mutable.ListBuffer[(Structure.Value, Structure.Value)]
    ) extends StackFrame
    private case class MapPairsFrame(
        var pendingKey: Structure.Value,
        var inKey: Boolean,
        entries: scala.collection.mutable.ListBuffer[(Structure.Value, Structure.Value)]
    ) extends StackFrame

    private var stack: List[StackFrame]      = Nil
    private var resultValue: Structure.Value = Structure.Value.Null

    private def addValue(dv: Structure.Value): Unit =
        stack match
            case (f: ObjectFrame) :: _ =>
                f.fields += ((f.currentField, dv))
            case (f: ArrayFrame) :: _ =>
                f.elements += dv
            case (f: VariantFrame) :: _ =>
                f.value = dv
            case (f: MapStringFrame) :: _ =>
                f.entries += ((Structure.Value.Str(f.currentField), dv))
            case (f: MapPairsFrame) :: _ =>
                if f.inKey then f.pendingKey = dv
                else f.entries += ((f.pendingKey, dv))
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
                bug("StructureValueWriter.objectEnd/fieldBytes: no active object frame")
    end objectEnd

    def arrayStart(size: Int): Unit =
        stack = ArrayFrame(scala.collection.mutable.ListBuffer.empty) :: stack

    def arrayEnd(): Unit =
        stack match
            case (f: ArrayFrame) :: rest =>
                stack = rest
                addValue(Structure.Value.Sequence(Chunk.from(f.elements)))
            case _ =>
                bug("StructureValueWriter.arrayEnd: no active array frame")
    end arrayEnd

    def fieldBytes(nameBytes: Array[Byte], index: Int): Unit =
        stack match
            case (f: ObjectFrame) :: _ =>
                f.currentField = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)
            case (f: MapStringFrame) :: _ =>
                f.currentField = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)
            case _ =>
                bug("StructureValueWriter.objectEnd/fieldBytes: no active object frame")
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
        stack = MapStringFrame("", scala.collection.mutable.ListBuffer.empty) :: stack

    def mapEnd(): Unit =
        stack match
            case (f: MapStringFrame) :: rest =>
                stack = rest
                addValue(Structure.Value.MapEntries(Chunk.from(f.entries)))
            case _ =>
                bug("StructureValueWriter.mapEnd: no active map frame")
    end mapEnd

    override def mapEntriesStart(size: Int): Unit =
        stack = MapPairsFrame(Structure.Value.Null, false, scala.collection.mutable.ListBuffer.empty) :: stack

    override def mapEntryStart(): Unit =
        stack match
            case (f: MapPairsFrame) :: _ => f.inKey = true
            case _                       => bug("StructureValueWriter.mapEntryStart: no active map frame")

    override def mapEntryValue(): Unit =
        stack match
            case (f: MapPairsFrame) :: _ => f.inKey = false
            case _                       => bug("StructureValueWriter.mapEntryValue: no active map frame")

    override def mapEntryEnd(): Unit = ()

    override def mapEntriesEnd(): Unit =
        stack match
            case (f: MapPairsFrame) :: rest =>
                stack = rest
                addValue(Structure.Value.MapEntries(Chunk.from(f.entries)))
            case _ =>
                bug("StructureValueWriter.mapEntriesEnd: no active map frame")
    end mapEntriesEnd

    override def variantStart(name: String, variantName: String, variantNameBytes: Array[Byte], variantFieldId: Int): Unit =
        stack = VariantFrame(variantName, Structure.Value.Null) :: stack

    override def variantEnd(): Unit =
        stack match
            case (f: VariantFrame) :: rest =>
                stack = rest
                addValue(Structure.Value.VariantCase(f.name, f.value))
            case _ =>
                bug("StructureValueWriter.variantEnd: no active variant frame")
    end variantEnd

    def bytes(value: Span[Byte]): Unit            = addValue(Structure.Value.Bytes(value))
    def bigInt(value: BigInt): Unit               = addValue(Structure.Value.BigNum(BigDecimal(value)))
    def bigDecimal(value: BigDecimal): Unit       = addValue(Structure.Value.BigNum(value))
    def instant(value: java.time.Instant): Unit   = addValue(Structure.Value.Instant(value))
    def duration(value: java.time.Duration): Unit = addValue(Structure.Value.Duration(value))

    def getResult: Structure.Value = resultValue

    def result(): Span[Byte] = Span.empty

end StructureValueWriter
