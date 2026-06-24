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

    /** Writes a value to a Writer, dispatching to the direct or transform-aware path.
      *
      * A non-serializable Schema throws `SchemaNotSerializableException` from inside its own `serializeWrite` body (the sentinel lambda
      * installed by `Schema.create`/`createFrom`/`createWithFocused`). No outer Maybe match is needed.
      */
    def writeTo[A](schema: Schema[A], value: A, writer: Writer): Unit =
        schema.serializeWrite(value, writer)

    /** Transform-aware serialization path.
      *
      * Serializes the original value to Structure.Value, applies transforms (drop/rename/add), then writes the transformed tree to the
      * target Writer.
      */
    def writeWithTransforms[A](schema: Schema[A], value: A, writer: Writer): Unit =
        val structWriter = StructureValueWriter()
        schema.rawSerializeWrite(value, structWriter)
        val original = structWriter.getResult

        val transformed = original match
            case Structure.Value.Record(originalFields) =>
                schema.structure match
                    case _: Structure.Type.Product =>
                        // Field-level transforms (drop/rename/computed/convention) apply only to a product's
                        // own fields. For a sum schema the materialized Record is a single-field wrapper whose
                        // key is the variant name, not a data field; that key is governed by the variant-naming
                        // layer and must not be run through applyFieldConvention here.
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
                        given Frame   = Frame.internal
                        Structure.Value.Record(applyFieldConvention(schema, resolvedRenames.values.toSet, allFields))
                    case _ =>
                        Structure.Value.Record(originalFields)
            case other =>
                other

        // Rewrite the materialized value tree into the configured wire shape. External passes
        // through (byte-identical wrapper object); the other cases rewrite at the value-tree level,
        // where a non-object payload is still intact (the flatten's non-record drop is what NOT
        // injecting into a Record avoids).
        val output = schema.representation match
            case Schema.SumRepresentation.External =>
                transformed
            case Schema.SumRepresentation.Internal(tagKey) =>
                flattenWithDiscriminator(transformed, tagKey, resolveVariantWire(schema))
            case Schema.SumRepresentation.Adjacent(tagKey, contentKey) =>
                adjacentEncode(transformed, tagKey, contentKey, resolveVariantWire(schema))
            case Schema.SumRepresentation.Tuple =>
                requireTopLevelCapable(writer, "Tuple")
                tupleEncode(transformed, resolveVariantWire(schema))
            case Schema.SumRepresentation.TupleFlat =>
                requireTopLevelCapable(writer, "TupleFlat")
                tupleFlatEncode(transformed, resolveVariantWire(schema))
            case Schema.SumRepresentation.Untagged =>
                requireTopLevelCapable(writer, "Untagged")
                untaggedEncode(transformed)

        writeStructureValue(writer, output)
    end writeWithTransforms

    /** Transforms a wrapper-format sealed trait value into flat discriminator format.
      *
      * Wrapper format: `Record([("MTCircle", Record([("radius", 5.0)]))])` Flat format:
      * `Record([("type", Str("MTCircle")), ("radius", 5.0)])`
      *
      * For case objects (variant value is an empty Record), produces: `Record([("type", Str("Active"))])`
      */
    private def flattenWithDiscriminator(
        value: Structure.Value,
        discField: String,
        resolveWire: String => String
    ): Structure.Value =
        value match
            // Wrapper format: single-field Record where field name is variant name
            case Structure.Value.Record(fields) if fields.size == 1 =>
                val (variantName, innerValue) = fields.head
                val wireName                  = resolveWire(variantName)
                innerValue match
                    case Structure.Value.Record(innerFields) =>
                        // Flatten: discriminator field + variant's fields at same level
                        val discEntry = (discField, Structure.Value.Str(wireName))
                        Structure.Value.Record(Chunk(discEntry) ++ innerFields)
                    case _ =>
                        // Non-record variant (shouldn't happen normally, but handle gracefully)
                        val discEntry = (discField, Structure.Value.Str(wireName))
                        Structure.Value.Record(Chunk(discEntry))
                end match
            case other =>
                // Not a wrapper format, pass through unchanged
                other
    end flattenWithDiscriminator

    /** Raises `RepresentationUnsupportedException` before any bytes are written when the active
      * writer cannot express a top-level array / bare scalar (the shape Tuple/TupleFlat/Untagged
      * require). Capability is a positive opt-in on the writer (Codec.Writer.canWriteTopLevelNonObject).
      */
    private def requireTopLevelCapable(writer: Writer, representation: String): Unit =
        given Frame = Frame.internal
        if !writer.canWriteTopLevelNonObject then
            throw RepresentationUnsupportedException(writer.codecName, representation)
    end requireTopLevelCapable

    /** Adjacent: rewrite the single-field wrapper into a two-field object
      * `{tagKey: wireName, contentKey: payload}`. The payload passes through unchanged, so a
      * non-object payload (scalar/array/null) survives as the content value; an empty-payload variant
      * yields `{contentKey: {}}` (the precedent-consistent empty object).
      */
    private def adjacentEncode(
        value: Structure.Value,
        tagKey: String,
        contentKey: String,
        resolveWire: String => String
    ): Structure.Value =
        value match
            case Structure.Value.Record(fields) if fields.size == 1 =>
                val (variantName, payload) = fields.head
                val wireName               = resolveWire(variantName)
                Structure.Value.Record(Chunk(
                    (tagKey, Structure.Value.Str(wireName)),
                    (contentKey, payload)
                ))
            case other => other
    end adjacentEncode

    /** Tuple: rewrite the single-field wrapper into the two-element positional array
      * `[wireName, payload]`. A non-object payload rides through as the second element.
      */
    private def tupleEncode(
        value: Structure.Value,
        resolveWire: String => String
    ): Structure.Value =
        value match
            case Structure.Value.Record(fields) if fields.size == 1 =>
                val (variantName, payload) = fields.head
                val wireName               = resolveWire(variantName)
                Structure.Value.Sequence(Chunk(Structure.Value.Str(wireName), payload))
            case other => other
    end tupleEncode

    /** TupleFlat: rewrite the single-field wrapper into the positional-flattened array
      * `[wireName, field0Value, field1Value, ...]`. The payload Record's field Chunk is in
      * declaration order, so each field value becomes its own element in that order; field names are
      * dropped. A field that is itself a record passes through as one nested element (not
      * deep-flattened). A zero-field variant yields the tag-only array `[wireName]`.
      */
    private def tupleFlatEncode(
        value: Structure.Value,
        resolveWire: String => String
    ): Structure.Value =
        value match
            case Structure.Value.Record(fields) if fields.size == 1 =>
                val (variantName, payload) = fields.head
                val wireName               = resolveWire(variantName)
                val fieldValues = payload match
                    case Structure.Value.Record(payloadFields) => payloadFields.map(_._2)
                    case _                                     => Chunk.empty
                Structure.Value.Sequence(Structure.Value.Str(wireName) +: fieldValues)
            case other => other
    end tupleFlatEncode

    /** Untagged: drop the wrapper entirely and emit the bare payload. The tag resolver is not
      * consulted (there is no tag).
      */
    private def untaggedEncode(value: Structure.Value): Structure.Value =
        value match
            case Structure.Value.Record(fields) if fields.size == 1 =>
                fields.head._2
            case other => other
    end untaggedEncode

    /** Builds the variant forward resolver: explicit pair wins, then the renameAll
      * convention, else the raw Scala name. Collision among convention-derived names is
      * checked here, at the first serialize where the full derived name set is known.
      */
    private def resolveVariantWire[A](schema: Schema[A]): String => String =
        given Frame       = Frame.internal
        val naming        = schema.variantNaming
        val explicitPairs = naming.variantPairs.toMap
        val conventionFn  = naming.variantCase.map(nc => NameCaseConversion.convert(nc))
        if conventionFn.nonEmpty then
            val variantNames = Schema.variantScalaNames(schema.structure)
            val derived      = variantNames.map(n => n -> explicitPairs.getOrElse(n, conventionFn.get(n)))
            val byWire       = derived.groupBy(_._2)
            byWire.foreach { (wire, group) =>
                val sources = group.map(_._1).distinct
                if sources.size > 1 then throw VariantNameCollisionException(wire, Chunk.from(sources.toSeq.sorted))
            }
        end if
        (scalaName: String) =>
            explicitPairs.get(scalaName)
                .orElse(conventionFn.fold(None: Option[String])(fn => Some(fn(scalaName))))
                .getOrElse(scalaName)
    end resolveVariantWire

    /** Rewrites field-name keys by the renameAll-fields convention. A field already moved
      * by an explicit `rename` (its target name is in `renamedTargetNames`) keeps its
      * renamed key; only un-renamed source fields are convention-mapped. Collision among
      * convention-derived keys raises `FieldNameCollisionException` (first-serialize).
      */
    private def applyFieldConvention[A](
        schema: Schema[A],
        renamedTargetNames: Set[String],
        fields: Chunk[(String, Structure.Value)]
    )(using Frame): Chunk[(String, Structure.Value)] =
        schema.variantNaming.fieldCase match
            case Maybe.Present(nc) =>
                val fn = NameCaseConversion.convert(nc)
                val mapped = fields.map { (name, v) =>
                    if renamedTargetNames.contains(name) then (name, name, v)
                    else (fn(name), name, v)
                }
                val byWire = mapped.groupBy(_._1)
                byWire.foreach { (wire, group) =>
                    if group.size > 1 then
                        throw FieldNameCollisionException(wire, Chunk.from(group.map(_._2).distinct.sorted))
                }
                mapped.map((wire, _, v) => (wire, v))
            case _ =>
                fields
    end applyFieldConvention

    /** Reads a value from a Reader, dispatching to direct or transform-aware path.
      *
      * A non-serializable Schema throws `SchemaNotSerializableException` from inside its own `serializeRead` body (the sentinel lambda
      * installed by `Schema.create`/`createFrom`/`createWithFocused`).
      */
    def readFrom[A](schema: Schema[A], reader: Reader): A =
        schema.serializeRead(reader)

    /** Transform-aware deserialization path.
      *
      * Handles renames (by reversing the rename mapping so the external field name is translated back to the original) and dropped fields
      * (by pre-populating their slots with zero values so required-field checks pass).
      */
    def readWithTransforms[A](schema: Schema[A], reader: Reader): A =
        // Build reverse rename map: external name (in JSON) -> original field name
        val forwardMap = schema.renamedFields.toMap

        @tailrec def resolveTarget(name: String): String =
            forwardMap.get(name) match
                case Some(next) => resolveTarget(next)
                case None       => name

        // renameReverse: final external name -> original source field name (for renamed fields only)
        val renameReverse: Map[String, String] =
            if schema.renamedFields.isEmpty then Map.empty
            else
                schema.sourceFields.flatMap { sf =>
                    if forwardMap.contains(sf.name) then Some(resolveTarget(sf.name) -> sf.name)
                    else None
                }.toMap

        // Merge field-convention + field-alias reverse entries from the SEPARATE naming
        // slot, AFTER the rename-derived entries (rename wins). The convention maps each
        // un-renamed source field's wire name back to the source; aliases map each alias
        // back to the source whose effective wire name is the alias target.
        val reverseMap: Map[String, String] = renameReverse ++ fieldNamingReverse(schema, renameReverse)

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

        schema.rawSerializeRead(transformReader)
    end readWithTransforms

    /** Builds the field-naming reverse entries (convention + aliases) keyed wire -> source
      * field name, from the SEPARATE variantNaming slot. A source field already covered by
      * a rename (present as a value in `renameReverse`) is left to the rename mapping.
      */
    private def fieldNamingReverse[A](schema: Schema[A], renameReverse: Map[String, String]): Map[String, String] =
        val naming         = schema.variantNaming
        val renamedTargets = renameReverse.values.toSet
        val conventionMap = naming.fieldCase match
            case Maybe.Present(nc) =>
                val fn = NameCaseConversion.convert(nc)
                schema.sourceFields.iterator
                    .map(_.name)
                    .filterNot(renamedTargets.contains)
                    .map(src => fn(src) -> src)
                    .toMap
            case _ => Map.empty
        // alias target -> effective source: resolve each alias's primary wire to a source.
        // renameReverse maps final wire name -> original source name for renamed fields; include
        // those so an alias registered against a rename target (e.g. alias("given","g") after
        // rename("firstName","given")) resolves on decode.
        val wireToSource = conventionMap ++ renameReverse ++ schema.sourceFields.map(sf => sf.name -> sf.name)
        val aliasMap = naming.fieldAliases.flatMap { (alias, primaryWire) =>
            wireToSource.get(primaryWire).map(src => alias -> src)
        }.toMap
        aliasMap ++ conventionMap
    end fieldNamingReverse

    /** Converts an arbitrary Scala value to Structure.Value for transform-aware serialization. */
    def anyToStructureValue(value: Any): Structure.Value =
        if isNull(value) then Structure.Value.Null
        else
            value match
                case s: String                => Structure.Value.Str(s)
                case b: Boolean               => Structure.Value.Bool(b)
                case i: Int                   => Structure.Value.Integer(i.toLong)
                case l: Long                  => Structure.Value.Integer(l)
                case d: Double                => Structure.Value.Decimal(d)
                case f: Float                 => Structure.Value.Decimal(f.toDouble)
                case s: Short                 => Structure.Value.Integer(s.toLong)
                case b: Byte                  => Structure.Value.Integer(b.toLong)
                case c: Char                  => Structure.Value.Str(c.toString)
                case bd: BigDecimal           => Structure.Value.BigNum(bd)
                case bi: BigInt               => Structure.Value.BigNum(BigDecimal(bi))
                case m: (Maybe[?] @unchecked) => m.fold(Structure.Value.Null)(v => anyToStructureValue(v))
                case o: Option[?]             => o.fold(Structure.Value.Null)(v => anyToStructureValue(v))
                case sv: Structure.Value      => sv
                case s: Iterable[?]           => Structure.Value.Sequence(Chunk.from(s.map(anyToStructureValue)))
                case other                    => Structure.Value.Str(other.toString)
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
                // Shape-aware MapEntries:
                //   * all-String keys -> JSON object with each key as a field; round-trips through the universal Record shape.
                //   * mixed/non-String keys -> array-of-pairs; non-String keys are inexpressible as JSON field names.
                val allStringKeys = entries.forall {
                    case (Structure.Value.Str(_), _) => true
                    case _                           => false
                }
                if allStringKeys then
                    writer.mapStart(entries.size)
                    entries.foreach { (k, v) =>
                        k match
                            case Structure.Value.Str(s) =>
                                writer.fieldBytes(s.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0)
                            case _ => () // unreachable; allStringKeys is true
                        end match
                        writeStructureValue(writer, v)
                    }
                    writer.mapEnd()
                else
                    writer.arrayStart(entries.size)
                    entries.foreach { (k, v) =>
                        writer.arrayStart(2)
                        writeStructureValue(writer, k)
                        writeStructureValue(writer, v)
                        writer.arrayEnd()
                    }
                    writer.arrayEnd()
                end if
            case Structure.Value.VariantCase(name, v) =>
                // Shape-aware VariantCase: single-field object whose key is the variant name. Symmetric with reading a
                // single-field object as a Record(name, value); the universal Structure.Value tree treats VariantCase
                // and Record(<single field>) as the same wire shape; round-trips through the shape-aware identity
                // Schema therefore canonicalize to Record on read.
                writer.objectStart(name, 1)
                writer.fieldBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0)
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
      * types without a known zero, returns null: this is intentional because the macro-generated decoder uses `Array[AnyRef]` with JVM
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
            else if field.tag <:< Tag[Option[Any]] then None.asInstanceOf[AnyRef]
            else if field.tag <:< Tag[Maybe[Any]] then Maybe.empty.asInstanceOf[AnyRef]
            else null // JVM null for unknown reference types: required by macro null-check protocol
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
    def readWithDiscriminator[A](schema: Schema[A], reader: Reader): A =
        val discField  = schema.discriminatorField.get
        val discReader = new DiscriminatorReader(reader, discField, reader.frame, variantReverse(schema))
        // The macro-generated sealedReadBody expects wrapper format, which DiscriminatorReader provides
        if schema.renamedFields.nonEmpty || schema.droppedFields.nonEmpty then
            readWithTransforms(schema, discReader)
        else
            schema.rawSerializeRead(discReader)
        end if
    end readWithDiscriminator

    /** Adjacent decode. Reads the two-field object, extracts the tag value, reverse-resolves it to
      * the Scala variant (accepting aliases), captures the content value, and presents
      * `{variantName: <content>}` to the macro readBody via an AdjacentReader. A missing tag key
      * raises `MissingTagKeyException`.
      */
    def readAdjacent[A](schema: Schema[A], reader: Reader, tagKey: String, contentKey: String): A =
        val adjReader = new AdjacentReader(reader, tagKey, contentKey, reader.frame, variantReverse(schema))
        if schema.renamedFields.nonEmpty || schema.droppedFields.nonEmpty then
            readWithTransforms(schema, adjReader)
        else
            schema.rawSerializeRead(adjReader)
        end if
    end readAdjacent

    /** Tuple decode. Reads the two-element array (element 0 the tag, element 1 the payload),
      * reverse-resolves element 0 to the variant (accepting aliases), captures element 1, and
      * presents `{variantName: <element1>}` to the macro readBody via a TupleReader.
      */
    def readTuple[A](schema: Schema[A], reader: Reader): A =
        val tupReader = new TupleReader(reader, reader.frame, variantReverse(schema))
        if schema.renamedFields.nonEmpty || schema.droppedFields.nonEmpty then
            readWithTransforms(schema, tupReader)
        else
            schema.rawSerializeRead(tupReader)
        end if
    end readTuple

    /** TupleFlat decode. Reads the array (element 0 the tag), reverse-resolves the variant, looks up
      * its declaration-ordered field names from the schema, captures exactly that many remaining
      * elements, and presents `{variantName: {fieldName_i: elem_{i+1}}}` (field names restored) to
      * the macro readBody via a TupleFlatReader. A remaining-element count that does not match the
      * variant's field count raises a typed `MissingFieldException` (too few) or the same decode
      * channel naming the variant arity (too many), in the Result; never a silent wrong value.
      */
    def readTupleFlat[A](schema: Schema[A], reader: Reader): A =
        val fieldsByVariant = tupleFlatFieldNames(schema.structure)
        val tfReader        = new TupleFlatReader(reader, reader.frame, variantReverse(schema), fieldsByVariant)
        if schema.renamedFields.nonEmpty || schema.droppedFields.nonEmpty then
            readWithTransforms(schema, tfReader)
        else
            schema.rawSerializeRead(tfReader)
        end if
    end readTupleFlat

    /** Untagged decode. Captures the whole payload once, materializes it to an immutable
      * Structure.Value via the codec's IntrospectingReader, then tries each variant's decoder in
      * declaration order over a FRESH StructureValueReader per attempt (non-destructive),
      * returning the first that decodes without a DecodeException. No match raises
      * NoVariantMatchException listing the attempted variant wire names. A non-self-describing
      * reader (Protobuf) surfaces the existing self-describing-reader typed failure.
      */
    def readUntagged[A](schema: Schema[A], reader: Reader): A =
        given Frame = reader.frame
        val tree: Structure.Value =
            reader.captureValue() match
                case ir: Codec.IntrospectingReader => ir.readStructure()
                case _ =>
                    throw SchemaNotSerializableException(
                        "untagged decode requires a self-describing reader (Json, Yaml, Ion, MsgPack)"
                    )
        val decoders  = schema.variantDecoders
        val wireNames = untaggedVariantWireNames(schema)
        @tailrec def attempt(idx: Int): A =
            if idx >= decoders.size then
                throw NoVariantMatchException(Seq.empty, wireNames)
            else
                val fresh = new StructureValueReader(tree)
                // The per-variant decoders are heterogeneous (typed `Reader => Any` because each
                // returns a distinct variant subtype); the value a decoder returns is always one of
                // this sum's variants, which widens to A, so the cast is sound.
                val result = Result.catching[DecodeException](decoders(idx)(fresh).asInstanceOf[A])
                result match
                    case Result.Success(value) => value
                    case Result.Failure(_)     => attempt(idx + 1) // this variant did not match the input; try the next
                    case Result.Panic(ex)      => throw ex         // an unexpected error is not a no-match; surface it
                end match
        attempt(0)
    end readUntagged

    /** The variant wire names in declaration order, for the NoVariantMatchException list. Reuses the
      * forward resolver so the names match what encode would emit for each variant.
      */
    private def untaggedVariantWireNames[A](schema: Schema[A]): Chunk[String] =
        val resolveWire = resolveVariantWire(schema)
        Schema.variantScalaNames(schema.structure).map(resolveWire)
    end untaggedVariantWireNames

    /** Maps each Scala variant name to its declaration-ordered field names, read from the schema's
      * materialized Structure.Type.Sum variants. Used by TupleFlat decode to restore the field
      * names the positional wire dropped and to know the expected arity.
      */
    private def tupleFlatFieldNames(structure: Structure.Type): Map[String, Chunk[String]] =
        structure match
            case Structure.Type.Sum(_, _, _, variants, _) =>
                variants.map { variant =>
                    val fieldNames = variant.variantType match
                        case Structure.Type.Product(_, _, _, fields) => fields.map(_.name)
                        case _                                       => Chunk.empty[String]
                    variant.name -> fieldNames
                }.toMap
            case _ => Map.empty
    end tupleFlatFieldNames

    /** Builds the variant reverse-map (wire primary or alias -> Scala variant name). An
      * unresolved wire string maps to itself, so it matches no variant and reaches
      * `UnknownVariantException`.
      */
    private def variantReverse[A](schema: Schema[A]): String => String =
        given Frame        = Frame.internal
        val resolveWire    = resolveVariantWire(schema)
        val variantNames   = Schema.variantScalaNames(schema.structure)
        val wireToScala    = variantNames.map(n => resolveWire(n) -> n).toMap
        val aliasToPrimary = schema.variantNaming.variantAliases.toMap
        (wire: String) =>
            wireToScala.get(wire)
                .orElse(aliasToPrimary.get(wire).flatMap(wireToScala.get))
                .getOrElse(wire)
    end variantReverse

    /** Abstract base for [[Reader]] wrappers that decode variant-tagged wire formats by delegating all
      * scalar and container reads to a captured sub-reader.
      *
      * Each concrete subclass reads the wire format once in `readWire()`, records which variant was
      * found, and then re-presents the data in the `{variantName: payload}` wrapper shape that the
      * macro-generated `sealedReadBody` expects.
      *
      * The delegation contract: after a field is parsed and its sub-reader captured, `delegateReader`
      * holds that sub-reader and `delegateDepth` tracks nested object/array/map depth within it.
      * Every scalar call and container call (arrayStart/arrayEnd/mapStart/mapEnd) is forwarded to
      * `delegateReader` when it is set. Concrete subclasses override `objectStartDirect` and
      * `objectEndDirect` (the non-delegate paths) and the field-iteration methods
      * (`field`, `fieldParse`, `matchField`, `lastFieldName`, `hasNextField`), which are specific to
      * each representation.
      */
    abstract private[kyo] class DelegatingWrapperReader(
        protected val inner: Reader,
        protected val _frame: Frame
    ) extends Reader:

        def frame: Frame = _frame

        protected var delegateReader: Maybe[Reader]   = Maybe.empty
        protected var delegateDepth: Int              = 0
        protected var phase: Int                      = 0
        protected var _parsedFieldName: Maybe[String] = Maybe.empty

        protected def objectStartDirect(): Int
        protected def objectEndDirect(): Unit

        def objectStart(): Int =
            if delegateReader.nonEmpty then
                delegateDepth += 1
                delegateReader.get.objectStart()
            else objectStartDirect()
        end objectStart

        def objectEnd(): Unit =
            if delegateReader.nonEmpty then
                delegateDepth -= 1
                delegateReader.get.objectEnd()
                if delegateDepth <= 0 then
                    delegateDepth = 0
                    delegateReader = Maybe.empty
            else objectEndDirect()
        end objectEnd

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

        def hasNextElement(): Boolean =
            if delegateReader.nonEmpty then delegateReader.get.hasNextElement() else false

        def hasNextEntry(): Boolean =
            if delegateReader.nonEmpty then delegateReader.get.hasNextEntry() else false

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
            if delegateReader.nonEmpty then delegateReader.get.isNil() else false

        def skip(): Unit =
            if delegateReader.nonEmpty then delegateReader.get.skip()

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
            if delegateReader.nonEmpty then delegateReader.get else inner.captureValue()

        override def release(): Unit = inner.release()

    end DelegatingWrapperReader

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
        _frame: Frame,
        resolveVariant: String => String
    ) extends DelegatingWrapperReader(inner, _frame):

        // Phase 0: initial, not yet started
        // Phase 1: outer object started, about to return variant name as field
        // Phase 2: variant field returned, inner object about to start
        // Phase 3: inner object started, iterating buffered fields
        // Phase 4: done
        private var variantName: Maybe[String]                     = Maybe.empty
        private var bufferedFields: Maybe[Array[(String, Reader)]] = Maybe.empty
        private var fieldIdx: Int                                  = 0
        private var innerFieldCount: Int                           = 0

        protected def objectStartDirect(): Int =
            phase match
                case 0 =>
                    // Read entire flat object, extract discriminator, buffer other fields.
                    // Use fieldParse + matchField to identify the discriminator in a wire-format-agnostic
                    // way: JSON's matchField compares parsed UTF-8 bytes, Protobuf's compares the parsed
                    // field tag's numeric ID against CodecMacro.fieldId(name). Without this routing the
                    // Protobuf path would compare numeric tag-strings (e.g. "12345") against the literal
                    // discriminator name ("type") and never find it.
                    val _                           = inner.objectStart()
                    val fields                      = scala.collection.mutable.ListBuffer[(String, Reader)]()
                    var foundVariant: Maybe[String] = Maybe.empty
                    val discFieldBytes              = discField.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    while inner.hasNextField() do
                        inner.fieldParse()
                        if inner.matchField(discFieldBytes) then
                            foundVariant = Maybe(inner.string())
                        else
                            val fname    = inner.lastFieldName()
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
        end objectStartDirect

        protected def objectEndDirect(): Unit =
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
        end objectEndDirect

        def field(): String =
            if delegateReader.nonEmpty then
                delegateReader.get.field()
            else
                phase match
                    case 1 =>
                        phase = 2
                        resolveVariant(variantName.get)
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

        override def matchField(nameBytes: Array[Byte]): Boolean =
            if delegateReader.nonEmpty && delegateDepth > 0 then
                delegateReader.get.matchField(nameBytes)
            else if _parsedFieldName.isEmpty then false
            else
                val expected = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)
                val parsed   = _parsedFieldName.get
                if parsed == expected then true
                else
                    // Wire-format-agnostic fallback: when the underlying inner reader is wire-format-tagged
                    // (e.g. Protobuf, which reports fields by their numeric tag id), the buffered "name" is a
                    // numeric string. Match it against CodecMacro.fieldId(expected) to align with the way
                    // the macro-generated case-class read body matches fields downstream.
                    try parsed.toInt == CodecMacro.fieldId(expected)
                    catch case _: NumberFormatException => false
                end if
        end matchField

        override def lastFieldName(): String =
            if delegateReader.nonEmpty && delegateDepth > 0 then
                delegateReader.get.lastFieldName()
            else
                _parsedFieldName.getOrElse("")
        end lastFieldName

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

        override def captureValue(): Reader =
            if delegateReader.nonEmpty then delegateReader.get.captureValue()
            else inner.captureValue()

    end DiscriminatorReader

    /** Reader wrapper presenting an adjacently-tagged object `{tagKey: wireName, contentKey: payload}`
      * as the wrapper format `{variantName: payload}` the macro readBody expects. Reads the object,
      * extracts the tag (reverse-resolved, aliases accepted) and captures the content sub-Reader,
      * then re-presents. A missing tag key raises MissingTagKeyException. Delegation to the captured
      * content reader uses the shared DelegatingWrapperReader scheme.
      */
    final class AdjacentReader(
        inner: Reader,
        tagKey: String,
        contentKey: String,
        _frame: Frame,
        resolveVariant: String => String
    ) extends DelegatingWrapperReader(inner, _frame):

        // state 0: not started; 1: outer wrapper open, variant field pending;
        // 2: variant field returned, content delegate about to drive; 3: done.
        private var variantName: Maybe[String] = Maybe.empty
        private var content: Maybe[Reader]     = Maybe.empty

        private def readWire(): Unit =
            val _                   = inner.objectStart()
            var tag: Maybe[String]  = Maybe.empty
            var body: Maybe[Reader] = Maybe.empty
            val tagBytes            = tagKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            val contentBytes        = contentKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            while inner.hasNextField() do
                inner.fieldParse()
                if inner.matchField(tagBytes) then tag = Maybe(inner.string())
                else if inner.matchField(contentBytes) then body = Maybe(inner.captureValue())
                else inner.skip()
                end if
            end while
            inner.objectEnd()
            if tag.isEmpty then throw MissingTagKeyException(Seq.empty, tagKey)(using _frame)
            variantName = Maybe(resolveVariant(tag.get))
            content = Maybe(body.getOrElse(new StructureValueReader(Structure.Value.Record(Chunk.empty))(using _frame)))
        end readWire

        protected def objectStartDirect(): Int =
            phase match
                case 0 =>
                    readWire()
                    phase = 1
                    1
                case _ =>
                    throw TypeMismatchException(Seq.empty, "objectStart", s"unexpected phase $phase")(using _frame)
            end match
        end objectStartDirect

        protected def objectEndDirect(): Unit = phase = 3

        def field(): String =
            if delegateReader.nonEmpty then delegateReader.get.field()
            else
                phase = 2
                delegateReader = content
                delegateDepth = 0
                variantName.get
        end field

        override def fieldParse(): Unit =
            if delegateReader.nonEmpty && delegateDepth > 0 then delegateReader.get.fieldParse()
            else
                delegateReader = content
                delegateDepth = 0
                _parsedFieldName = variantName
        end fieldParse

        override def matchField(nameBytes: Array[Byte]): Boolean =
            if delegateReader.nonEmpty && delegateDepth > 0 then delegateReader.get.matchField(nameBytes)
            else if _parsedFieldName.isEmpty then false
            else
                val expected = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)
                val ok       = _parsedFieldName.get == expected
                if ok then phase = 2
                ok
        end matchField

        override def lastFieldName(): String =
            if delegateReader.nonEmpty && delegateDepth > 0 then delegateReader.get.lastFieldName()
            else _parsedFieldName.getOrElse("")

        def hasNextField(): Boolean =
            if delegateReader.nonEmpty && delegateDepth > 0 then delegateReader.get.hasNextField()
            else phase == 1

    end AdjacentReader

    /** Reader wrapper presenting a nested positional array `[wireName, payload]` as the wrapper
      * format `{variantName: payload}`. Reads the array, reverse-resolves element 0 to the variant
      * (aliases accepted), captures element 1, then re-presents. Uses the shared DelegatingWrapperReader scheme.
      */
    final class TupleReader(
        inner: Reader,
        _frame: Frame,
        resolveVariant: String => String
    ) extends DelegatingWrapperReader(inner, _frame):

        private var variantName: Maybe[String] = Maybe.empty
        private var content: Maybe[Reader]     = Maybe.empty

        private def readWire(): Unit =
            val _ = inner.arrayStart()
            if !inner.hasNextElement() then
                throw MissingFieldException(Seq.empty, "<tuple tag>")(using _frame)
            val tag = inner.string()
            if !inner.hasNextElement() then
                throw MissingFieldException(Seq.empty, "<tuple payload>")(using _frame)
            val body = inner.captureValue()
            while inner.hasNextElement() do inner.skip()
            inner.arrayEnd()
            variantName = Maybe(resolveVariant(tag))
            content = Maybe(body)
        end readWire

        protected def objectStartDirect(): Int =
            phase match
                case 0 =>
                    readWire()
                    phase = 1
                    1
                case _ =>
                    throw TypeMismatchException(Seq.empty, "objectStart", s"unexpected phase $phase")(using _frame)
            end match
        end objectStartDirect

        protected def objectEndDirect(): Unit = phase = 3

        def field(): String =
            if delegateReader.nonEmpty then delegateReader.get.field()
            else
                phase = 2
                delegateReader = content
                delegateDepth = 0
                variantName.get
        end field

        override def fieldParse(): Unit =
            if delegateReader.nonEmpty && delegateDepth > 0 then delegateReader.get.fieldParse()
            else
                delegateReader = content
                delegateDepth = 0
                _parsedFieldName = variantName
        end fieldParse

        override def matchField(nameBytes: Array[Byte]): Boolean =
            if delegateReader.nonEmpty && delegateDepth > 0 then delegateReader.get.matchField(nameBytes)
            else if _parsedFieldName.isEmpty then false
            else
                val expected = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)
                val ok       = _parsedFieldName.get == expected
                if ok then phase = 2
                ok
        end matchField

        override def lastFieldName(): String =
            if delegateReader.nonEmpty && delegateDepth > 0 then delegateReader.get.lastFieldName()
            else _parsedFieldName.getOrElse("")

        def hasNextField(): Boolean =
            if delegateReader.nonEmpty && delegateDepth > 0 then delegateReader.get.hasNextField()
            else phase == 1

    end TupleReader

    /** Reader wrapper presenting a positional-flattened array `[wireName, f1, f2, ...]` as the wrapper
      * format `{variantName: {fieldName_i: elem_{i+1}}}` (field names restored from the schema). Reads
      * the array, reverse-resolves element 0, captures the remaining elements, checks the count
      * against the variant's known field count, and re-presents the named-field object. The inner
      * field iteration mirrors DiscriminatorReader's buffered-field state machine. Uses the shared
      * DelegatingWrapperReader scheme.
      */
    final class TupleFlatReader(
        inner: Reader,
        _frame: Frame,
        resolveVariant: String => String,
        fieldsByVariant: Map[String, Chunk[String]]
    ) extends DelegatingWrapperReader(inner, _frame):

        // state 0: not started; 1: outer wrapper open, variant field pending;
        // 2: variant field returned, inner object about to start; 3: inner object, iterating fields; 4: done.
        private var variantName: Maybe[String] = Maybe.empty
        private var fieldNames: Chunk[String]  = Chunk.empty
        private var elements: Array[Reader]    = Array.empty
        private var fieldIdx: Int              = 0

        private def readWire(): Unit =
            val _ = inner.arrayStart()
            if !inner.hasNextElement() then
                throw MissingFieldException(Seq.empty, "<tupleFlat tag>")(using _frame)
            val tag      = inner.string()
            val resolved = resolveVariant(tag)
            val expected = fieldsByVariant.getOrElse(resolved, Chunk.empty)
            val captured = scala.collection.mutable.ListBuffer[Reader]()
            while inner.hasNextElement() do captured += inner.captureValue()
            inner.arrayEnd()
            val got = captured.length
            if got < expected.size then
                throw MissingFieldException(Seq.empty, expected(got))(using _frame)
            if got > expected.size then
                throw TypeMismatchException(Seq.empty, s"$resolved arity ${expected.size}", s"$got elements")(using _frame)
            variantName = Maybe(resolved)
            fieldNames = expected
            elements = captured.toArray
        end readWire

        protected def objectStartDirect(): Int =
            phase match
                case 0 =>
                    readWire()
                    phase = 1
                    1
                case 2 =>
                    fieldIdx = 0
                    phase = 3
                    fieldNames.size
                case _ =>
                    throw TypeMismatchException(Seq.empty, "objectStart", s"unexpected phase $phase")(using _frame)
            end match
        end objectStartDirect

        protected def objectEndDirect(): Unit =
            phase match
                case 3 => phase = 4
                case 4 => ()
                case _ => throw TypeMismatchException(Seq.empty, "objectEnd", s"unexpected phase $phase")(using _frame)
            end match
        end objectEndDirect

        def field(): String =
            if delegateReader.nonEmpty then delegateReader.get.field()
            else
                phase match
                    case 1 =>
                        phase = 2
                        variantName.get
                    case 3 =>
                        if fieldIdx < fieldNames.size then
                            val name = fieldNames(fieldIdx)
                            delegateReader = Maybe(elements(fieldIdx))
                            delegateDepth = 0
                            fieldIdx += 1
                            name
                        else throw MissingFieldException(Seq.empty, "<next>")(using _frame)
                    case _ =>
                        throw TypeMismatchException(Seq.empty, "field", s"unexpected phase $phase")(using _frame)
                end match
        end field

        override def fieldParse(): Unit =
            if delegateReader.nonEmpty && delegateDepth > 0 then delegateReader.get.fieldParse()
            else
                delegateReader = Maybe.empty
                delegateDepth = 0
                if phase == 3 then
                    if fieldIdx < fieldNames.size then
                        val name = fieldNames(fieldIdx)
                        delegateReader = Maybe(elements(fieldIdx))
                        delegateDepth = 0
                        fieldIdx += 1
                        _parsedFieldName = Maybe(name)
                    else _parsedFieldName = Maybe.empty
                else _parsedFieldName = Maybe(field())
                end if
        end fieldParse

        override def matchField(nameBytes: Array[Byte]): Boolean =
            if delegateReader.nonEmpty && delegateDepth > 0 then delegateReader.get.matchField(nameBytes)
            else if _parsedFieldName.isEmpty then false
            else new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8) == _parsedFieldName.get
        end matchField

        override def lastFieldName(): String =
            if delegateReader.nonEmpty && delegateDepth > 0 then delegateReader.get.lastFieldName()
            else _parsedFieldName.getOrElse("")

        def hasNextField(): Boolean =
            if delegateReader.nonEmpty && delegateDepth > 0 then delegateReader.get.hasNextField()
            else
                delegateReader = Maybe.empty
                delegateDepth = 0
                phase match
                    case 1 => true
                    case 3 => fieldIdx < fieldNames.size
                    case _ => false
                end match
        end hasNextField

    end TupleFlatReader

    /** A [[Reader]] wrapper that applies field-name translation and dropped-field pre-population during decode.
      *
      * Used by [[readWithTransforms]] to make renamed and dropped fields transparent to the macro-generated decoder.
      *
      *   - Renames: [[fieldParse]] reads the raw external name from the inner reader and translates it to the original field name via
      *     [[reverseMap]]. Names that were renamed away (present in [[renamedSources]]) are translated to a unique sentinel so they do not
      *     match any valid field slot.
      *   - Drops: [[droppedFieldsMask]] reports the dropped-field bit positions so the macro's required-field bitmap check treats them as
      *     already satisfied and does not throw [[MissingFieldException]].
      */
    final class TransformAwareReader(
        inner: Reader,
        reverseMap: Map[String, String],
        renamedSources: Set[String],
        droppedIndices: Map[Int, Field[?, ?]]
    ) extends Reader:

        def frame: Frame = inner.frame

        // Pre-compute the dropped-field bitmask once per reader instance.
        // Bit i is set iff field index i is dropped. Indices >= 64 are clamped to 63 (see the 64-field macro limit).
        private val _droppedMask: Long =
            var m = 0L
            for (idx, _) <- droppedIndices do
                if idx >= 0 && idx < 64 then m |= (1L << idx)
            m
        end _droppedMask

        override def droppedFieldsMask(n: Int): Long =
            val innerMask = inner.droppedFieldsMask(n)
            if n >= 64 then _droppedMask | innerMask
            else (_droppedMask & ((1L << n) - 1L)) | innerMask
        end droppedFieldsMask

        override def initFields(n: Int): Array[AnyRef] = inner.initFields(n)

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

        override def lastFieldName(): String =
            _translatedField.getOrElse("")

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
