package kyo.internal

import kyo.*
import kyo.Codec.Reader
import kyo.Codec.Writer
import scala.annotation.tailrec

/** Serialization logic for Schema instances: transform-aware write/read, Structure.Value utilities, and the TransformAwareReader.
  *
  * All methods take a Schema instance as a parameter and access its private[kyo] fields directly. Schema instance methods delegate here as
  * one-line stubs.
  */
private[kyo] object SchemaSerializer:

    /** Writes a value to a Writer, dispatching to the direct or transform-aware path. */
    def writeTo[A](schema: Schema[A], value: A, writer: Writer)(using frame: Frame): Unit =
        schema.serializeWrite match
            case Maybe.Present(write) =>
                if schema.droppedFields.isEmpty && schema.renamedFields.isEmpty &&
                    schema.computedFields.isEmpty &&
                    schema.discriminatorField.isEmpty
                then
                    write(value, writer)
                else
                    writeWithTransforms(schema, value, writer)
            case _ =>
                throw SchemaNotSerializableException(
                    "This schema does not have serialization. " +
                        "Focused schemas and Record schemas cannot serialize directly."
                )
    end writeTo

    /** Transform-aware serialization path.
      *
      * Serializes the original value to Structure.Value, applies transforms (drop/rename/add), then writes the transformed tree to the
      * target Writer.
      */
    def writeWithTransforms[A](schema: Schema[A], value: A, writer: Writer)(using Frame): Unit =
        val structWriter = StructureValueWriter()
        schema.serializeWrite.get(value, structWriter)
        val original = structWriter.getResult

        val transformed = original match
            case Structure.Value.Record(originalFields) =>
                // Resolve rename chains
                val forwardMap = schema.renamedFields.toMap
                def resolveTarget(name: String): String =
                    forwardMap.get(name) match
                        case Some(next) => resolveTarget(next)
                        case None       => name
                val resolvedRenames = schema.sourceFields.flatMap { sf =>
                    if forwardMap.contains(sf.name) then Some(sf.name -> resolveTarget(sf.name))
                    else None
                }.toMap
                val renamedSourceNames = resolvedRenames.keySet

                // Transform original fields: drop and skip renamed sources
                val transformedFields = originalFields.flatMap { (name, reflValue) =>
                    if schema.droppedFields.contains(name) then
                        Chunk.empty
                    else if renamedSourceNames.contains(name) then
                        Chunk.empty // added below with new name
                    else
                        Chunk((name, reflValue))
                }

                // Add renamed fields with their values
                val renamedFieldValues = Chunk.from(resolvedRenames.flatMap { (sourceName, targetName) =>
                    originalFields.find(_._1 == sourceName).map((_, v) => (targetName, v))
                })

                // Add computed fields
                val computedFieldValues = schema.computedFields.map { (name, compute) =>
                    (name, anyToStructureValue(compute(value)))
                }

                val allFields = transformedFields ++ renamedFieldValues ++ computedFieldValues
                Structure.Value.Record(allFields)

            case other =>
                other

        // Apply discriminator flattening if configured
        val output = schema.discriminatorField match
            case Maybe.Present(discField) =>
                flattenWithDiscriminator(transformed, discField)
            case _ =>
                transformed

        writeStructureValue(writer, output)
    end writeWithTransforms

    /** Transforms a wrapper-format sealed trait value into flat discriminator format.
      *
      * Wrapper format: `Record([("MTCircle", Record([("radius", 5.0)]))])` Flat format:
      * `Record([("type", Str("MTCircle")), ("radius", 5.0)])`
      *
      * For case objects (variant value is an empty Record), produces: `Record([("type", Str("Active"))])`
      */
    private def flattenWithDiscriminator(value: Structure.Value, discField: String): Structure.Value =
        value match
            // Wrapper format: single-field Record where field name is variant name
            case Structure.Value.Record(fields) if fields.size == 1 =>
                val (variantName, innerValue) = fields.head
                innerValue match
                    case Structure.Value.Record(innerFields) =>
                        // Flatten: discriminator field + variant's fields at same level
                        val discEntry = (discField, Structure.Value.Str(variantName))
                        Structure.Value.Record(Chunk(discEntry) ++ innerFields)
                    case _ =>
                        // Non-record variant (shouldn't happen normally, but handle gracefully)
                        val discEntry = (discField, Structure.Value.Str(variantName))
                        Structure.Value.Record(Chunk(discEntry))
                end match
            case other =>
                // Not a wrapper format, pass through unchanged
                other
    end flattenWithDiscriminator

    /** Reads a value from a Reader, dispatching to direct or transform-aware path. */
    def readFrom[A](schema: Schema[A], reader: Reader)(using frame: Frame): A =
        schema.serializeRead match
            case Maybe.Present(read) =>
                if schema.discriminatorField.nonEmpty then
                    readWithDiscriminator(schema, reader)
                else if schema.renamedFields.nonEmpty || schema.droppedFields.nonEmpty then
                    readWithTransforms(schema, reader)
                else
                    read(reader)
            case _ =>
                throw SchemaNotSerializableException(
                    "This schema does not have serialization. " +
                        "Focused schemas and Record schemas cannot serialize directly."
                )
    end readFrom

    /** Transform-aware deserialization path.
      *
      * Handles renames (by reversing the rename mapping so the external field name is translated back to the original) and dropped fields
      * (by pre-populating their slots with zero values so required-field checks pass).
      */
    def readWithTransforms[A](schema: Schema[A], reader: Reader)(using Frame): A =
        // Build reverse rename map: external name (in JSON) -> original field name
        val forwardMap = schema.renamedFields.toMap

        @tailrec def resolveTarget(name: String): String =
            forwardMap.get(name) match
                case Some(next) => resolveTarget(next)
                case None       => name

        // reverseMap: final external name -> original source field name (for renamed fields only)
        val reverseMap: Map[String, String] =
            if schema.renamedFields.isEmpty then Map.empty
            else
                schema.sourceFields.flatMap { sf =>
                    if forwardMap.contains(sf.name) then Some(resolveTarget(sf.name) -> sf.name)
                    else None
                }.toMap

        // renamedSources: original field names that have been renamed away (no longer valid in JSON)
        val renamedSources: Set[String] =
            if schema.renamedFields.isEmpty then Set.empty
            else schema.sourceFields.filter(sf => forwardMap.contains(sf.name)).map(_.name).toSet

        // droppedIndices: for each dropped field, its index in the case class product
        val droppedIndices: Map[Int, Field[?, ?]] =
            if schema.droppedFields.isEmpty then Map.empty
            else
                schema.sourceFields.zipWithIndex.flatMap { (field, idx) =>
                    if schema.droppedFields.contains(field.name) then Some(idx -> field)
                    else None
                }.toMap

        val transformReader =
            if reverseMap.isEmpty && renamedSources.isEmpty && droppedIndices.isEmpty then reader
            else new TransformAwareReader(reader, reverseMap, renamedSources, droppedIndices)

        schema.serializeRead.get(transformReader)
    end readWithTransforms

    /** Converts an arbitrary Scala value to Structure.Value for transform-aware serialization. */
    def anyToStructureValue(value: Any): Structure.Value =
        if isNull(value) then Structure.Value.Null
        else
            value match
                case s: String           => Structure.Value.Str(s)
                case b: Boolean          => Structure.Value.Bool(b)
                case i: Int              => Structure.Value.Integer(i.toLong)
                case l: Long             => Structure.Value.Integer(l)
                case d: Double           => Structure.Value.Decimal(d)
                case f: Float            => Structure.Value.Decimal(f.toDouble)
                case s: Short            => Structure.Value.Integer(s.toLong)
                case b: Byte             => Structure.Value.Integer(b.toLong)
                case c: Char             => Structure.Value.Str(c.toString)
                case bd: BigDecimal      => Structure.Value.BigNum(bd)
                case bi: BigInt          => Structure.Value.BigNum(BigDecimal(bi))
                case m: Maybe[?]         => m.fold(Structure.Value.Null)(v => anyToStructureValue(v))
                case o: Option[?]        => o.fold(Structure.Value.Null)(v => anyToStructureValue(v))
                case sv: Structure.Value => sv
                case s: Iterable[?]      => Structure.Value.Sequence(Chunk.from(s.map(anyToStructureValue)))
                case other               => Structure.Value.Str(other.toString)
            end match
    end anyToStructureValue

    /** Writes a Structure.Value tree to a Writer. Reverse of StructureValueWriter. */
    def writeStructureValue(writer: Writer, value: Structure.Value): Unit =
        value match
            case Structure.Value.Record(fields) =>
                writer.objectStart("", fields.size)
                fields.foreach { (name, v) =>
                    writer.fieldBytes(
                        name.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        CodecMacro.fieldId(name)
                    )
                    writeStructureValue(writer, v)
                }
                writer.objectEnd()
            case Structure.Value.Sequence(elements) =>
                writer.arrayStart(elements.size)
                elements.foreach(e => writeStructureValue(writer, e))
                writer.arrayEnd()
            case Structure.Value.MapEntries(entries) =>
                writer.mapStart(entries.size)
                entries.foreach { (k, v) =>
                    writeStructureValue(writer, k)
                    writeStructureValue(writer, v)
                }
                writer.mapEnd()
            case Structure.Value.VariantCase(name, v) =>
                writer.objectStart(name, 1)
                writeStructureValue(writer, v)
                writer.objectEnd()
            case Structure.Value.Str(s) => writer.string(s)
            case Structure.Value.Integer(l) =>
                if l >= Int.MinValue && l <= Int.MaxValue then writer.int(l.toInt)
                else writer.long(l)
            case Structure.Value.Decimal(d) => writer.double(d)
            case Structure.Value.Bool(b)    => writer.boolean(b)
            case Structure.Value.BigNum(bd) => writer.bigDecimal(bd)
            case Structure.Value.Null       => writer.nil()

    /** Returns a zero/default value for a dropped field so required-field null checks pass during decode.
      *
      * Uses the field's declared default if available. Otherwise derives a type-appropriate zero value from the field's tag. For reference
      * types without a known zero, returns null — this is intentional because the macro-generated decoder uses `Array[AnyRef]` with JVM
      * null checks (`values(idx) == null`) to detect missing required fields.
      */
    def zeroForField(field: Field[?, ?]): AnyRef =
        val zeroFromTag: AnyRef =
            val show = field.tag.show
            if show == "java.lang.String" then ""
            else if show == "scala.Int" then java.lang.Integer.valueOf(0)
            else if show == "scala.Long" then java.lang.Long.valueOf(0L)
            else if show == "scala.Double" then java.lang.Double.valueOf(0.0)
            else if show == "scala.Float" then java.lang.Float.valueOf(0.0f)
            else if show == "scala.Short" then java.lang.Short.valueOf(0.toShort)
            else if show == "scala.Byte" then java.lang.Byte.valueOf(0.toByte)
            else if show == "scala.Boolean" then java.lang.Boolean.FALSE
            else if show == "scala.Char" then java.lang.Character.valueOf('\u0000')
            else if show.startsWith("scala.Option[") then None.asInstanceOf[AnyRef]
            else if show.startsWith("kyo.Maybe[") then Maybe.empty.asInstanceOf[AnyRef]
            else null // JVM null for unknown reference types — required by macro null-check protocol
            end if
        end zeroFromTag
        field.default.fold(zeroFromTag)(_.asInstanceOf[AnyRef])
    end zeroForField

    /** Discriminator-aware deserialization path.
      *
      * Reads a flat JSON object like `{"type":"MTCircle","radius":5.0}`, extracts the discriminator field value, and presents the data in
      * wrapper format to the macro-generated sealed trait reader via a [[DiscriminatorReader]].
      *
      * The approach:
      *   1. Read all fields from the flat object, capturing non-discriminator field values as sub-readers
      *   2. Present data in wrapper format:
      *      `objectStart(1), field(variantName), objectStart(n), field(f1), value1, ..., objectEnd, objectEnd`
      *   3. For each field value read, delegate entirely to the captured sub-reader
      */
    def readWithDiscriminator[A](schema: Schema[A], reader: Reader)(using frame: Frame): A =
        val discField  = schema.discriminatorField.get
        val discReader = new DiscriminatorReader(reader, discField, frame)
        // The macro-generated sealedReadBody expects wrapper format, which DiscriminatorReader provides
        if schema.renamedFields.nonEmpty || schema.droppedFields.nonEmpty then
            readWithTransforms(schema, discReader)
        else
            schema.serializeRead.get(discReader)
        end if
    end readWithDiscriminator

    /** A [[Reader]] wrapper that transforms flat discriminator format back to wrapper format for sealed trait deserialization.
      *
      * When the macro-generated `sealedReadBody` calls `objectStart()`, this reader reads all fields from the inner (wire-format) reader,
      * finds the discriminator, buffers non-discriminator field values as captured sub-readers, and then presents the data in wrapper
      * format.
      *
      * The key challenge is that when the case class reader calls `schema.serializeRead.get(reader)` for a field value, the inner schema
      * may make arbitrary Reader calls (objectStart, hasNextField, field, double, etc.) on this DiscriminatorReader. These must all
      * delegate to the captured sub-reader for that field. This is achieved by tracking a `delegateReader`: when set, ALL Reader method
      * calls are forwarded to it. The delegate is set after `fieldParse()` identifies a field, and cleared by the next `fieldParse()` or
      * `hasNextField()` call at the case-class level.
      *
      * Depth tracking distinguishes case-class-level calls from nested-value calls: `objectStart`/`objectEnd` within a delegate increment/
      * decrement `delegateDepth`. When `delegateDepth > 0`, even `hasNextField` and `fieldParse` go to the delegate.
      */
    final class DiscriminatorReader(
        inner: Reader,
        discField: String,
        _frame: Frame
    ) extends Reader:

        def frame: Frame = _frame

        // Phase 0: initial, not yet started
        // Phase 1: outer object started, about to return variant name as field
        // Phase 2: variant field returned, inner object about to start
        // Phase 3: inner object started, iterating buffered fields
        // Phase 4: done
        private var phase: Int                                     = 0
        private var variantName: Maybe[String]                     = Maybe.empty
        private var bufferedFields: Maybe[Array[(String, Reader)]] = Maybe.empty
        private var fieldIdx: Int                                  = 0
        private var innerFieldCount: Int                           = 0

        // Delegation state: when delegateReader is set, ALL calls go to it
        private var delegateReader: Maybe[Reader] = Maybe.empty
        private var delegateDepth: Int            = 0

        def objectStart(): Int =
            if delegateReader.nonEmpty then
                delegateDepth += 1
                delegateReader.get.objectStart()
            else
                phase match
                    case 0 =>
                        // Read entire flat object, extract discriminator, buffer other fields
                        val _                           = inner.objectStart()
                        val fields                      = scala.collection.mutable.ListBuffer[(String, Reader)]()
                        var foundVariant: Maybe[String] = Maybe.empty
                        while inner.hasNextField() do
                            val fname = inner.field()
                            if fname == discField then
                                foundVariant = Maybe(inner.string())
                            else
                                val captured = inner.captureValue()
                                fields += ((fname, captured))
                            end if
                        end while
                        inner.objectEnd()

                        if foundVariant.isEmpty then
                            throw MissingFieldException(Seq.empty, discField)(using _frame)
                        variantName = foundVariant
                        val arr = fields.toArray
                        bufferedFields = Maybe(arr)
                        innerFieldCount = arr.length
                        phase = 1
                        1 // outer wrapper has 1 field (the variant name)

                    case 2 =>
                        // Inner variant object
                        fieldIdx = 0
                        phase = 3
                        innerFieldCount

                    case _ =>
                        throw TypeMismatchException(Seq.empty, "objectStart", s"unexpected phase $phase")(using _frame)
                end match
            end if
        end objectStart

        def objectEnd(): Unit =
            if delegateReader.nonEmpty then
                delegateDepth -= 1
                delegateReader.get.objectEnd()
                if delegateDepth < 0 then
                    // The delegate's object ended - but this shouldn't happen because
                    // delegateDepth should only go negative if there's no matching objectStart
                    delegateDepth = 0
                    delegateReader = Maybe.empty
                end if
            else
                phase match
                    case 3 =>
                        // End of inner variant object
                        phase = 4
                    case 4 =>
                        // End of outer wrapper object (called by sealedReadBody)
                        ()
                    case _ =>
                        throw TypeMismatchException(Seq.empty, "objectEnd", s"unexpected phase $phase")(using _frame)
                end match
            end if
        end objectEnd

        def field(): String =
            if delegateReader.nonEmpty then
                delegateReader.get.field()
            else
                phase match
                    case 1 =>
                        phase = 2
                        variantName.get
                    case 3 =>
                        val arr = bufferedFields.get
                        if fieldIdx < arr.length then
                            val (name, reader) = arr(fieldIdx)
                            fieldIdx += 1
                            delegateReader = Maybe(reader)
                            delegateDepth = 0
                            name
                        else
                            throw MissingFieldException(Seq.empty, "<next>")(using _frame)
                        end if
                    case _ =>
                        throw TypeMismatchException(Seq.empty, "field", s"unexpected phase $phase")(using _frame)
                end match
            end if
        end field

        override def fieldParse(): Unit =
            if delegateReader.nonEmpty && delegateDepth > 0 then
                delegateReader.get.fieldParse()
            else
                // At case-class level: clear previous delegate and advance to next field
                delegateReader = Maybe.empty
                delegateDepth = 0
                if phase == 3 then
                    val arr = bufferedFields.get
                    if fieldIdx < arr.length then
                        val (name, reader) = arr(fieldIdx)
                        fieldIdx += 1
                        delegateReader = Maybe(reader)
                        delegateDepth = 0
                        _parsedFieldName = Maybe(name)
                    else
                        _parsedFieldName = Maybe.empty
                    end if
                else
                    _parsedFieldName = Maybe(field())
                end if
            end if
        end fieldParse

        private var _parsedFieldName: Maybe[String] = Maybe.empty

        override def matchField(nameBytes: Array[Byte]): Boolean =
            if delegateReader.nonEmpty && delegateDepth > 0 then
                delegateReader.get.matchField(nameBytes)
            else if _parsedFieldName.isEmpty then false
            else
                val expected = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)
                _parsedFieldName.get == expected
        end matchField

        def hasNextField(): Boolean =
            if delegateReader.nonEmpty && delegateDepth > 0 then
                delegateReader.get.hasNextField()
            else
                // At case-class level: clear delegate and check for more fields
                delegateReader = Maybe.empty
                delegateDepth = 0
                phase match
                    case 1 => true
                    case 3 => fieldIdx < bufferedFields.get.length
                    case _ => false
                end match
            end if
        end hasNextField

        def hasNextElement(): Boolean =
            if delegateReader.nonEmpty then delegateReader.get.hasNextElement()
            else false

        def arrayStart(): Int =
            if delegateReader.nonEmpty then
                delegateDepth += 1
                delegateReader.get.arrayStart()
            else throw TypeMismatchException(Seq.empty, "arrayStart", s"unexpected phase $phase")(using _frame)

        def arrayEnd(): Unit =
            if delegateReader.nonEmpty then
                delegateDepth -= 1
                delegateReader.get.arrayEnd()
            else throw TypeMismatchException(Seq.empty, "arrayEnd", s"unexpected phase $phase")(using _frame)

        def string(): String =
            if delegateReader.nonEmpty then delegateReader.get.string()
            else throw TypeMismatchException(Seq.empty, "String", s"unexpected phase $phase")(using _frame)

        def int(): Int =
            if delegateReader.nonEmpty then delegateReader.get.int()
            else throw TypeMismatchException(Seq.empty, "Int", s"unexpected phase $phase")(using _frame)

        def long(): Long =
            if delegateReader.nonEmpty then delegateReader.get.long()
            else throw TypeMismatchException(Seq.empty, "Long", s"unexpected phase $phase")(using _frame)

        def float(): Float =
            if delegateReader.nonEmpty then delegateReader.get.float()
            else throw TypeMismatchException(Seq.empty, "Float", s"unexpected phase $phase")(using _frame)

        def double(): Double =
            if delegateReader.nonEmpty then delegateReader.get.double()
            else throw TypeMismatchException(Seq.empty, "Double", s"unexpected phase $phase")(using _frame)

        def boolean(): Boolean =
            if delegateReader.nonEmpty then delegateReader.get.boolean()
            else throw TypeMismatchException(Seq.empty, "Boolean", s"unexpected phase $phase")(using _frame)

        def short(): Short =
            if delegateReader.nonEmpty then delegateReader.get.short()
            else throw TypeMismatchException(Seq.empty, "Short", s"unexpected phase $phase")(using _frame)

        def byte(): Byte =
            if delegateReader.nonEmpty then delegateReader.get.byte()
            else throw TypeMismatchException(Seq.empty, "Byte", s"unexpected phase $phase")(using _frame)

        def char(): Char =
            if delegateReader.nonEmpty then delegateReader.get.char()
            else throw TypeMismatchException(Seq.empty, "Char", s"unexpected phase $phase")(using _frame)

        def isNil(): Boolean =
            if delegateReader.nonEmpty then delegateReader.get.isNil()
            else false

        def skip(): Unit =
            if delegateReader.nonEmpty then delegateReader.get.skip()

        def mapStart(): Int =
            if delegateReader.nonEmpty then
                delegateDepth += 1
                delegateReader.get.mapStart()
            else throw TypeMismatchException(Seq.empty, "mapStart", s"unexpected phase $phase")(using _frame)

        def mapEnd(): Unit =
            if delegateReader.nonEmpty then
                delegateDepth -= 1
                delegateReader.get.mapEnd()
            else throw TypeMismatchException(Seq.empty, "mapEnd", s"unexpected phase $phase")(using _frame)

        def hasNextEntry(): Boolean =
            if delegateReader.nonEmpty then delegateReader.get.hasNextEntry()
            else false

        def bytes(): Span[Byte] =
            if delegateReader.nonEmpty then delegateReader.get.bytes()
            else throw TypeMismatchException(Seq.empty, "Span[Byte]", s"unexpected phase $phase")(using _frame)

        def bigInt(): BigInt =
            if delegateReader.nonEmpty then delegateReader.get.bigInt()
            else throw TypeMismatchException(Seq.empty, "BigInt", s"unexpected phase $phase")(using _frame)

        def bigDecimal(): BigDecimal =
            if delegateReader.nonEmpty then delegateReader.get.bigDecimal()
            else throw TypeMismatchException(Seq.empty, "BigDecimal", s"unexpected phase $phase")(using _frame)

        def instant(): java.time.Instant =
            if delegateReader.nonEmpty then delegateReader.get.instant()
            else throw TypeMismatchException(Seq.empty, "Instant", s"unexpected phase $phase")(using _frame)

        def duration(): java.time.Duration =
            if delegateReader.nonEmpty then delegateReader.get.duration()
            else throw TypeMismatchException(Seq.empty, "Duration", s"unexpected phase $phase")(using _frame)

        override def captureValue(): Reader =
            if delegateReader.nonEmpty then delegateReader.get
            else inner.captureValue()

        override def release(): Unit = inner.release()

    end DiscriminatorReader

    /** A [[Reader]] wrapper that applies field-name translation and dropped-field pre-population during decode.
      *
      * Used by [[readWithTransforms]] to make renamed and dropped fields transparent to the macro-generated decoder.
      *
      *   - Renames: [[fieldParse]] reads the raw external name from the inner reader and translates it to the original field name via
      *     [[reverseMap]]. Names that were renamed away (present in [[renamedSources]]) are translated to a unique sentinel so they do not
      *     match any valid field slot.
      *   - Drops: [[initFields]] pre-populates the slot for each dropped field with a zero value so the macro's required-field null check
      *     does not throw [[MissingFieldException]].
      */
    final class TransformAwareReader(
        inner: Reader,
        reverseMap: Map[String, String],
        renamedSources: Set[String],
        droppedIndices: Map[Int, Field[?, ?]]
    ) extends Reader:

        def frame: Frame = inner.frame

        override def initFields(n: Int): Array[AnyRef] =
            val arr = inner.initFields(n)
            for (idx, field) <- droppedIndices do
                if idx < n then arr(idx) = zeroForField(field)
            arr
        end initFields

        override def clearFields(n: Int): Unit = inner.clearFields(n)

        private var _translatedField: Maybe[String] = Maybe.empty

        // Read the field name from the inner reader and translate it.
        // We call inner.field() (not inner.fieldParse()) so the stream advances exactly once.
        override def fieldParse(): Unit =
            val rawName = inner.field()
            _translatedField = Maybe(
                reverseMap.getOrElse(
                    rawName,
                    if renamedSources.contains(rawName) then "\u0000_invalid_renamed_field"
                    else rawName
                )
            )
        end fieldParse

        override def matchField(nameBytes: Array[Byte]): Boolean =
            if _translatedField.isEmpty then false
            else
                val expected = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)
                _translatedField.get == expected
        end matchField

        def objectStart(): Int              = inner.objectStart()
        def objectEnd(): Unit               = inner.objectEnd()
        def arrayStart(): Int               = inner.arrayStart()
        def arrayEnd(): Unit                = inner.arrayEnd()
        def field(): String                 = _translatedField.getOrElse(inner.field())
        def hasNextField(): Boolean         = inner.hasNextField()
        def hasNextElement(): Boolean       = inner.hasNextElement()
        def string(): String                = inner.string()
        def int(): Int                      = inner.int()
        def long(): Long                    = inner.long()
        def float(): Float                  = inner.float()
        def double(): Double                = inner.double()
        def boolean(): Boolean              = inner.boolean()
        def short(): Short                  = inner.short()
        def byte(): Byte                    = inner.byte()
        def char(): Char                    = inner.char()
        def isNil(): Boolean                = inner.isNil()
        def skip(): Unit                    = inner.skip()
        def mapStart(): Int                 = inner.mapStart()
        def mapEnd(): Unit                  = inner.mapEnd()
        def hasNextEntry(): Boolean         = inner.hasNextEntry()
        def bytes(): Span[Byte]             = inner.bytes()
        def bigInt(): BigInt                = inner.bigInt()
        def bigDecimal(): BigDecimal        = inner.bigDecimal()
        def instant(): java.time.Instant    = inner.instant()
        def duration(): java.time.Duration  = inner.duration()
        override def captureValue(): Reader = inner.captureValue()
        override def release(): Unit        = inner.release()
    end TransformAwareReader

end SchemaSerializer
