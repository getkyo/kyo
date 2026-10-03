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
final class StructureValueWriter(target: Maybe[Writer], positionalPayload: Boolean)(using site: Frame) extends Writer:

    def this(target: Maybe[Writer])(using Frame) = this(target, false)
    def this()(using Frame) = this(Maybe.empty, false)

    override def frame: Frame = site

    // A positional sum's payload is the record directly inside the root variant frame. A variant schema with transforms decides its
    // omissions against this writer before the payload opens, and materializes its record into a writer of its own, whose root asks
    // this one; a record nested deeper keeps its fields by name and may omit them.
    override private[kyo] def writesEveryField: Boolean =
        def payloadLevel(below: List[StackFrame]): Boolean =
            below match
                case (_: VariantFrame) :: Nil => positionalPayload
                case Nil                      => target.exists(_.writesEveryField)
                case _                        => false
        stack match
            case (_: ObjectFrame) :: below => payloadLevel(below)
            case below                     => payloadLevel(below)
        end match
    end writesEveryField

    // A tree built to be replayed into `target` is subject to `target`'s capabilities: a nested schema's representation and transform
    // checks run against this writer during materialization, and must refuse exactly what the real codec would refuse. A tree with no
    // target is the value itself, and a Structure.Value holds any shape.
    override def canWriteTopLevelNonObject: Boolean = target.forall(_.canWriteTopLevelNonObject)
    override def isSelfDescribing: Boolean          = target.forall(_.isSelfDescribing)
    override def capabilities: Codec.Capabilities   = target.map(_.capabilities).getOrElse(super.capabilities)
    override def codecName: String                  = target.map(_.codecName).getOrElse(super.codecName)

    // A nested schema installs its field-id pins on this writer while it writes its record, and a replay can only apply them if it
    // knows which record they belong to: each object frame keeps the pins active when it opened, and the finished record is
    // recorded with them, by identity, as the map framing below is.
    override def supportsFieldIdOverrides: Boolean                            = target.exists(_.supportsFieldIdOverrides)
    private var fieldIdOverrides: Map[String, Int]                            = Map.empty
    override def withFieldIdOverrides(overrides: Map[String, Int]): this.type =
        fieldIdOverrides = overrides
        this
    override def fieldIdOverridesSnapshot: Map[String, Int] = fieldIdOverrides

    private var fieldIdOverridden: List[(Structure.Value, Map[String, Int])] = Nil

    /** The record nodes in [[getResult]] written while a schema's field-id pins were installed, with those pins, for a caller
      * replaying this tree through `SchemaSerializer.writeStructureValue`.
      */
    private[kyo] def fieldIdOverriddenNodes: List[(Structure.Value, Map[String, Int])] = fieldIdOverridden

    sealed private trait StackFrame
    private case class ObjectFrame(
        name: String,
        var currentField: String,
        fields: scala.collection.mutable.ListBuffer[(String, Structure.Value)],
        fieldIdOverrides: Map[String, Int]
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

    // The map nodes this writer built from the pair-array framing (mapEntriesStart / mapEntriesEnd)
    // rather than the object framing (mapStart / mapEnd). Both produce a Structure.Value.MapEntries,
    // because a map node is semantic and carries no framing (Structure.encode's contract keeps map
    // entries distinguishable from product fields and pair lists, not from each other), so a replay
    // cannot read the framing back off the node. The transform path needs it: it replays this tree
    // into the real writer, and a mapping field whose bound given writes the pair-array form for a
    // String key would otherwise come out in the object form, which the schema that wrote it cannot
    // read back. Held by identity, since two empty mappings are equal values from different givens.
    private var pairArrayFramed: List[Structure.Value] = Nil

    /** The map nodes in [[getResult]] that were written in the pair-array framing, for a caller
      * replaying this tree through `SchemaSerializer.writeStructureValue`.
      */
    private[kyo] def pairArrayFramedNodes: List[Structure.Value] = pairArrayFramed

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
        stack = ObjectFrame(name, "", scala.collection.mutable.ListBuffer.empty, fieldIdOverrides) :: stack

    def objectEnd(): Unit =
        stack match
            case (f: ObjectFrame) :: rest =>
                stack = rest
                val record = Structure.Value.Record(Chunk.from(f.fields))
                if f.fieldIdOverrides.nonEmpty then fieldIdOverridden = (record, f.fieldIdOverrides) :: fieldIdOverridden
                addValue(record)
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
                val node = Structure.Value.MapEntries(Chunk.from(f.entries))
                pairArrayFramed = node :: pairArrayFramed
                addValue(node)
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
