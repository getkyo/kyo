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

    final private[internal] case class SyntheticField(name: String, value: Frame => Structure.Value)

    /** What `readWithTransforms` consults for every record a schema reads, and `writeWithTransforms` for the fields it writes as null,
      * computed from the schema's slots once, on first use.
      * Rebuilding these per record cost about 14 KB per decode for a record with one renamed field, against about 200 bytes for a
      * plain record, and on Scala Native the garbage grew the heap past a 16 GB cap for a hundred thousand records.
      */
    final private[kyo] class ReadTables[A] private[internal] (
        private[internal] val layout: WireLayout,
        private[internal] val droppedIndices: Map[Int, Field[?, ?]],
        private[internal] val syntheticFields: List[SyntheticField],
        private[internal] val fieldReadOverrides: Map[WireKey.Key, (String, Schema.FieldTransform[A])],
        private[internal] val renamesFields: Boolean,
        private[internal] val absentWrittenAsNull: Chunk[String],
        private[internal] val sourceIndex: Map[String, Int],
        private[internal] val idIndex: Map[Int, Int],
        private[internal] val probeNames: Array[String],
        private[internal] val probeBytes: Array[Array[Byte]]
    ):
        private[internal] def unchanged: Boolean =
            layout.fieldReverse.isEmpty && layout.renamedAway.isEmpty && droppedIndices.isEmpty && syntheticFields.isEmpty &&
                fieldReadOverrides.isEmpty

        // Bit i is set iff field index i is dropped; indices of 64 and above have no bit (see the 64-field macro limit).
        private[internal] val droppedMask: Long =
            droppedIndices.keysIterator.foldLeft(0L)((m, idx) => if idx >= 0 && idx < 64 then m | (1L << idx) else m)
    end ReadTables

    // What a record read through a TransformAwareReader tracks to inject the fields the wire lacks: the configured values and the
    // flattened fields still to inject, the source fields read from the wire (a bit per field index) so a field that had a value is
    // not injected, and the flat keys captured per flattened field. List, not Chunk: the pending fields are drained head to tail.
    final private class Injection(var pendingSynthetic: List[SyntheticField]):
        var pendingFlattened: List[(String, Structure.Value)]                                                 = Nil
        var flattenedPrepared: Boolean                                                                        = false
        var seenMask: Long                                                                                    = 0L
        var seenWide: scala.collection.mutable.BitSet                                                         = null
        var flattenedValues: scala.collection.mutable.LinkedHashMap[String, Chunk[(String, Structure.Value)]] = null
    end Injection

    private[kyo] def readTables[A](schema: Schema[A]): ReadTables[A] =
        val layout = schema.wireLayout
        // The number a field-id codec holds a field under: its pin, or the hash of its wire key.
        def fieldNumber(source: String): Int = layout.fieldIds.getOrElse(source, CodecMacro.fieldId(source))

        // omitDefaultedNames: WhenEmpty-configured fields (per-field or schema-wide) whose missing
        // wire slot must decode to the typed empty value via synthetic field injection.
        // WhenNone fields are excluded: Option/Maybe is already seeded None by the macro.
        // Type guard: only Collection/Mapping fields qualify. An empty product also materializes
        // as an empty Record on encode; without this guard, a product field that had all its own
        // fields omitted would be added here and synthetic-injected as an empty value on decode,
        // which is wrong. Symmetric with the encode-side isEmptyOmittableCollection gate.
        val omitDefaultedNames: Set[String] =
            schema.sourceFields.iterator.filter(isCollectionOrMapTag).map(_.name).filter { name =>
                val perField = schema.omitPolicies.collectFirst { case (n, p) if n == name => p }
                perField match
                    case Some(Schema.OmitPolicy.WhenEmpty)   => true
                    case Some(Schema.OmitPolicy.WhenNone)    => false
                    case Some(Schema.OmitPolicy.When(_))     => false
                    case Some(Schema.OmitPolicy.WhenDefault) => false
                    case None                                => schema.omitEmptyCollectionsAll
                end match
            }.toSet

        val droppedIndices =
            if schema.droppedFields.isEmpty then Map.empty[Int, Field[?, ?]]
            else
                schema.sourceFields.zipWithIndex.flatMap { (field, idx) =>
                    if schema.droppedFields.contains(field.name) then Some(idx -> field)
                    else None
                }.toMap

        def materializeDefault(fieldDefault: Schema.FieldDefault, frame: Frame): Structure.Value =
            val writer = StructureValueWriter()(using frame)
            fieldDefault.writeDefault(fieldDefault.supplier(), writer)
            writer.getResult
        end materializeDefault

        val defaultByName   = schema.fieldDefaults.toMap
        val syntheticFields =
            schema.sourceFields.flatMap { field =>
                if schema.droppedFields.contains(field.name) then None
                else
                    defaultByName.get(field.name) match
                        case Some(fieldDefault) =>
                            Some(SyntheticField(field.name, materializeDefault(fieldDefault, _)))
                        case None if omitDefaultedNames.contains(field.name) =>
                            if isMappingTag(field) then
                                Some(SyntheticField(field.name, _ => emptyMappingWireValue))
                            else
                                val zero = zeroForField(field)
                                if zero == null then None
                                else Some(SyntheticField(field.name, _ => zeroToStructureValue(zero)))
                        case None =>
                            None
                    end match
            }.toList

        // Build the read-override lookup keyed by BOTH the source field name AND its numeric field id,
        // as disjoint WireKey namespaces. A self-describing codec reports the field by name, Protobuf by
        // its numeric id, so fieldParse() probes both wire forms. Each entry carries the SOURCE field name
        // alongside the transform so fieldParse() can rewrite _translatedField to the source name when the
        // match was via the id key.
        val fieldReadOverrides: Map[WireKey.Key, (String, Schema.FieldTransform[A])] =
            if schema.fieldTransforms.isEmpty then Map.empty
            else
                schema.fieldTransforms.iterator.collect {
                    case (name, t) if t.read.isDefined =>
                        Iterator[(WireKey.Key, (String, Schema.FieldTransform[A]))](
                            WireKey.name(name)            -> (name, t),
                            WireKey.id(fieldNumber(name)) -> (name, t)
                        )
                }.flatten.toMap

        // The generated read body names a failing or missing field by its source name; the wire carries it under the renamed or
        // convention-cased key. A sum's renames do not reach its variants' fields, so only a product's names are rewritten.
        val renamesFields = (layout.renamedAway.nonEmpty || schema.variantNaming.fieldCase.nonEmpty) &&
            (schema.structure match
                case _: Structure.Type.Product => true
                case _                         => false)

        val idIndex = schema.sourceFields.iterator.map(f => fieldNumber(f.name)).zipWithIndex.toMap

        // Every wire key whose name the wrapper acts on: a translated key, a renamed-away source name, a flat key, a source name, and
        // a source field's id in decimal (a read override and the seen set also match a key by id). A key a reader reports that is
        // none of these is passed through without being turned into a String.
        val probeNames = Chunk.from(
            layout.fieldReverse.keys ++ layout.renamedAway ++ schema.sourceFields.map(_.name) ++ layout.flatReverse.keys ++
                layout.ownKeys ++ idIndex.keys.map(_.toString)
        ).distinct.toArray
        // A field with a configured default: the write leaves out an absent optional field, which would read back as that default.
        // Written as null it reads back absent whatever the default is, so the default's supplier is not run to tell.
        val absentWrittenAsNull = Chunk.from(schema.fieldDefaults).collect {
            case (name, _) if !schema.droppedFields.contains(name) => name
        }

        new ReadTables(
            layout,
            droppedIndices,
            syntheticFields,
            fieldReadOverrides,
            renamesFields,
            absentWrittenAsNull,
            schema.sourceFields.iterator.map(_.name).zipWithIndex.toMap,
            idIndex,
            probeNames,
            probeNames.map(_.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        )
    end readTables

    /** Decode-time lookup key that keeps the field-name namespace and the numeric field-id namespace
      * disjoint. A self-describing codec reports a field by name; a binary codec (Protobuf) reports it
      * by its numeric field id. Registering an entry under BOTH a `ByName` and a `ByFieldId` key lets a
      * lookup by either wire form succeed, and the two opaque types make a name string and an id integer
      * un-aliasable inside one map: `ByName` is a `String` key, `ByFieldId` an `Int` key, so they occupy
      * disjoint slots even when their textual forms coincide. `ByFieldId` is opaque over `Int` (field ids
      * fit in `Int`); it boxes only on the id side and only at lookup, a short-lived non-escaping box.
      */
    private[internal] object WireKey:
        opaque type ByName    = String
        opaque type ByFieldId = Int
        type Key              = ByName | ByFieldId
        inline def name(value: String): ByName = value
        inline def id(value: Int): ByFieldId   = value
    end WireKey

    /** Parse a wire token as a non-negative numeric field id, or -1 when it is not an all-ASCII-digit id
      * string. Manual digit scan to avoid the `Option` allocation of `String.toIntOption` on the decode
      * path; a token that is not a pure id (any field name) returns -1 on the first non-digit.
      *
      * The check is deliberately strict ASCII `'0'..'9'`, NOT `Character.isDigit`: the only tokens that
      * are ever field ids come from `CodecMacro.fieldId(name).toString` or a Protobuf numeric tag, both
      * always ASCII decimal. Any other token, including a unicode name or a name made of unicode digits
      * (Arabic-Indic, fullwidth, ...), is a field NAME and must return -1 so it stays classified as a
      * name. Accepting unicode digits here would misclassify such a name as an id.
      * `DiscriminatorReader.matchField` uses this for its numeric-tag fallback for the same reason:
      * a plain field name must classify as a name without constructing a `NumberFormatException`.
      */
    /** Whether `name` is the UTF-8 text `bytes` holds. The generated read body probes every field's name in turn, so an ASCII name is
      * compared byte by byte rather than decoded into a String per probe.
      */
    private def sameName(name: String, bytes: Array[Byte]): Boolean =
        @tailrec def ascii(i: Int): Boolean =
            if i == bytes.length then true
            else
                val c = name.charAt(i)
                if c >= 0x80 || bytes(i) < 0 then name == new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                else c == bytes(i) && ascii(i + 1)
        if name.length != bytes.length then
            // a non-ASCII name's UTF-8 form is longer than its chars, so only then can the lengths differ and the names agree
            !name.forall(_ < 0x80) && name == new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
        else ascii(0)
        end if
    end sameName

    private def wireFieldId(token: String): Int =
        if token.isEmpty then -1
        else
            var i   = 0
            var acc = 0L
            while i < token.length do
                val c = token.charAt(i)
                if c < '0' || c > '9' then return -1
                acc = acc * 10 + (c - '0')
                if acc > Int.MaxValue then return -1
                i += 1
            end while
            acc.toInt
    end wireFieldId

    /** Threads a schema's field-id overrides onto a field-id-aware writer, mirroring what `Protobuf.encode`
      * does for its own entry point. `writeTo` calls this once for the outermost schema passed to
      * `Schema.encode[C]` / `Schema.encodeString[C]`; `Schema.init`'s `serializeWrite` override also
      * calls this directly for every schema reached at any nesting depth (a container element, a
      * product field, or the outermost schema itself), so a nested schema's own pin is visible to the
      * writer regardless of whether the nested schema carries a structural transform of its own.
      *
      * Gating on `supportsFieldIdOverrides` runs FIRST, before `fieldIdNameOverrides` is computed:
      * this runs at every nesting depth, so computing the overrides map unconditionally would pay
      * its rename-resolution cost at every node of every encode on codecs that cannot use the
      * result too. The gate makes the call a single virtual call for every codec without field
      * ids, with `fieldIdNameOverrides` computed only when a nonEmpty result can matter.
      *
      * Returns the writer's PRIOR override map, scoped to this call, so `restoreFieldIdOverridesForWrite`
      * can put it back once this schema's own write completes: `Schema.init`'s `serializeWrite` calls
      * this at every nesting depth, and a nested schema's own pin must not permanently replace an
      * ancestor's pin for the remainder of the ancestor's write. `Absent` means this call made no
      * change (this schema carried no overrides of its own, or the writer does not support field-id
      * overrides), so there is nothing to restore and an ambient ancestor override, if any, is left
      * untouched.
      */
    private[kyo] def threadFieldIdOverridesForWrite(schema: Schema[?], writer: Writer): Maybe[Map[String, Int]] =
        if writer.supportsFieldIdOverrides then
            val overrides = schema.fieldIdNameOverrides
            if overrides.nonEmpty then
                val prior = writer.fieldIdOverridesSnapshot
                // Installs this schema's pins on the writer: withFieldIdOverrides mutates the
                // writer's active override map (the side effect is the purpose) and returns the
                // writer for chaining, discarded here because `prior` captured above is what the
                // caller restores once this schema's write completes.
                val _ = writer.withFieldIdOverrides(overrides)
                Maybe.Present(prior)
            else Maybe.Absent
            end if
        else Maybe.Absent
    end threadFieldIdOverridesForWrite

    /** Restores a writer's field-id override state saved by `threadFieldIdOverridesForWrite`,
      * scoping a nested schema's overrides to its own `serializeWrite` call. `Absent` means the
      * matching thread call made no change, so restoring is a no-op.
      */
    private[kyo] def restoreFieldIdOverridesForWrite(writer: Writer, prior: Maybe[Map[String, Int]]): Unit =
        prior match
            case Maybe.Present(overrides) => val _ = writer.withFieldIdOverrides(overrides)
            case Maybe.Absent             => ()
    end restoreFieldIdOverridesForWrite

    /** Writes a value to a Writer, dispatching to the direct or transform-aware path.
      *
      * A non-serializable Schema throws `SchemaNotSerializableException` from inside its own `serializeWrite` body (the sentinel lambda
      * installed by `Schema.create`/`createFrom`/`createWithFocused`). No outer Maybe match is needed.
      *
      * Top-level threading: covers a hand-written Schema whose `serializeWrite` does not self-thread
      * (`Structure.scala`, `Json.scala` meta-schemas); redundant-but-harmless for every
      * `Schema.init`-derived schema, which threads again (and restores) inside `serializeWrite`. Do
      * NOT delete it; it is the only threading for the meta-schemas.
      */
    def writeTo[A](schema: Schema[A], value: A, writer: Writer)(using Frame): Unit =
        val _ = threadFieldIdOverridesForWrite(schema, writer)
        schema.serializeWrite(value, writer)

    /** Transform-aware serialization path.
      *
      * Serializes the original value to Structure.Value, applies transforms (drop/rename/add), then writes the transformed tree to the
      * target Writer.
      */
    def writeWithTransforms[A](schema: Schema[A], value: A, writer: Writer)(using Frame): Unit =
        // `Schema.init`'s `serializeWrite` override threads a schema's own field-id overrides
        // unconditionally, before branching on `hasTransforms`, for every schema reached at any
        // nesting depth (a container element, a product field, or the outermost schema passed to
        // `Schema.encode[C]` / `Protobuf.encode`). This block's own thread call is therefore
        // redundant with that upstream call on every path that reaches here (`transformedWrite` is
        // the only caller, always on the same schema instance whose `serializeWrite` already
        // threaded it). The call here is an idempotent no-op on those paths and keeps this
        // function correct standing alone for any caller that does not route through
        // `serializeWrite`.
        if writer.supportsFieldIdOverrides then
            val fieldIdOverrides = schema.fieldIdNameOverrides
            if fieldIdOverrides.nonEmpty then
                val _ = writer.withFieldIdOverrides(fieldIdOverrides)
        end if

        val layout = schema.wireLayout
        if layout.flattens && !writer.isSelfDescribing then
            throw TransformUnsupportedException(writer.codecName, "flatten")
        if layout.flatSum.nonEmpty then layout.flatSumProblem.foreach(_.raise()(using writer.frame))

        val selected     = selectRepresentation(schema, writer)
        val structWriter = StructureValueWriter(Maybe(writer), selected == Schema.UnionRepresentation.TupleFlat)
        // The raw materialization below reaches nested schemas through their serializeWrite, which
        // consults the writer's transform-override stack; carry the enclosing writer's stack so
        // recursive occurrences of a configured type keep their configuration.
        structWriter.schemaTransformOverrides = writer.schemaTransformOverrides
        schema.rawSerializeWrite(value, structWriter)
        val original = structWriter.getResult

        // Field names carrying a write-direction transform: their materialized value is produced by
        // the user-supplied transform, not by the field's own declared type, so the shape hint below
        // must not apply the declared type to them (a transform is free to change the wire shape,
        // e.g. a Map turned into a list of pairs).
        val transformOverrideNames: Set[String] =
            schema.fieldTransforms.collect { case (name, t) if t.write.isDefined => name }.toSet

        // Shape hint for writeStructureValue: this schema's own product shape with each source
        // field's wire key substituted for its declared name, so a renamed or cased field's
        // value keeps its original declared type available for the Map/object wire-shape decision.
        // Dropped fields stay keyed under their original (never-emitted) name, which is harmless: the
        // replay never looks up a name absent from the transformed tree. Transform-overridden fields
        // are excluded entirely, so their value falls back to no shape hint (today's behavior). A
        // schema whose structure is not a Product (e.g. a Sum, matching the case below that skips
        // field-transform application entirely) carries no shape hint either.
        val topShape: Maybe[Structure.Type] = schema.structure match
            case p: Structure.Type.Product =>
                Maybe(p.copy(fields =
                    p.fields.filterNot(f => transformOverrideNames.contains(f.name))
                        .map(f => f.copy(name = layout.wireOf(f.name)))
                ))
            case _ =>
                Maybe.empty

        // The map nodes in the materialized tree that were written in the pair-array framing, which
        // the replay at the end of this method honors rather than re-deciding from the entry keys
        // (see writeStructureValue). A field-transform's own writer contributes its own nodes below:
        // a transform is free to write a mapping in either framing, exactly as a bound given is.
        var framedNodes: List[Structure.Value] = structWriter.pairArrayFramedNodes

        // The record nodes a nested schema wrote with its own field-id pins installed; the replay installs them again around the
        // record's fields. A field-transform's writer contributes its own, as it does map framing.
        var overriddenNodes: List[(Structure.Value, Map[String, Int])] = structWriter.fieldIdOverriddenNodes

        // Apply per-field write overrides. For each field with a write-direction transform,
        // extract the raw Scala value, run the user-supplied writer against a fresh
        // StructureValueWriter, and capture the result. The replacement chunk feeds the
        // existing drop/omit/rename logic so those see the override value, satisfying the
        // evaluation order: transform write first, then omit predicate.
        val baseFields: Chunk[(String, Structure.Value)] = original match
            case Structure.Value.Record(originalFields) if schema.fieldTransforms.nonEmpty =>
                val overrideMap: Map[String, Structure.Value] =
                    schema.fieldTransforms.collect {
                        case (name, transform) if transform.write.isDefined =>
                            val rawFieldValue: Any = transform.get(value)
                            val fieldWriter        = StructureValueWriter(Maybe(writer))
                            transform.write.get(rawFieldValue, fieldWriter)
                            framedNodes = fieldWriter.pairArrayFramedNodes ::: framedNodes
                            overriddenNodes = fieldWriter.fieldIdOverriddenNodes ::: overriddenNodes
                            name -> fieldWriter.getResult
                    }.toMap
                if overrideMap.isEmpty then originalFields
                else
                    val replaced = originalFields.map { (name, v) => name -> overrideMap.getOrElse(name, v) }
                    // The field's own write omits an absent Maybe, but its transform still decides what is written
                    // (`Absent` as `""`, say), so a field the base write dropped takes its transform's output in its
                    // declared position: tupleFlat reads the payload by position. A `null` output is how an absent
                    // optional is written, which the base write leaves out, so it stays out.
                    val missing = overrideMap.filter { (name, written) =>
                        !originalFields.exists(_._1 == name) && written != Structure.Value.Null
                    }
                    if missing.isEmpty then replaced
                    else
                        val position = schema.sourceFields.map(_.name).zipWithIndex.toMap
                        (replaced ++ Chunk.from(missing)).sortBy((name, _) => position.getOrElse(name, Int.MaxValue))
                    end if
                end if
            case Structure.Value.Record(originalFields) =>
                originalFields
            case _ =>
                Chunk.empty

        // A field's value as its own schema wrote it, before a field transform replaced it: what WhenDefault compares to the default.
        // The base write leaves out only an absent optional, so a field it left out was absent: `Null`, as an `Absent` default is
        // materialized, and never the transform's output, which would make an `Absent` default never match.
        val sourceValueOf: (String, Structure.Value) => Structure.Value = original match
            case Structure.Value.Record(sourceFields) if schema.fieldTransforms.nonEmpty =>
                val byName = sourceFields.toMap
                (name, _) => byName.getOrElse(name, Structure.Value.Null)
            case _ => (_, written) => written

        val transformed = original match
            case Structure.Value.Record(_) =>
                schema.structure match
                    case _: Structure.Type.Product =>
                        // Field-level transforms (drop/rename/computed/convention) apply only to a product's
                        // own fields. For a sum schema the materialized Record is a single-field wrapper whose
                        // key is the variant name, not a data field; that key is governed by the variant-naming
                        // layer and must not be cased here.

                        // A field the base write left out was absent; one whose configured default is not absent is written
                        // as null in its declared position, since left out it would read back as the default.
                        val absentAsNull   = schema.readTables.absentWrittenAsNull
                        val originalFields =
                            if absentAsNull.isEmpty then baseFields
                            else
                                val missing = absentAsNull.filterNot(name => baseFields.exists(_._1 == name))
                                if missing.isEmpty then baseFields
                                else
                                    val position = schema.sourceFields.map(_.name).zipWithIndex.toMap
                                    (baseFields ++ Chunk.from(missing).map(_ -> Structure.Value.Null))
                                        .sortBy((name, _) => position.getOrElse(name, Int.MaxValue))
                                end if

                        // Transform original fields in declaration order: drop, key each by its wire key, and omit
                        // empty/absent configured fields (keyed off the SOURCE name). A renamed field keeps its
                        // position: tupleFlat reads the payload by position.
                        val transformedFields = originalFields.flatMap { (name, reflValue) =>
                            if schema.droppedFields.contains(name) then
                                Chunk.empty
                            else if !writer.writesEveryField && omitField(schema, name, reflValue, sourceValueOf(name, reflValue)) then
                                Chunk.empty // omitted: empty/absent under an effective omit policy
                            else
                                Chunk((layout.wireOf(name), reflValue))
                        }

                        // Add computed fields
                        val computedFieldValues = schema.computedFields.map { (name, compute) =>
                            (layout.wireOf(name), anyToStructureValue(compute(value)))
                        }

                        Structure.Value.Record(flattenFields(schema, value, transformedFields ++ computedFieldValues))
                    case _ =>
                        Structure.Value.Record(baseFields)
                end match
            case other =>
                other

        // Rewrite the materialized value tree into the configured wire shape. External passes
        // through (byte-identical wrapper object); the other cases rewrite at the value-tree level,
        // where a non-object payload is still intact (the flatten's non-record drop is what NOT
        // injecting into a Record avoids).
        // A catch-all value is written back in the shape it was read from, not as a variant of its own.
        val catchAllOutput: Maybe[Structure.Value] = schema.catchAll match
            case Maybe.Present(catchAll) =>
                val slots = catchAllSlots(schema, selected, catchAll)
                transformed match
                    case Structure.Value.VariantCase(name, Structure.Value.Record(fields)) if name == catchAll.variant =>
                        selected match
                            case Schema.UnionRepresentation.TagOnly  => requireTopLevelCapable(writer, "TagOnly")
                            case Schema.UnionRepresentation.Untagged => requireTopLevelCapable(writer, "Untagged")
                            case _                                   => ()
                        end match
                        Maybe(catchAllEncode(selected, slots, fields, catchAll))
                    case _ => Maybe.empty
                end match
            case _ => Maybe.empty

        val output = if catchAllOutput.nonEmpty then catchAllOutput.get
        else
            selected match
                case Schema.UnionRepresentation.External =>
                    transformed
                case Schema.UnionRepresentation.TagOnly =>
                    requireTopLevelCapable(writer, "TagOnly")
                    if schema.catchAll.isEmpty then requireTagOnlyShape(schema)
                    tagOnlyEncode(transformed, resolveVariantTag(schema))
                case Schema.UnionRepresentation.Internal(tagKey) =>
                    val merged = flattenWithDiscriminator(transformed, tagKey, resolveVariantTag(schema))
                    // the merged record is a new node holding the payload's fields, so it takes the payload's pins
                    transformed match
                        case Structure.Value.VariantCase(_, payload) =>
                            overriddenNodes.find(_._1 eq payload).foreach((_, overrides) =>
                                overriddenNodes = (merged, overrides) :: overriddenNodes
                            )
                        case _ => ()
                    end match
                    merged
                case Schema.UnionRepresentation.Adjacent(tagKey, contentKey) =>
                    adjacentEncode(transformed, tagKey, contentKey, resolveVariantTag(schema))
                case Schema.UnionRepresentation.Tuple =>
                    requireTopLevelCapable(writer, "Tuple")
                    tupleEncode(transformed, resolveVariantTag(schema))
                case Schema.UnionRepresentation.TupleFlat =>
                    requireTopLevelCapable(writer, "TupleFlat")
                    tupleFlatEncode(transformed, resolveVariantTag(schema))
                case Schema.UnionRepresentation.Untagged =>
                    requireTopLevelCapable(writer, "Untagged")
                    untaggedEncode(transformed)

        // The shape hint is only valid against the External passthrough, where output is exactly
        // transformed (the shape topShape describes); the other representations restructure the tree
        // (a discriminator key added, a tuple flattened), so no hint is threaded there and the replay
        // falls back to the object-shaped default for every Record it encounters.
        val outputShape = selected match
            case Schema.UnionRepresentation.External => topShape
            case _                                   => Maybe.empty

        // Identity, not equality: two empty mappings are equal values written by different givens.
        // The transforms above carry a field's value into the output tree by reference, so the node
        // the writer recorded is the node the replay sees.
        val pairArrayFramed: Structure.Value => Boolean =
            if framedNodes.isEmpty then noPairArrayFraming
            else node => framedNodes.exists(_ eq node)

        val fieldIdOverridesOf: Structure.Value => Maybe[Map[String, Int]] =
            if overriddenNodes.isEmpty then noFieldIdOverrides
            else node => Maybe.fromOption(overriddenNodes.collectFirst { case (n, overrides) if n eq node => overrides })

        writeStructureValue(writer, output, outputShape, pairArrayFramed, fieldIdOverridesOf)
    end writeWithTransforms

    /** True iff `sourceName`'s value should be omitted on encode under the schema's effective omit
      * policy for that field. A per-field `omitPolicies` entry shadows the schema-wide flags. A
      * `WhenNone` policy omits a `Structure.Value.Null`; a `WhenEmpty` policy omits an empty
      * `Sequence` / `MapEntries`. Schema-wide `omitNoneAll` / `omitEmptyCollectionsAll` apply to a
      * field with no per-field entry. `WhenDefault` compares `source`, the field's value before a field
      * transform, since the default is materialized from the field's Scala value; every other policy
      * tests the written `value`.
      */
    private def omitField[A](schema: Schema[A], sourceName: String, value: Structure.Value, source: Structure.Value): Boolean =
        val perField = schema.omitPolicies.collectFirst { case (n, p) if n == sourceName => p }
        perField match
            case Some(Schema.OmitPolicy.WhenNone)        => isNullValue(value)
            case Some(Schema.OmitPolicy.WhenEmpty)       => isEmptyOmittableCollection(schema, sourceName, value)
            case Some(Schema.OmitPolicy.When(predicate)) => predicate(value)
            case Some(Schema.OmitPolicy.WhenDefault)     =>
                schema.fieldMaterializedDefaults.collectFirst { case (n, default) if n == sourceName => default } match
                    case Some(default) => default == source
                    case None          => false
            case None =>
                (schema.omitNoneAll && isNullValue(value)) ||
                (schema.omitEmptyCollectionsAll && isEmptyOmittableCollection(schema, sourceName, value))
        end match
    end omitField

    /** True iff `sourceName`'s value is an empty collection or map AND its declared field type is a
      * collection or map. The declared-type check is required because an empty product or case object
      * also materializes as an empty `Structure.Value.Record`; without it, an empty nested product
      * would be wrongly omitted under `WhenEmpty` / `omitEmptyCollectionsAll`. Both the empty-shape
      * test and the declared-type predicate must hold, so an empty product (a Record whose declared
      * type is not a collection or map) never qualifies for omission.
      */
    private def isEmptyOmittableCollection[A](schema: Schema[A], sourceName: String, value: Structure.Value): Boolean =
        isEmptyCollection(value) &&
            schema.sourceFields.find(_.name == sourceName).exists(isCollectionOrMapTag)

    private def isNullValue(value: Structure.Value): Boolean = value match
        case Structure.Value.Null => true
        case _                    => false

    private def isEmptyCollection(value: Structure.Value): Boolean = value match
        case Structure.Value.Sequence(es)   => es.isEmpty
        case Structure.Value.MapEntries(es) => es.isEmpty
        // An empty Record covers Map fields encoded via mapStart/mapEnd through StructureValueWriter,
        // which produces Record rather than MapEntries. A Record with zero entries is an empty map
        // or empty object; the declared-type gate in isEmptyOmittableCollection keeps an empty
        // product from being treated as an omittable collection.
        case Structure.Value.Record(es) => es.isEmpty
        case _                          => false

    /** Selects the representation encode should emit for the active writer. With no chain, the
      * schema's single `representation`. With a chain, the highest-priority entry the writer's
      * capabilities express; if EVERY entry is inexpressible, throws
      * `RepresentationUnsupportedException` naming the codec and the joined attempted chain,
      * before any bytes are written.
      */
    private def selectRepresentation[A](schema: Schema[A], writer: Writer)(using Frame): Schema.UnionRepresentation =
        schema.representationChain match
            case Maybe.Present(chain) =>
                val caps = writer.capabilities
                chain.find(rep => Schema.representationExpressibleBy(rep, caps)) match
                    case Some(rep) => rep
                    case None      =>
                        throw RepresentationUnsupportedException(writer.codecName, chain.mkString(", "))
                end match
            case Maybe.Absent =>
                schema.representation
    end selectRepresentation

    /** Chain decode. Captures the wire once, then tries each chain entry's representation reader in
      * declared order over a FRESH reader per attempt (non-destructive replay, reusing the
      * `readUntagged` capture model). Returns the first entry that decodes without a
      * `SchemaException`; a non-`SchemaException` throwable is re-thrown as a panic, never folded
      * into a no-match. If no entry parses, the last attempt's failure surfaces (the input matched
      * no declared representation).
      */
    def readChain[A](schema: Schema[A], reader: Reader, chain: Chunk[Schema.UnionRepresentation]): A =
        given Frame  = reader.frame
        val captured = reader.captureValue() match
            case ir: Codec.IntrospectingReader => ir.readStructure()
            case _                             =>
                throw SchemaNotSerializableException(
                    "representation-chain decode requires a self-describing reader (such as: Json, Yaml, Ion, MsgPack)"
                )
        @tailrec def attempt(idx: Int): A =
            val rep   = chain(idx)
            val fresh = new StructureValueReader(captured)
            fresh.schemaTransformOverrides = reader.schemaTransformOverrides
            val result = Result.catching[SchemaException](schema.catchAll match
                case Maybe.Present(catchAll) => readWithCatchAll(schema, fresh, rep, catchAll)
                case _                       => readForRepresentation(schema, fresh, rep))
            result match
                case Result.Success(value)                       => value
                case Result.Failure(ex) if idx + 1 >= chain.size => throw ex
                case Result.Failure(_)                           => attempt(idx + 1)
                case Result.Panic(ex)                            => throw ex
            end match
        end attempt
        attempt(0)
    end readChain

    /** Dispatches a single decode attempt to the reader for one representation, reusing the existing
      * per-representation read paths (the same ones `transformedRead` dispatches to for a
      * single-representation schema). For `Internal(tagKey)`, the tag key is taken from the chain
      * entry directly (the schema may not have `discriminatorField` set when only `representations`
      * was called without `.discriminator`).
      */
    private def readForRepresentation[A](schema: Schema[A], reader: Reader, rep: Schema.UnionRepresentation): A =
        rep match
            case Schema.UnionRepresentation.External         => readWithTransforms(schema, reader)
            case Schema.UnionRepresentation.TagOnly          => readTagOnly(schema, reader)
            case Schema.UnionRepresentation.Internal(tagKey) => readWithDiscriminatorField(schema, reader, tagKey)
            case Schema.UnionRepresentation.Adjacent(tk, ck) => readAdjacent(schema, reader, tk, ck)
            case Schema.UnionRepresentation.Tuple            => readTuple(schema, reader)
            case Schema.UnionRepresentation.TupleFlat        => readTupleFlat(schema, reader)
            case Schema.UnionRepresentation.Untagged         => readUntagged(schema, reader)
    end readForRepresentation

    /** Writes a map with string-backed keys as an object, each key rendered through its own schema. */
    private[kyo] def writeStringKeyed[K, V](
        foreachEntry: ((K, V) => Unit) => Unit,
        size: Int,
        keySchema: Schema[K],
        valueSchema: Schema[V],
        writer: Writer
    ): Unit =
        given Frame = writer.frame
        writer.mapStart(size)
        var idx = 0
        foreachEntry { (k, v) =>
            val keyWriter = StructureValueWriter()
            keySchema.serializeWrite(k, keyWriter)
            val key = keyWriter.getResult match
                case Structure.Value.Str(s) => s
                case other                  => throw TransformFailedException(s"a string map key's schema wrote ${wireKind(other)}")
            writer.field(key, idx)
            valueSchema.serializeWrite(v, writer)
            idx += 1
        }
        writer.mapEnd()
    end writeStringKeyed

    /** Reads a map with string-backed keys, calling `add` per entry. The object form is read, and so is the array of `{key, value}`
      * records such a map was written as before: a self-describing reader is captured to tell the two apart, since a reader cannot
      * look ahead. A reader that is not self-describing (Protobuf) has one form for both, and is read as an object.
      */
    private[kyo] def readStringKeyed[K, V](keySchema: Schema[K], valueSchema: Schema[V], reader: Reader)(add: (K, V) => Unit): Unit =
        given Frame                 = reader.frame
        def readKey(key: String): K =
            try readCaptured(keySchema, Structure.Value.Str(key))
            catch case e: DecodeException => throw e.prependPath(key)
        reader match
            case _: Codec.IntrospectingReader =>
                reader.captureValue() match
                    case captured: Codec.IntrospectingReader =>
                        captured.readStructure() match
                            case Structure.Value.Record(fields) =>
                                fields.zipWithIndex.foreach { case ((key, value), idx) =>
                                    reader.checkCollectionSize(idx + 1)
                                    val k = readKey(key)
                                    val v =
                                        try readCaptured(valueSchema, value)
                                        catch case e: DecodeException => throw e.prependPath(key)
                                    add(k, v)
                                }
                            case Structure.Value.Sequence(elements) =>
                                elements.zipWithIndex.foreach { (element, idx) =>
                                    reader.checkCollectionSize(idx + 1)
                                    element match
                                        case Structure.Value.Record(pair) =>
                                            def side(name: String): Structure.Value =
                                                pair.collectFirst { case (`name`, v) => v }.getOrElse(
                                                    throw MissingFieldException(Seq(idx.toString), name)
                                                )
                                            val k =
                                                try readCaptured(keySchema, side("key"))
                                                catch case e: DecodeException => throw e.prependPath("key").prependPath(idx.toString)
                                            val v =
                                                try readCaptured(valueSchema, side("value"))
                                                catch case e: DecodeException => throw e.prependPath("value").prependPath(idx.toString)
                                            add(k, v)
                                        case other =>
                                            throw TypeMismatchException(Seq(idx.toString), "object", wireKind(other))
                                    end match
                                }
                            case other =>
                                throw TypeMismatchException(Seq.empty, "object", wireKind(other))
                    case other => notIntrospecting(other)
            case _ =>
                discard(reader.mapStart())
                @tailrec def loop(count: Int): Unit =
                    if reader.hasNextEntry() then
                        reader.checkCollectionSize(count)
                        val key = reader.field()
                        val k   = readKey(key)
                        add(k, readEntryAt(valueSchema, reader, key))
                        loop(count + 1)
                loop(1)
                reader.mapEnd()
        end match
    end readStringKeyed

    /** Where the catch-all takes the tag and the unmatched input under `rep`, as `Schema.catchAllPlacement` worked it out, raising
      * the reason when it does not fit.
      */
    private def catchAllSlots[A](schema: Schema[A], rep: Schema.UnionRepresentation, catchAll: CatchAll)(using Frame): (Int, Int) =
        discard(tagsOf(schema))
        schema.catchAllPlacement.getOrElse(rep, catchAllFit(schema, rep, catchAll)) match
            case Result.Success(slots)  => slots
            case Result.Failure(reason) => throw TransformFailedException(reason)
            case Result.Panic(error)    => throw error
        end match
    end catchAllSlots

    /** Where a catch-all variant takes the tag and the unmatched input under `rep` (field positions, -1 for none), or why it does not
      * fit.
      *
      * A tagged representation needs both (a two-field variant with a tag field), untagged the input alone (one field), tag-only the
      * tag alone (one tag field). The tag field is a `String`, or an `Int` or `Long` when the variants are numbered; the wrapper
      * object's key is always a name. Under tag-only no other variant may have a field: the tag-only check admits a one-field variant
      * for the catch-all builder, so one that is not the catch-all is caught here.
      */
    private[kyo] def catchAllFit[A](schema: Schema[A], rep: Schema.UnionRepresentation, catchAll: CatchAll): Result[String, (Int, Int)] =
        def unfit(needs: String): Result[String, (Int, Int)] =
            Result.fail(s"catch-all variant '${catchAll.variant}' does not fit the $rep representation, which needs $needs.")
        val tags       = schema.variantTags
        val numericTag = tags.numbered && rep != Schema.UnionRepresentation.External
        val tagField   = if numericTag then "an Int or Long field" else "a String field"
        val tagFits    = catchAll.tagIndex >= 0 && tags.catchAllTagIsNumber == numericTag
        rep match
            case Schema.UnionRepresentation.Untagged =>
                if catchAll.arity == 1 then Result.succeed((-1, 0)) else unfit("a variant with one field, which receives the whole value")
            case Schema.UnionRepresentation.TagOnly =>
                if catchAll.arity != 1 || !tagFits then unfit(s"a variant with one field, $tagField, which receives the tag")
                else
                    tagOnlyProblem(schema, Maybe(catchAll.variant)) match
                        case Maybe.Present(reason) => Result.fail(reason)
                        case _                     => Result.succeed((0, -1))
            case Schema.UnionRepresentation.Tuple | Schema.UnionRepresentation.TupleFlat =>
                unfit("no catch-all, since a positional array has no place for the unmatched input")
            case _ =>
                if catchAll.arity == 2 && tagFits then Result.succeed((catchAll.tagIndex, 1 - catchAll.tagIndex))
                else unfit(s"a variant with two fields, $tagField for the tag and one for the unmatched input")
        end match
    end catchAllFit

    /** Raises `Schema.tagOnlyProblem` for a sum with no catch-all, which tagOnly writes as names alone. */
    private def requireTagOnlyShape[A](schema: Schema[A])(using Frame): Unit =
        schema.tagOnlyProblem.foreach(reason => throw TransformFailedException(reason))

    /** Under tagOnly no variant may have a field except the catch-all. The builder admits a one-`String` variant because a catch-all
      * builder may follow it, so a variant of that shape that never became the catch-all is caught on first use.
      */
    private[kyo] def tagOnlyProblem[A](schema: Schema[A], catchAllVariant: Maybe[String]): Maybe[String] =
        val others = schema.fieldBearingVariants.filterNot(name => catchAllVariant.contains(name))
        if others.isEmpty then Maybe.empty
        else
            val exception = catchAllVariant match
                case Maybe.Present(variant) => s"and is not the catch-all variant '$variant'"
                case _                      => "and no catch-all variant is configured"
            Maybe(s"tagOnly writes each variant as its name alone; ${others.mkString(", ")} has a field $exception.")
        end if
    end tagOnlyProblem

    /** Builds the catch-all variant from its tag and the unmatched input, placed by `catchAllSlots`. */
    private def buildCatchAll(
        catchAll: CatchAll,
        slots: (Int, Int),
        tag: Maybe[Structure.Value],
        payload: Maybe[Structure.Value]
    ): Any =
        val values = Array.fill[Structure.Value](catchAll.arity)(Structure.Value.Null)
        tag.foreach(t => values(slots._1) = t)
        payload.foreach(p => values(slots._2) = p)
        catchAll.construct(Chunk.from(values))
    end buildCatchAll

    /** Decode for a sum with a catch-all variant. The value is captured whole: the catch-all receives it, so it has to be read before
      * the tag decides between it and a known variant. A known tag, or input the representation reads as malformed (a missing tag
      * key), takes the representation's own reader over the captured value. External keys are Scala variant names; every other tag
      * is a wire name or alias. Under `onFailure`, a known tag whose reader fails gives the catch-all the tag and input an unknown
      * tag would; input without a tag or content, or with a tag of the wrong kind, is left to the reader.
      */
    def readWithCatchAll[A](schema: Schema[A], reader: Reader, rep: Schema.UnionRepresentation, catchAll: CatchAll): A =
        given Frame = reader.frame
        val slots   = catchAllSlots(schema, rep, catchAll)
        reader match
            case _: Codec.IntrospectingReader =>
                reader.captureValue() match
                    case ir: Codec.IntrospectingReader => readCatchAllTree(schema, reader, rep, catchAll, slots, ir.readStructure())
                    case other                         => notIntrospecting(other)
            case _ =>
                // Without per-value types the input cannot be held for the catch-all, so the representation's own reader decodes a
                // known variant and refuses an unknown tag with a typed failure.
                readForRepresentation(schema, reader, rep)
        end match
    end readWithCatchAll

    private def readCatchAllTree[A](
        schema: Schema[A],
        reader: Reader,
        rep: Schema.UnionRepresentation,
        catchAll: CatchAll,
        slots: (Int, Int),
        tree: Structure.Value
    )(using Frame): A =
        def known(): A =
            val fresh = new StructureValueReader(tree)
            fresh.schemaTransformOverrides = reader.schemaTransformOverrides
            readForRepresentation(schema, fresh, rep)
        end known
        def caught(tag: Maybe[Structure.Value], payload: Maybe[Structure.Value]): A =
            buildCatchAll(catchAll, slots, tag, payload).asInstanceOf[A]
        // Under onFailure, input the catch-all's own fields also reject fails with the known variant's failure, the more specific one.
        def knownOrCaught(tag: Structure.Value, payload: Structure.Value): A =
            if !catchAll.onFailure then known()
            else
                Result.catching[DecodeException](known()).foldOrThrow(
                    identity,
                    failure =>
                        Result.catching[DecodeException](caught(Maybe(tag), Maybe(payload))).foldOrThrow(identity, _ => throw failure)
                )
        // The catch-all's own name is not a known tag: its encode writes the tag it holds, never its name.
        val tags       = tagsOf(schema)
        val scalaNames = tags.entries.collect { case entry if entry.tag.nonEmpty => entry.scalaName }.toSet
        // A tag of the other kind (a string for a numbered sum) is malformed input, which the representation's own reader reports.
        def unknownTag(value: Structure.Value): Boolean =
            tags.captured(value).exists(tag => !tags.known.contains(tag))
        def knownTag(value: Structure.Value): Boolean =
            tags.captured(value).exists(tags.known.contains)
        def field(fields: Chunk[(String, Structure.Value)], key: String): Maybe[Structure.Value] =
            Maybe.fromOption(fields.collectFirst { case (k, v) if k == key => v })
        (rep, tree) match
            case (Schema.UnionRepresentation.Internal(tagKey), Structure.Value.Record(fields)) =>
                field(fields, tagKey) match
                    case Maybe.Present(tag) if unknownTag(tag) => caught(Maybe(tag), Maybe(tree))
                    case Maybe.Present(tag) if knownTag(tag)   => knownOrCaught(tag, tree)
                    case _                                     => known()
            case (Schema.UnionRepresentation.Adjacent(tagKey, contentKey), Structure.Value.Record(fields)) =>
                field(fields, tagKey) match
                    case Maybe.Present(tag) if unknownTag(tag) =>
                        field(fields, contentKey) match
                            case Maybe.Present(content) => caught(Maybe(tag), Maybe(content))
                            case _                      => throw MissingFieldException(Seq.empty, contentKey)
                    case Maybe.Present(tag) if knownTag(tag) =>
                        field(fields, contentKey) match
                            case Maybe.Present(content) => knownOrCaught(tag, content)
                            case _                      => known()
                    case _ => known()
            case (Schema.UnionRepresentation.External, Structure.Value.Record(entries)) if entries.size == 1 =>
                val (key, value) = entries.head
                if scalaNames.contains(key) then knownOrCaught(Structure.Value.Str(key), value)
                else caught(Maybe(Structure.Value.Str(key)), Maybe(value))
            case (Schema.UnionRepresentation.TagOnly, tag) if unknownTag(tag) =>
                caught(Maybe(tag), Maybe.empty)
            case (Schema.UnionRepresentation.Untagged, _) =>
                Result.catching[NoVariantMatchException](known()).foldOrThrow(identity, _ => caught(Maybe.empty, Maybe(tree)))
            case _ => known()
        end match
    end readCatchAllTree

    /** The wire value of a catch-all variant: the tag and the input it holds, back in the representation's shape. A value built by
      * hand whose input disagrees is written as held. The tag must be one a tag can be, a name or a number, and a name under the
      * wrapper object, whose key it becomes.
      */
    private def catchAllEncode(
        rep: Schema.UnionRepresentation,
        slots: (Int, Int),
        fields: Chunk[(String, Structure.Value)],
        catchAll: CatchAll
    )(using Frame): Structure.Value =
        def at(index: Int): Structure.Value = if index >= 0 && index < fields.size then fields(index)._2 else Structure.Value.Null
        def refused(needs: String): Nothing =
            throw TransformFailedException(
                s"catch-all variant '${catchAll.variant}' holds the tag ${wireKind(at(slots._1))}, which $rep cannot write: it needs $needs."
            )
        def tag: Structure.Value =
            at(slots._1) match
                case held @ (_: Structure.Value.Str | _: Structure.Value.Integer) => held
                case _                                                            => refused("a name or a number")
        rep match
            case Schema.UnionRepresentation.Adjacent(tagKey, contentKey) =>
                Structure.Value.Record(Chunk(tagKey -> tag, contentKey -> at(slots._2)))
            case Schema.UnionRepresentation.External =>
                at(slots._1) match
                    case Structure.Value.Str(name) => Structure.Value.Record(Chunk(name -> at(slots._2)))
                    case _                         => refused("a name, the wrapper object's key")
            case Schema.UnionRepresentation.TagOnly => tag
            case _                                  => at(slots._2)
        end match
    end catchAllEncode

    /** Internal-format decode using an explicit tag key (as opposed to `readWithDiscriminator` which
      * reads the tag key from `schema.discriminatorField`). Used by `readForRepresentation` so that a
      * chain entry of `Internal(tagKey)` works even when the schema was configured via `representations`
      * without calling `.discriminator(tagKey)`.
      */
    private def readWithDiscriminatorField[A](schema: Schema[A], reader: Reader, tagKey: String): A =
        given Frame    = reader.frame
        val discReader = discriminatorReader(reader, tagKey, tagsOf(schema))
        if schema.renamedFields.nonEmpty || schema.droppedFields.nonEmpty then
            readWithTransforms(schema, discReader)
        else
            schema.rawSerializeRead(discReader)
        end if
    end readWithDiscriminatorField

    /** Transforms a materialized sealed trait value into flat discriminator format.
      *
      * Materialized form: `VariantCase("MTCircle", Record([("radius", 5.0)]))` Flat format:
      * `Record([("type", Str("MTCircle")), ("radius", 5.0)])`
      *
      * For case objects (variant value is an empty Record), produces: `Record([("type", Str("Active"))])`
      */
    /** Refuses a variant written with a key that a discriminator also writes beside the variant's fields: a field there would be
      * overwritten by the tag on write and taken for it on read. A catch-all variant, and a variant that is itself a sum with one, is
      * exempt: it holds the input it was read from, the tag key included, and the tag written in its place is the one that holds (see
      * `flattenWithDiscriminator`). A derived `@discriminator` sum is checked at compile time as well; this check covers a tag key a
      * builder sets and a variant laid out by its own given.
      */
    private[kyo] def tagKeyClash(schema: Schema[?], tagKeys: Chunk[String]): Maybe[BuilderProblem.Failure] =
        val names = variantNamesOf(schema)
        val clash = schema.variantSchemas.iterator.zipWithIndex.flatMap { (variantSchema, idx) =>
            val name    = names.lift(idx).getOrElse(idx.toString)
            val variant = variantSchema()
            if schema.catchAll.exists(_.variant == name) || variant.catchAll.nonEmpty then Iterator.empty
            else
                val written =
                    if WireLayout.isSum(variant) then WireLayout.sumFixedKeys(variant).map(key => key -> key)
                    else variant.wireLayout.slots.collect { case slot if !slot.flattened => slot.wire -> slot.source }
                written.iterator.collect {
                    case (key, field) if tagKeys.contains(key) =>
                        BuilderProblem.Failure.FieldCollision(key, Chunk("<discriminator>", s"$name.$field"))
                }
            end if
        }.nextOption()
        Maybe.fromOption(clash)
    end tagKeyClash

    /** A sum's variant Scala names in variant order: the names a derived sum passes, else its structure's. */
    private[kyo] def variantNamesOf(schema: Schema[?]): Chunk[String] =
        if schema.variantNames.nonEmpty then schema.variantNames else Schema.variantScalaNames(schema.structure)

    private def flattenWithDiscriminator(
        value: Structure.Value,
        discField: String,
        resolveTag: String => Structure.Value
    ): Structure.Value =
        value match
            case Structure.Value.VariantCase(variantName, innerValue) =>
                val tag = resolveTag(variantName)
                innerValue match
                    case Structure.Value.Record(innerFields) =>
                        // Flatten: discriminator field + variant's fields at same level. A variant that is itself a sum with a
                        // catch-all writes back the object it read, which may hold this key; the tag written here is the one that holds.
                        val discEntry = (discField, tag)
                        Structure.Value.Record(Chunk(discEntry) ++ innerFields.filter(_._1 != discField))
                    case _ =>
                        // Non-record variant (shouldn't happen normally, but handle gracefully)
                        val discEntry = (discField, tag)
                        Structure.Value.Record(Chunk(discEntry))
                end match
            case other =>
                // Not a variant, pass through unchanged
                other
    end flattenWithDiscriminator

    /** Raises `RepresentationUnsupportedException` before any bytes are written when the active
      * writer cannot express a top-level array / bare scalar (the shape Tuple/TupleFlat/Untagged
      * require). Capability is a positive opt-in on the writer (Codec.Writer.canWriteTopLevelNonObject).
      */
    private def requireTopLevelCapable(writer: Writer, representation: String)(using Frame): Unit =
        if !writer.canWriteTopLevelNonObject then
            throw RepresentationUnsupportedException(writer.codecName, representation)
    end requireTopLevelCapable

    /** Adjacent: rewrite the materialized VariantCase into a two-field object
      * `{tagKey: wireName, contentKey: payload}`. The payload passes through unchanged, so a
      * non-object payload (scalar/array/null) survives as the content value. A payload that is an
      * empty object writes no content key, the shape protocols such as Discord's give an optional
      * `data`; AdjacentReader reads a missing content as an empty object, so the value round-trips.
      */
    private def adjacentEncode(
        value: Structure.Value,
        tagKey: String,
        contentKey: String,
        resolveTag: String => Structure.Value
    ): Structure.Value =
        value match
            case Structure.Value.VariantCase(variantName, Structure.Value.Record(fields)) if fields.isEmpty =>
                Structure.Value.Record(Chunk((tagKey, resolveTag(variantName))))
            case Structure.Value.VariantCase(variantName, payload) =>
                Structure.Value.Record(Chunk(
                    (tagKey, resolveTag(variantName)),
                    (contentKey, payload)
                ))
            case other => other
    end adjacentEncode

    /** Tuple: rewrite the materialized VariantCase into the two-element positional array
      * `[wireName, payload]`. A non-object payload rides through as the second element.
      */
    private def tupleEncode(
        value: Structure.Value,
        resolveTag: String => Structure.Value
    ): Structure.Value =
        value match
            case Structure.Value.VariantCase(variantName, payload) =>
                Structure.Value.Sequence(Chunk(resolveTag(variantName), payload))
            case other => other
    end tupleEncode

    /** TupleFlat: rewrite the materialized VariantCase into the positional-flattened array
      * `[wireName, field0Value, field1Value, ...]`. The payload Record's field Chunk is in
      * declaration order, so each field value becomes its own element in that order; field names are
      * dropped. A field that is itself a record passes through as one nested element (not
      * deep-flattened). A zero-field variant yields the tag-only array `[wireName]`.
      */
    private def tupleFlatEncode(
        value: Structure.Value,
        resolveTag: String => Structure.Value
    ): Structure.Value =
        value match
            case Structure.Value.VariantCase(variantName, payload) =>
                val fieldValues = payload match
                    case Structure.Value.Record(payloadFields) => payloadFields.map(_._2)
                    case _                                     => Chunk.empty
                Structure.Value.Sequence(resolveTag(variantName) +: fieldValues)
            case other => other
    end tupleFlatEncode

    /** Untagged: drop the variant tag entirely and emit the bare payload. The tag resolver is not
      * consulted (there is no tag).
      */
    private def untaggedEncode(value: Structure.Value): Structure.Value =
        value match
            case Structure.Value.VariantCase(_, payload) => payload
            case other                                   => other
    end untaggedEncode

    /** TagOnly: rewrite the materialized VariantCase into its wire name. The payload is empty: the builder and the annotation admit
      * only variants without fields.
      */
    private def tagOnlyEncode(
        value: Structure.Value,
        resolveTag: String => Structure.Value
    ): Structure.Value =
        value match
            case Structure.Value.VariantCase(variantName, _) => resolveTag(variantName)
            case other                                       => other
    end tagOnlyEncode

    /** TagOnly decode. The value is captured whole, so a value of another kind is reported as such on every self-describing codec,
      * rather than as whatever each reader's `string()` raises. The variant is then read from the `{variantName: {}}` wrapper the
      * macro read body expects.
      */
    def readTagOnly[A](schema: Schema[A], reader: Reader): A =
        given Frame = reader.frame
        if schema.catchAll.isEmpty then requireTagOnlyShape(schema)
        val tree = reader.captureValue() match
            case ir: Codec.IntrospectingReader => ir.readStructure()
            case _                             =>
                throw SchemaNotSerializableException(
                    "tagOnly decode requires a self-describing reader (such as: Json, Yaml, Ion, MsgPack)"
                )
        val tags = tagsOf(schema)
        tags.captured(tree) match
            case Maybe.Present(tag) =>
                val scalaName = tags(tag)
                val wrapper   = new StructureValueReader(
                    Structure.Value.Record(Chunk(scalaName -> Structure.Value.Record(Chunk.empty)))
                )
                wrapper.schemaTransformOverrides = reader.schemaTransformOverrides
                schema.rawSerializeRead(wrapper)
            case _ =>
                throw TypeMismatchException(Seq.empty, if tags.numbered then "number" else "string", wireKind(tree))
        end match
    end readTagOnly

    private def wireKind(value: Structure.Value): String = StructureValueReader.kindOf(value)

    /** The value each variant writes as its tag. */
    private def resolveVariantTag[A](schema: Schema[A])(using Frame): String => Structure.Value =
        tagsOf(schema).written

    /** Threads a schema's field-id overrides onto a field-id-aware reader, mirroring what `Protobuf.decode`
      * does for its own entry point. `readFrom` calls this once for the outermost schema passed to
      * `Schema.decode[C]`; `Schema.init`'s `serializeRead` override also calls this directly for every
      * schema reached at any nesting depth (a container element, a product field, or the outermost
      * schema itself), so a nested schema's own pin is visible to the reader regardless of whether the
      * nested schema carries a structural transform of its own.
      *
      * Gating on `supportsFieldIdOverrides` runs FIRST, before `fieldIdNameOverrides` is computed:
      * this runs at every nesting depth, so computing the overrides map unconditionally would pay
      * its rename-resolution cost at every node of every decode on codecs that cannot use the
      * result too. The gate makes the call a single virtual call for every codec without field
      * ids, with `fieldIdNameOverrides` computed only when a nonEmpty result can matter.
      *
      * Returns the reader's PRIOR override map, scoped to this call, so `restoreFieldIdOverridesForRead`
      * can put it back once this schema's own read completes: `Schema.init`'s `serializeRead` calls
      * this at every nesting depth, and a nested schema's own pin must not permanently replace an
      * ancestor's pin for the remainder of the ancestor's read. `Absent` means this call made no
      * change (this schema carried no overrides of its own, or the reader does not support field-id
      * overrides), so there is nothing to restore and an ambient ancestor override, if any, is left
      * untouched.
      */
    private[kyo] def threadFieldIdOverridesForRead(schema: Schema[?], reader: Reader): Maybe[Map[String, Int]] =
        if reader.supportsFieldIdOverrides then
            val overrides = schema.fieldIdNameOverrides
            if overrides.nonEmpty then
                val prior = reader.fieldIdOverridesSnapshot
                // Installs this schema's pins on the reader: withFieldIdOverrides mutates the
                // reader's active override map (the side effect is the purpose) and returns the
                // reader for chaining, discarded here because `prior` captured above is what the
                // caller restores once this schema's read completes.
                val _ = reader.withFieldIdOverrides(overrides)
                Maybe.Present(prior)
            else Maybe.Absent
            end if
        else Maybe.Absent
    end threadFieldIdOverridesForRead

    /** Restores a reader's field-id override state saved by `threadFieldIdOverridesForRead`,
      * scoping a nested schema's overrides to its own `serializeRead` call. `Absent` means the
      * matching thread call made no change, so restoring is a no-op.
      */
    private[kyo] def restoreFieldIdOverridesForRead(reader: Reader, prior: Maybe[Map[String, Int]]): Unit =
        prior match
            case Maybe.Present(overrides) => val _ = reader.withFieldIdOverrides(overrides)
            case Maybe.Absent             => ()
    end restoreFieldIdOverridesForRead

    /** Reads a value from a Reader, dispatching to direct or transform-aware path.
      *
      * A non-serializable Schema throws `SchemaNotSerializableException` from inside its own `serializeRead` body (the sentinel lambda
      * installed by `Schema.create`/`createFrom`/`createWithFocused`).
      *
      * Top-level threading: covers a hand-written Schema whose `serializeRead` does not self-thread
      * (`Structure.scala`, `Json.scala` meta-schemas); redundant-but-harmless for every
      * `Schema.init`-derived schema, which threads again (and restores) inside `serializeRead`. Do
      * NOT delete it; it is the only threading for the meta-schemas.
      */
    def readFrom[A](schema: Schema[A], reader: Reader): A =
        val _ = threadFieldIdOverridesForRead(schema, reader)
        schema.serializeRead(reader)

    /** Transform-aware deserialization path.
      *
      * Handles renames (by reversing the rename mapping so the external field name is translated back to the original) and dropped fields
      * (by pre-populating their slots with zero values so required-field checks pass).
      */
    def readWithTransforms[A](schema: Schema[A], reader: Reader): A =
        // `Schema.init`'s `serializeRead` override threads a schema's own field-id overrides
        // unconditionally, before branching on `hasReadTransforms`, for every schema reached at any
        // nesting depth (a container element, a product field, or the outermost schema passed to
        // `Schema.decode[C]` / `Protobuf.decode`). This block's own thread call is therefore
        // redundant with that upstream call on every path that reaches here (`transformedRead` and
        // its `readChain`/`readWithDiscriminatorField` representation-retry helpers all operate on
        // the same schema instance whose `serializeRead` already threaded it). The call here is an
        // idempotent no-op on those paths and keeps this function correct standing alone for any
        // caller that does not route through `serializeRead`.
        if reader.supportsFieldIdOverrides then
            val fieldIdOverrides = schema.fieldIdNameOverrides
            if fieldIdOverrides.nonEmpty then
                val _ = reader.withFieldIdOverrides(fieldIdOverrides)
        end if

        val tables = schema.readTables
        val layout = tables.layout
        // Flat keys are captured as Structure values, which only an introspecting reader can produce. A reader without that
        // capability sees the flattened schema as unflattened: the nested form decodes, and no encoder writes the flat form to it.
        val flattened = layout.flattens &&
            (reader match
                case _: Codec.IntrospectingReader => true
                case _                            => false)
        if flattened && layout.flatSum.nonEmpty then layout.flatSumProblem.foreach(_.raise()(using reader.frame))
        // A flattened sum reads the whole parent record, the parent's own keys included, so the record is captured once and both the
        // parent's fields and the sum read from the captured value. A wrapper-object sum is the exception: it takes its one key as the
        // variant name, so it reads the record without the keys the parent's fields own.
        val captured: Maybe[(String, Schema[?], Structure.Value)] =
            if !flattened then Maybe.empty
            else
                layout.flatSum match
                    case Maybe.Present(WireLayout.FlatSum(parent, child)) =>
                        reader match
                            case introspecting: Codec.IntrospectingReader => Maybe((parent, child, introspecting.readStructure()))
                            case _                                        => Maybe.empty
                    case _ => Maybe.empty
        val sumInput: Maybe[(String, Structure.Value)] =
            captured.map { (parent, child, whole) =>
                (child.representation, whole) match
                    case (Schema.UnionRepresentation.External, Structure.Value.Record(fields)) =>
                        val owned =
                            Chunk.from(schema.sourceFields.map(_.name) ++ schema.computedFields.map(_._1)).filterNot(_ == parent)
                                .flatMap(name => Chunk(name, layout.wireOf(name))).toSet ++
                                layout.fieldReverse.filter(_._2 != parent).keySet ++
                                layout.flatReverse.keySet
                        (parent, Structure.Value.Record(fields.filterNot((key, _) => owned.contains(key))))
                    case _ => (parent, whole)
            }
        val source: Reader = captured match
            case Maybe.Present((_, _, whole)) => new StructureValueReader(whole)(using reader.frame)
            case _                            => reader

        val transformReader =
            if !schema.denyUnknownFieldsEnabled && tables.unchanged && !flattened then source
            else
                source match
                    case _: Codec.IntrospectingReader =>
                        new TransformAwareReader(source, tables, schema.denyUnknownFieldsEnabled, flattened, sumInput)
                            with IntrospectingWrapper
                    case _ =>
                        new TransformAwareReader(source, tables, schema.denyUnknownFieldsEnabled, flattened, sumInput)

        val renamesFields = tables.renamesFields
        // A flattened field's keys sit at the parent level, so a failure inside one is located by the key the input holds there, without
        // the field the value is held in: a flattened record's key through the layout, a flattened sum's as its variant names it.
        val sumParent = sumInput.map(_._1)
        val flatKeys  = if flattened then layout.flatKeys else Map.empty[(String, String), String]
        if !renamesFields && sumParent.isEmpty && flatKeys.isEmpty then schema.rawSerializeRead(transformReader)
        else
            def wireOf(name: String): String =
                if !renamesFields then name
                else layout.wireOf(name)
            def located(path: Seq[String]): Seq[String] =
                path match
                    case parent +: key +: rest if flatKeys.contains((parent, key)) => flatKeys((parent, key)) +: rest
                    case parent +: rest if sumParent.contains(parent)              => rest.headOption.map(wireOf).toSeq ++ rest.drop(1)
                    case head +: rest                                              => wireOf(head) +: rest
                    case _                                                         => path
            try schema.rawSerializeRead(transformReader)
            catch
                case e: MissingFieldException =>
                    e.path match
                        case Seq() => throw MissingFieldException(e.path, wireOf(e.fieldName))(using e.frame)
                        case Seq(parent) if sumParent.contains(parent) =>
                            throw MissingFieldException(Nil, wireOf(e.fieldName))(using e.frame)
                        case Seq(parent) if flatKeys.contains((parent, e.fieldName)) =>
                            throw MissingFieldException(Nil, flatKeys((parent, e.fieldName)))(using e.frame)
                        case _ => throw e.mapPath(located)
                case e: DecodeException => throw e.mapPath(located)
            end try
        end if
    end readWithTransforms

    /** Replaces each flattened parent field's nested record with the record's own entries under their parent-level keys, the write
      * half of `flatten` whose read half is `WireLayout.route`. A parent is found under its wire key, so a flattened field that was also
      * renamed is still spliced. A key the layout does not expect would write a record the schema cannot read back, so it raises
      * instead.
      *
      * A flattened sum splices whatever record its variant wrote, its keys cased by the parent's convention. A key the parent's own
      * fields also write would appear twice, so it raises, except in a catch-all variant: that one holds the whole input it was read
      * from, the parent's keys included, and the parent's own values stand for them.
      */
    private def flattenFields[A](schema: Schema[A], value: A, fields: Chunk[(String, Structure.Value)])(using
        Frame
    ): Chunk[(String, Structure.Value)] =
        val layout = schema.wireLayout
        if !layout.flattens then fields
        else
            val sumWire = layout.flatSum.map(sum => layout.wireOf(sum.parent))
            fields.flatMap {
                case (name, written) if sumWire.contains(name) =>
                    val WireLayout.FlatSum(parent, child) = layout.flatSum.get
                    val parentKeys                        = fields.map(_._1).filterNot(_ == name).toSet
                    val isCatchAll                        = child.catchAll.exists { catchAll =>
                        val index = schema.sourceFields.indexWhere(_.name == parent)
                        value match
                            case product: Product if index >= 0 =>
                                product.productElement(index) match
                                    case variant: Product => variant.productPrefix == catchAll.variant
                                    case _                => false
                            case _ => false
                        end match
                    }
                    // A wrapper-object sum is held as its variant case, which the codec writes as a one-key record.
                    val entries = written match
                        case Structure.Value.Record(children)              => children
                        case Structure.Value.VariantCase(variant, payload) => Chunk(variant -> payload)
                        case other                                         =>
                            throw TransformFailedException(
                                s"flatten(_.$parent): the sum wrote ${other.getClass.getSimpleName}, not a record, so it has no keys to move"
                            )
                    entries.flatMap { (written, child) =>
                        val key = layout.casedFlatSumKey(written)
                        if !parentKeys.contains(key) then Chunk(key -> child)
                        else if isCatchAll then Chunk.empty
                        else
                            throw TransformFailedException(
                                s"flatten: the sum '$parent' wrote the key '$key', which the parent's field '$key' also writes"
                            )
                        end if
                    }
                // an absent optional record writes none of its keys
                case (name, Structure.Value.Null) if layout.flattenedAt(name).exists(_.optionalParent) =>
                    Chunk.empty
                case (name, Structure.Value.Record(children)) if layout.flattenedAt(name).nonEmpty =>
                    val parent = layout.flattenedAt(name).get.source
                    children.map { (key, child) =>
                        layout.flatWire(parent, key) match
                            case Maybe.Present(wire) => wire -> child
                            case _                   =>
                                val expected = layout.expectedChildKeys(parent)
                                throw TransformFailedException(
                                    s"flatten: the schema of '$parent' wrote the key '$key', which is not one of its fields' wire names " +
                                        s"(${expected.toSeq.sorted.mkString(", ")}), so the flat record could not be read back"
                                )
                    }
                case other => Chunk(other)
            }
        end if
    end flattenFields

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
                case bytes: Array[Byte]       => Structure.Value.Bytes(Span.from(bytes))
                case i: java.time.Instant     => Structure.Value.Instant(i)
                case d: java.time.Duration    => Structure.Value.Duration(d)
                case m: (Maybe[?] @unchecked) => m.fold(Structure.Value.Null)(v => anyToStructureValue(v))
                case o: Option[?]             => o.fold(Structure.Value.Null)(v => anyToStructureValue(v))
                case sv: Structure.Value      => sv
                case s: Iterable[?]           => Structure.Value.Sequence(Chunk.from(s.map(anyToStructureValue)))
                case other                    => Structure.Value.Str(other.toString)
            end match
    end anyToStructureValue

    private def zeroToStructureValue(value: Any): Structure.Value =
        value match
            case m: scala.collection.Map[?, ?] =>
                Structure.Value.Record(Chunk.from(m.iterator.map((k, v) => k.toString -> anyToStructureValue(v))))
            case s: Iterable[?] =>
                Structure.Value.Sequence(Chunk.from(s.map(anyToStructureValue)))
            case other =>
                anyToStructureValue(other)
        end match
    end zeroToStructureValue

    /** Unwraps Optional layers down to the first non-Optional type. `Structure.Value` never
      * materializes a distinct node for `Optional` (the wrapped value is written directly, or the
      * field is omitted), so a Maybe/Option-wrapped field's declared shape must see through the
      * wrapper to reach the type that actually guides the Record replay below.
      */
    @tailrec private def unwrapOptionalShape(tpe: Structure.Type): Structure.Type =
        tpe match
            case Structure.Type.Optional(_, _, inner) => unwrapOptionalShape(inner)
            case other                                => other

    /** The framing answer for a caller replaying a tree it did not materialize, and so has no
      * framing to declare. See [[writeStructureValue]]'s `pairArrayFramed`.
      */
    private val noPairArrayFraming: Structure.Value => Boolean = _ => false

    private val noFieldIdOverrides: Structure.Value => Maybe[Map[String, Int]] = _ => Maybe.empty

    /** Writes a Structure.Value tree to a Writer. Reverse of StructureValueWriter.
      *
      * `shape` is an optional type hint carried down from the originating Schema's structure. It
      * exists to break an ambiguity for `Record` trees that spell a map: a wire-decoded or hand-built
      * tree carries a string-keyed map as `Record` (only `Structure.encode` produces `MapEntries`),
      * so replaying such a `Record` with no further information cannot tell it from a product. That
      * is harmless for a self-describing codec, where an object and a map write identically, but
      * Protobuf's wire encoding of the two is NOT interchangeable: an object is one nested
      * sub-message under the field's own number, while a map is a REPEATED MapEntry sub-message per
      * entry (key at field 1, value at field 2) under that same number. Replaying a map with object
      * framing corrupts the wire (each entry key becomes a bogus hash-derived field number instead of
      * a MapEntry key). When `shape` resolves to `Mapping`, the `Record` writes as a map; otherwise
      * (no hint, or a genuine `Product`) it writes as an object, matching the shape-free behavior
      * every caller other than `writeWithTransforms` still relies on.
      *
      * `pairArrayFramed` answers, for a `MapEntries` node, whether the writer that materialized the
      * tree wrote it in the pair-array framing. A map node carries no framing of its own, and a
      * replay has a `Mapping` shape only for a node it can locate in the declared structure, so this
      * is what tells the two apart wherever the shape is missing, and the transform path's replay
      * has to tell them apart: a mapping
      * field's framing belongs to the given bound at it, and rewriting it produces a document the
      * schema that wrote it cannot read back. A caller replaying a tree it did not materialize
      * (a wire-decoded or hand-built tree) has nothing to declare and passes nothing, which keeps
      * the key-type spelling below.
      *
      * `fieldIdOverridesOf` answers, for a `Record` node, the field-id pins its schema had installed when the tree was materialized.
      * A record's field numbers on a field-id codec come from the pins installed while it is written, and a replay writes every record
      * through one writer, so a nested record's own pins are installed around its fields here, or they would be lost.
      */
    def writeStructureValue(
        writer: Writer,
        value: Structure.Value,
        shape: Maybe[Structure.Type] = Maybe.empty,
        pairArrayFramed: Structure.Value => Boolean = noPairArrayFraming,
        fieldIdOverridesOf: Structure.Value => Maybe[Map[String, Int]] = noFieldIdOverrides
    ): Unit =
        value match
            case Structure.Value.Record(fields) =>
                val resolvedShape = shape.map(unwrapOptionalShape)
                resolvedShape match
                    case Maybe.Present(Structure.Type.Mapping(_, _, _, valueType, _)) =>
                        val valueShape = Maybe(unwrapOptionalShape(valueType))
                        writer.mapStart(fields.size)
                        fields.foreach { (name, v) =>
                            writer.fieldBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0)
                            writeStructureValue(writer, v, valueShape, pairArrayFramed, fieldIdOverridesOf)
                        }
                        writer.mapEnd()
                    case _ =>
                        val fieldShapes: Map[String, Structure.Type] = resolvedShape match
                            case Maybe.Present(p: Structure.Type.Product) =>
                                p.fields.iterator.map(f => f.name -> unwrapOptionalShape(f.fieldType)).toMap
                            case _ =>
                                Map.empty
                        val prior =
                            if writer.supportsFieldIdOverrides then
                                fieldIdOverridesOf(value).map { overrides =>
                                    val snapshot = writer.fieldIdOverridesSnapshot
                                    val _        = writer.withFieldIdOverrides(overrides)
                                    snapshot
                                }
                            else Maybe.empty
                        writer.objectStart("", fields.size)
                        fields.foreach { (name, v) =>
                            writer.fieldBytes(
                                name.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                CodecMacro.fieldId(name)
                            )
                            writeStructureValue(writer, v, Maybe.fromOption(fieldShapes.get(name)), pairArrayFramed, fieldIdOverridesOf)
                        }
                        writer.objectEnd()
                        prior.foreach { snapshot =>
                            val _ = writer.withFieldIdOverrides(snapshot)
                        }
                end match
            case Structure.Value.Sequence(elements) =>
                val elemShape = shape.map(unwrapOptionalShape) match
                    case Maybe.Present(Structure.Type.Collection(_, _, elem)) => Maybe(unwrapOptionalShape(elem))
                    case _                                                    => Maybe.empty
                writer.arrayStart(elements.size)
                elements.foreach(e => writeStructureValue(writer, e, elemShape, pairArrayFramed, fieldIdOverridesOf))
                writer.arrayEnd()
            case Structure.Value.MapEntries(entries) =>
                // The framing a map goes out in, in priority order:
                //   * the framing the field's own writer used, when the caller can name it
                //     (`pairArrayFramed`): a bound given's framing is part of what it encodes, so a
                //     replay that changed it would produce a document that given cannot read back.
                //   * the form the shape declares, when the caller passes a `Mapping` shape.
                //   * all-String keys -> map framing with each key as a field (a JSON object).
                //   * mixed/non-String keys -> the mapEntriesStart envelope (array of {key, value}
                //     records on wire codecs); non-String keys are inexpressible as JSON field names.
                // The last two match the typed map schemas' own spelling, so a tree with no framing
                // declared still replays byte-identically with the raw write path.
                val (keyShape, valueShape) = shape.map(unwrapOptionalShape) match
                    case Maybe.Present(Structure.Type.Mapping(_, _, k, v, _)) =>
                        (Maybe(unwrapOptionalShape(k)), Maybe(unwrapOptionalShape(v)))
                    case _ =>
                        (Maybe.empty, Maybe.empty)
                val allStringKeys = entries.forall {
                    case (Structure.Value.Str(_), _) => true
                    case _                           => false
                }
                val shapedPairs = shape.map(unwrapOptionalShape) match
                    case Maybe.Present(m: Structure.Type.Mapping) => m.form == Structure.MapForm.Pairs
                    case _                                        => false
                if allStringKeys && !pairArrayFramed(value) && !shapedPairs then
                    writer.mapStart(entries.size)
                    entries.foreach { (k, v) =>
                        k match
                            case Structure.Value.Str(s) =>
                                writer.fieldBytes(s.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0)
                            case _ => () // unreachable; allStringKeys is true
                        end match
                        writeStructureValue(writer, v, valueShape, pairArrayFramed, fieldIdOverridesOf)
                    }
                    writer.mapEnd()
                else
                    writer.mapEntriesStart(entries.size)
                    entries.foreach { (k, v) =>
                        writer.mapEntryStart()
                        writeStructureValue(writer, k, keyShape, pairArrayFramed, fieldIdOverridesOf)
                        writer.mapEntryValue()
                        writeStructureValue(writer, v, valueShape, pairArrayFramed, fieldIdOverridesOf)
                        writer.mapEntryEnd()
                    }
                    writer.mapEntriesEnd()
                end if
            case Structure.Value.VariantCase(name, v) =>
                // Shape-aware VariantCase: on wire codecs the variantStart default writes the single-field object
                // whose key is the variant name, symmetric with reading a single-field object as a Record(name, value).
                // Wire round-trips through the shape-aware identity Schema therefore canonicalize to Record on read;
                // a StructureValueWriter target keeps the VariantCase identity.
                writer.variantStart(name, name, name.getBytes(java.nio.charset.StandardCharsets.UTF_8), CodecMacro.fieldId(name))
                writeStructureValue(writer, v, Maybe.empty, pairArrayFramed, fieldIdOverridesOf)
                writer.variantEnd()
            case Structure.Value.Str(s)     => writer.string(s)
            case Structure.Value.Integer(l) =>
                if l >= Int.MinValue && l <= Int.MaxValue then writer.int(l.toInt)
                else writer.long(l)
            case Structure.Value.Decimal(d)  => writer.double(d)
            case Structure.Value.Bool(b)     => writer.boolean(b)
            case Structure.Value.BigNum(bd)  => writer.bigNumber(bd)
            case Structure.Value.Bytes(b)    => writer.bytes(b)
            case Structure.Value.Instant(i)  => writer.instant(i)
            case Structure.Value.Duration(d) => writer.duration(d)
            case Structure.Value.Null        => writer.nil()

    /** Returns a zero/default value for a dropped field so required-field null checks pass during decode.
      *
      * Uses the field's declared default if available. Otherwise derives a type-appropriate zero value from the field's tag. For reference
      * types without a known zero, returns null: this is intentional because the macro-generated decoder uses `Array[AnyRef]` with JVM
      * null checks (`values(idx) == null`) to detect missing required fields.
      */
    private def isMapTag(field: Field[?, ?]): Boolean =
        // Map[K,V] has an invariant K, so Tag[Map[String,Int]] <:< Tag[Map[Any,Any]] may not hold.
        // A show-prefix check reliably detects map types regardless of element variance.
        val show = field.tag.show
        show.startsWith("scala.collection.immutable.Map[") ||
        show.startsWith("scala.collection.Map[")
    end isMapTag

    private def isSetTag(field: Field[?, ?]): Boolean =
        // Combine Tag <:< with a show-prefix check, since variance may not propagate through Tag
        // at all element types (e.g. Set[Int] vs Set[Any]).
        val show = field.tag.show
        field.tag <:< Tag[Set[Any]] ||
        show.startsWith("scala.collection.immutable.Set[") ||
        show.startsWith("scala.collection.Set[")
    end isSetTag

    /** True iff `field`'s declared type is `OrderedDict[K, V]` or `Dict[K, V]`. Both are invariant in
      * their key and value, so `Tag[Dict[String, Int]] <:< Tag[Dict[Any, Any]]` does not hold, and
      * neither does a `scala.collection.*` show prefix, since the underlying union is erased behind
      * the opaque type. A show-prefix check against the opaque type's own qualified name is the
      * reliable discriminator, the same idiom `isMapTag` uses for its own variance gap. An opaque
      * type's show renders its arguments in brackets after the name, as an applied class type does.
      */
    private def isOrderedDictOrDictTag(field: Field[?, ?]): Boolean =
        val show = field.tag.show
        show.startsWith("(kyo.OrderedDict$package$.OrderedDict[") ||
        show.startsWith("(kyo.Dict$package$.Dict[")
    end isOrderedDictOrDictTag

    /** True iff `field`'s declared type is a mapping: `Map`, `Dict`, or `OrderedDict`. These are the
      * fields whose omitted empty value is injected as [[emptyMappingWireValue]] rather than derived
      * from a zero instance, because their wire shape depends on the bound given.
      */
    private def isMappingTag(field: Field[?, ?]): Boolean =
        isMapTag(field) || isOrderedDictOrDictTag(field)

    /** True iff `field`'s declared type is a sequence-like collection, a set, or a map (including
      * the opaque `OrderedDict`/`Dict` map types): the exact set the encode-time omit gate and the
      * decode-time synthetic-injection gate both consult, so an empty product (which also
      * materializes as an empty `Record`) is never mistaken for an empty collection. A mapping field
      * takes its decode-time empty value from [[emptyMappingWireValue]] rather than from
      * [[zeroForField]]: `Dict` and `OrderedDict` erase to a bare `Span`-backed array with no runtime
      * shape to introspect, and every mapping's wire form depends on the given bound at the field.
      */
    private def isCollectionOrMapTag(field: Field[?, ?]): Boolean =
        isMapTag(field) ||
            isOrderedDictOrDictTag(field) ||
            isSetTag(field) ||
            field.tag <:< Tag[List[Any]] ||
            field.tag <:< Tag[Vector[Any]] ||
            field.tag <:< Tag[Chunk[Any]] ||
            field.tag <:< Tag[Seq[Any]]

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
            // Map must be checked before List/Vector/Set/Chunk/Seq.
            else if isMapTag(field) then Map.empty.asInstanceOf[AnyRef]
            // List, Vector, Chunk are covariant; Tag <:< holds. Seq last (most general).
            else if field.tag <:< Tag[List[Any]] then List.empty.asInstanceOf[AnyRef]
            else if field.tag <:< Tag[Vector[Any]] then Vector.empty.asInstanceOf[AnyRef]
            else if isSetTag(field) then Set.empty.asInstanceOf[AnyRef]
            else if field.tag <:< Tag[Chunk[Any]] then Chunk.empty.asInstanceOf[AnyRef]
            else if field.tag <:< Tag[Seq[Any]] then Seq.empty.asInstanceOf[AnyRef]
            else null // JVM null for unknown reference types: required by macro null-check protocol
            end if
        end zeroFromTag
        field.default.fold(zeroFromTag)(_.asInstanceOf[AnyRef])
    end zeroForField

    /** The empty value injected on decode for an omitted mapping field, in the one wire shape that
      * reads back under either mapping form.
      *
      * A mapping's wire form belongs to the given bound at the field, not to the field's declared
      * type: for a `String` key the object form is the default, while `mapAsPairs`, `dictAsPairs` and
      * `orderedDictAsPairs` bind the array of pairs for the same key type, so a value guessed from the
      * key type is unreadable under that binding (getkyo/kyo#1748).
      *
      * It does not have to be guessed. `MapEntries` is what [[StructureValueWriter]] produces for an
      * empty mapping under BOTH forms (`mapEnd` and `mapEntriesEnd` both emit it), and
      * [[StructureValueReader]], which replays every synthetic value, accepts it under both protocols:
      * `mapStart` presents the entries as object fields and `arrayStart` as the array-of-`{key, value}`
      * envelope. So this is the empty value the field's own codec would have written, for whichever
      * form is bound.
      */
    private val emptyMappingWireValue: Structure.Value = Structure.Value.MapEntries(Chunk.empty)

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
    private def discriminatorReader(reader: Reader, discField: String, resolveVariant: VariantTags): DiscriminatorReader =
        reader match
            case _: Codec.IntrospectingReader =>
                new DiscriminatorReader(reader, discField, reader.frame, resolveVariant) with IntrospectingWrapper
            case _ => new DiscriminatorReader(reader, discField, reader.frame, resolveVariant)

    def readWithDiscriminator[A](schema: Schema[A], reader: Reader): A =
        given Frame    = reader.frame
        val discField  = schema.discriminatorField.get
        val discReader = discriminatorReader(reader, discField, tagsOf(schema))
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
        given Frame   = reader.frame
        val adjReader =
            reader match
                case _: Codec.IntrospectingReader =>
                    new AdjacentReader(reader, tagKey, contentKey, reader.frame, tagsOf(schema)) with IntrospectingWrapper
                case _ => new AdjacentReader(reader, tagKey, contentKey, reader.frame, tagsOf(schema))
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
        given Frame   = reader.frame
        val tupReader =
            reader match
                case _: Codec.IntrospectingReader => new TupleReader(reader, reader.frame, tagsOf(schema)) with IntrospectingWrapper
                case _                            => new TupleReader(reader, reader.frame, tagsOf(schema))
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
        given Frame         = reader.frame
        val fieldsByVariant = tupleFlatFieldNames(schema.structure)
        val tfReader        =
            reader match
                case _: Codec.IntrospectingReader =>
                    new TupleFlatReader(reader, reader.frame, tagsOf(schema), fieldsByVariant) with IntrospectingWrapper
                case _ => new TupleFlatReader(reader, reader.frame, tagsOf(schema), fieldsByVariant)
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
        // Type-union schemas (derived by the union arm of the macro) use multi-probe
        // to detect ambiguity. Nominal sealed sums use first-declared-wins below.
        // The discriminant is the Structure.Type.Sum name: union derivation always
        // produces "Union", while nominal sums always use the sealed type's own name.
        schema.structure match
            case Structure.Type.Sum(name, _, _, _, _, _) if name == "Union" =>
                readUnionMultiProbe(schema, reader)
            case _ =>
                given Frame               = reader.frame
                val tree: Structure.Value =
                    reader.captureValue() match
                        case ir: Codec.IntrospectingReader => ir.readStructure()
                        case _                             =>
                            throw SchemaNotSerializableException(
                                "untagged decode requires a self-describing reader (Json, Yaml, Ion, MsgPack)"
                            )
                val decoders  = schema.variantDecoders
                val wireNames = untaggedVariantWireNames(schema)
                // The catch-all takes what no other variant matches, so its own decoder is not one of the attempts.
                val catchAllIndex = schema.catchAll.map(c => Schema.variantScalaNames(schema.structure).indexOf(c.variant)).getOrElse(-1)
                @tailrec def attempt(idx: Int): A =
                    if idx >= decoders.size then
                        throw NoVariantMatchException(Seq.empty, wireNames)
                    else if idx == catchAllIndex then attempt(idx + 1)
                    else
                        val fresh = new StructureValueReader(tree)
                        fresh.schemaTransformOverrides = reader.schemaTransformOverrides
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

    /** Multi-probe untagged decode for type-union schemas.
      *
      * Captures the wire payload once, then replays a fresh StructureValueReader per member,
      * collecting every member that decodes without a DecodeException. The three-way outcome is:
      * zero matches -> NoVariantMatchException listing all attempted members;
      * exactly one match -> that value;
      * more than one match -> consult unionAmbiguityPolicy: Strict raises
      * AmbiguousVariantMatchException listing matched members; FirstMatch returns the
      * first-declared success.
      *
      * Each probe is non-destructive (fresh cursor over an immutable Structure.Value). A
      * Result.Panic from any probe is re-thrown immediately; it is never folded into a no-match.
      * Requires a self-describing codec (such as: Json, Yaml, Ion, MsgPack); a non-self-describing
      * reader raises SchemaNotSerializableException.
      */
    def readUnionMultiProbe[A](schema: Schema[A], reader: Reader): A =
        given Frame = reader.frame
        val tree    = reader.captureValue() match
            case ir: Codec.IntrospectingReader => ir.readStructure()
            case _                             =>
                throw SchemaNotSerializableException(
                    "untagged union decode requires a self-describing reader (such as: Json, Yaml, Ion, MsgPack)"
                )
        val decoders  = schema.variantDecoders
        val wireNames = untaggedVariantWireNames(schema)
        // Collect all member indices that decode successfully. Using a @tailrec accumulator
        // because we must probe every member (not short-circuit) to detect ambiguity.
        @tailrec def collect(idx: Int, acc: List[(Int, A)]): List[(Int, A)] =
            if idx >= decoders.size then acc
            else
                val fresh = new StructureValueReader(tree)
                fresh.schemaTransformOverrides = reader.schemaTransformOverrides
                val result = Result.catching[DecodeException](decoders(idx)(fresh).asInstanceOf[A])
                result match
                    case Result.Success(value) => collect(idx + 1, (idx, value) :: acc)
                    case Result.Failure(_)     => collect(idx + 1, acc)
                    case Result.Panic(ex)      => throw ex
                end match
        val matches = collect(0, Nil).reverse
        if matches.isEmpty then
            throw NoVariantMatchException(Seq.empty, wireNames)
        else if matches.sizeIs == 1 then
            matches.head._2
        else
            schema.unionAmbiguityPolicy match
                case Schema.UnionAmbiguity.FirstMatch =>
                    matches.minBy(_._1)._2
                case Schema.UnionAmbiguity.Strict =>
                    val matchedNames = Chunk.from(matches.map((i, _) => wireNames(i)))
                    throw AmbiguousVariantMatchException(Seq.empty, matchedNames)
        end if
    end readUnionMultiProbe

    /** The variant tags in declaration order, as the NoVariantMatchException list names them: what encode writes for each variant. */
    private def untaggedVariantWireNames[A](schema: Schema[A])(using Frame): Chunk[String] =
        tagsOf(schema).entries.map(entry => entry.tag.fold(entry.scalaName)(_.show))

    /** Maps each Scala variant name to its declaration-ordered field names, read from the schema's
      * materialized Structure.Type.Sum variants. Used by TupleFlat decode to restore the field
      * names the positional wire dropped and to know the expected arity.
      */
    private def tupleFlatFieldNames(structure: Structure.Type): Map[String, Chunk[String]] =
        structure match
            case Structure.Type.Sum(_, _, _, variants, _, _) =>
                variants.map { variant =>
                    val fieldNames = variant.variantType match
                        case Structure.Type.Product(_, _, _, fields, _) => fields.map(_.name)
                        case _                                          => Chunk.empty[String]
                    variant.name -> fieldNames
                }.toMap
            case _ => Map.empty
    end tupleFlatFieldNames

    /** The schema's tag table, after the checks that wait for the first codec use. */
    private def tagsOf[A](schema: Schema[A])(using Frame): VariantTags =
        val tags = schema.variantTags
        tags.checked()
        tags
    end tagsOf

    /** A reader wrapper that can read the next value as a `Structure.Value` through the reader it wraps. */
    private[internal] trait StructureSource:
        private[internal] def readStructureFromWire(): Structure.Value

    /** The introspecting face of a wrapper. A wrapper takes it only when the reader it wraps is an `IntrospectingReader`, so a wrapper
      * over a codec without per-value type tags still fails the type test that guards a `Structure.Value` decode.
      */
    private[internal] trait IntrospectingWrapper extends Codec.IntrospectingReader:
        self: StructureSource =>
        def readStructure(): Structure.Value = readStructureFromWire()
    end IntrospectingWrapper

    private def notIntrospecting(reader: Reader): Nothing =
        throw SchemaNotSerializableException(
            s"Schema[Structure.Value] requires a self-describing reader (JSON or Structure source); got ${reader.getClass.getSimpleName}"
        )(using reader.frame)

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
    ) extends Reader with StructureSource:

        def frame: Frame = _frame

        private[internal] def readStructureFromWire(): Structure.Value =
            delegateReader match
                case Present(ir: Codec.IntrospectingReader) =>
                    val value = ir.readStructure()
                    // At depth 0 the structure is the whole delegated value, as an objectStart/objectEnd pair would have consumed it, so
                    // the delegate is released the way that pair releases it; the wrapper's own objectEnd follows.
                    if delegateDepth == 0 then delegateReader = Maybe.empty
                    value
                case Present(other) => notIntrospecting(other)
                case Absent         => readStructureDirect()

        /** The next value as a `Structure.Value` when no delegate holds it. A variant schema that reads its whole record as a
          * structure, such as one flattening a sum, reads it here.
          */
        protected def readStructureDirect(): Structure.Value =
            throw TypeMismatchException(Seq.empty, "Structure.Value", s"unexpected phase $phase")(using _frame)

        // A wrapper consumes nothing of its own; whether the input is exhausted is the reader it wraps.
        private[kyo] def requireEndOfInput(): Unit = inner.requireEndOfInput()

        // Share the wrapped reader's transform-override stack: the macro read bodies receive this
        // wrapper, so a lookup or registration here must reach the same stack the enclosing
        // transformed read registered on.
        override private[kyo] def schemaTransformOverrides: List[Schema[?]]               = inner.schemaTransformOverrides
        override private[kyo] def schemaTransformOverrides_=(next: List[Schema[?]]): Unit = inner.schemaTransformOverrides = next

        protected var delegateReader: Maybe[Reader] = Maybe.empty
        protected var delegateDepth: Int            = 0

        // The field-id pins a schema read through this wrapper installs: the wrapper's own numeric-tag fallback matches against them,
        // and the captured reader it currently delegates to receives them, since that reader numbers the nested fields. The wrapped
        // reader is never changed: its fields are already read, and its pins belong to the enclosing record.
        private var fieldIdOverrides: Map[String, Int]                            = inner.fieldIdOverridesSnapshot
        override def supportsFieldIdOverrides: Boolean                            = inner.supportsFieldIdOverrides
        override def withFieldIdOverrides(overrides: Map[String, Int]): this.type =
            fieldIdOverrides = overrides
            delegateReader.foreach(reader => discard(reader.withFieldIdOverrides(overrides)))
            this
        end withFieldIdOverrides
        override def fieldIdOverridesSnapshot: Map[String, Int] = fieldIdOverrides

        /** Whether a buffered numeric wire tag is the field named `expected`, under the installed pins. */
        protected def wireTagMatches(parsed: String, expected: String): Boolean =
            wireFieldId(parsed) == fieldIdOverrides.getOrElse(expected, CodecMacro.fieldId(expected))
        protected var phase: Int                      = 0
        protected var _parsedFieldName: Maybe[String] = Maybe.empty

        // Decode-FSM phase constants (phase field stays Int for zero-overhead comparison).
        protected inline val PhaseInitial         = 0
        protected inline val PhaseOuterStarted    = 1
        protected inline val PhaseVariantReturned = 2
        protected inline val PhaseInnerStarted    = 3
        protected inline val PhaseDone            = 4

        protected def objectStartDirect(): Int
        protected def objectEndDirect(): Unit

        def objectStart(): Int =
            if delegateReader.nonEmpty then
                delegateDepth += 1
                delegateReader.get.objectStart()
            else objectStartDirect()
        end objectStart

        def objectEnd(): Unit =
            if delegateReader.nonEmpty && delegateDepth > 0 then
                delegateDepth -= 1
                delegateReader.get.objectEnd()
                if delegateDepth == 0 then delegateReader = Maybe.empty
            else
                // At depth 0 the delegate has no object open: its value was read whole (a scalar, or captured by a sum with a catch-all
                // or a discriminator), so this end closes the wrapper's own object.
                delegateReader = Maybe.empty
                objectEndDirect()
            end if
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
            delegateReader match
                // inside the delegated value, the value at the cursor is one of its parts
                case Present(reader) if delegateDepth > 0 => reader.captureValue()
                // at its start, the delegate is the value at the cursor
                case Present(reader) => reader
                case Absent          => inner.captureValue()

        override def release(): Unit = inner.release()

        override def absentDefaultedFieldsMask(n: Int, defaultableFieldsMask: Long): Long =
            delegateReader match
                case Present(reader) => reader.absentDefaultedFieldsMask(n, defaultableFieldsMask)
                case _               => inner.absentDefaultedFieldsMask(n, defaultableFieldsMask)
            end match
        end absentDefaultedFieldsMask

        override private[kyo] def missingOptionalIsAbsent: Boolean =
            delegateReader match
                case Present(reader) => reader.missingOptionalIsAbsent
                case _               => inner.missingOptionalIsAbsent

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
    class DiscriminatorReader(
        inner: Reader,
        discField: String,
        _frame: Frame,
        resolveVariant: VariantTags
    ) extends DelegatingWrapperReader(inner, _frame):

        // PhaseInitial: not yet started
        // PhaseOuterStarted: outer object started, about to return variant name as field
        // PhaseVariantReturned: variant field returned, inner object about to start
        // PhaseInnerStarted: inner object started, iterating buffered fields
        // PhaseDone: done
        private var variantName: Maybe[VariantTag]                 = Maybe.empty
        private var bufferedFields: Maybe[Array[(String, Reader)]] = Maybe.empty
        private var fieldIdx: Int                                  = 0
        private var innerFieldCount: Int                           = 0

        protected def objectStartDirect(): Int =
            phase match
                case PhaseInitial =>
                    // Read entire flat object, extract discriminator, buffer other fields.
                    // Use fieldParse + matchField to identify the discriminator in a wire-format-agnostic
                    // way: JSON's matchField compares parsed UTF-8 bytes, Protobuf's compares the parsed
                    // field tag's numeric ID against CodecMacro.fieldId(name). Without this routing the
                    // Protobuf path would compare numeric tag-strings (e.g. "12345") against the literal
                    // discriminator name ("type") and never find it.
                    val _                               = inner.objectStart()
                    val fields                          = scala.collection.mutable.ListBuffer[(String, Reader)]()
                    var foundVariant: Maybe[VariantTag] = Maybe.empty
                    val discFieldBytes                  = discField.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    while inner.hasNextField() do
                        inner.fieldParse()
                        if inner.matchField(discFieldBytes) then
                            foundVariant = Maybe(resolveVariant.read(inner))
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
                    phase = PhaseOuterStarted
                    1 // outer wrapper has 1 field (the variant name)

                case PhaseVariantReturned =>
                    // Inner variant object
                    fieldIdx = 0
                    phase = PhaseInnerStarted
                    innerFieldCount

                case _ =>
                    throw TypeMismatchException(Seq.empty, "objectStart", s"unexpected phase $phase")(using _frame)
            end match
        end objectStartDirect

        protected def objectEndDirect(): Unit =
            phase match
                case PhaseInnerStarted =>
                    // End of inner variant object
                    phase = PhaseDone
                case PhaseDone =>
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
                    case PhaseOuterStarted =>
                        phase = PhaseVariantReturned
                        resolveVariant(variantName.get)(using _frame)
                    case PhaseInnerStarted =>
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
                if phase == PhaseInnerStarted then
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
                    // numeric string. Match it against the field's number (its pin, else CodecMacro.fieldId) to
                    // align with the way the macro-generated case-class read body matches fields downstream.
                    // wireFieldId returns -1 for a non-id token and a number is always positive, so a plain name
                    // never matches.
                    wireTagMatches(parsed, expected)
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
                    case PhaseOuterStarted => true
                    case PhaseInnerStarted => fieldIdx < bufferedFields.get.length
                    case _                 => false
                end match
            end if
        end hasNextField

        override def captureValue(): Reader =
            if delegateReader.nonEmpty then delegateReader.get.captureValue()
            else if phase == PhaseVariantReturned then
                // A variant that is itself a sum with a catch-all captures it whole before reading its tag.
                new StructureValueReader(variantRecord())(using _frame)
            else inner.captureValue()

        override protected def readStructureDirect(): Structure.Value =
            if phase == PhaseVariantReturned then variantRecord() else super.readStructureDirect()

        // The value at the cursor is the variant's object, which this reader holds as the buffered fields, not the inner reader's next
        // value. Reading it whole consumes the variant, as the inner objectStart/objectEnd pair would.
        private def variantRecord(): Structure.Value =
            val fields = bufferedFields.get.map { (name, captured) =>
                captured match
                    case ir: Codec.IntrospectingReader => (name, ir.readStructure())
                    case _                             =>
                        throw SchemaNotSerializableException(
                            "a sum with a catch-all variant decodes only from a self-describing reader (such as: Json, Yaml, Ion, MsgPack)"
                        )(using _frame)
            }
            phase = PhaseDone
            Structure.Value.Record(Chunk.from(fields))
        end variantRecord

    end DiscriminatorReader

    /** Reader wrapper presenting an adjacently-tagged object `{tagKey: wireName, contentKey: payload}`
      * as the wrapper format `{variantName: payload}` the macro readBody expects. Reads the object,
      * extracts the tag (reverse-resolved, aliases accepted) and captures the content sub-Reader,
      * then re-presents. A missing tag key raises MissingTagKeyException. Delegation to the captured
      * content reader uses the shared DelegatingWrapperReader scheme.
      */
    class AdjacentReader(
        inner: Reader,
        tagKey: String,
        contentKey: String,
        _frame: Frame,
        resolveVariant: VariantTags
    ) extends DelegatingWrapperReader(inner, _frame):

        // PhaseInitial: not started; PhaseOuterStarted: outer wrapper open, variant field pending;
        // PhaseVariantReturned: variant field returned, content delegate about to drive. This
        // two-level reader has no deeper state, so PhaseInnerStarted is reused as its terminal state.
        private var variantName: Maybe[String] = Maybe.empty
        private var content: Maybe[Reader]     = Maybe.empty

        private def readWire(): Unit =
            val _                      = inner.objectStart()
            var tag: Maybe[VariantTag] = Maybe.empty
            var body: Maybe[Reader]    = Maybe.empty
            val tagBytes               = tagKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            val contentBytes           = contentKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            while inner.hasNextField() do
                inner.fieldParse()
                if inner.matchField(tagBytes) then tag = Maybe(resolveVariant.read(inner))
                // A null content is no content, as `{"t":"RESUMED","d":null}` writes a variant without fields.
                else if inner.matchField(contentBytes) then body = if inner.isNil() then Maybe.empty else Maybe(inner.captureValue())
                else inner.skip()
                end if
            end while
            inner.objectEnd()
            if tag.isEmpty then throw MissingTagKeyException(Seq.empty, tagKey)(using _frame)
            variantName = Maybe(resolveVariant(tag.get)(using _frame))
            content = Maybe(body.getOrElse(new StructureValueReader(Structure.Value.Record(Chunk.empty))(using _frame)))
        end readWire

        protected def objectStartDirect(): Int =
            phase match
                case PhaseInitial =>
                    readWire()
                    phase = PhaseOuterStarted
                    1
                case _ =>
                    throw TypeMismatchException(Seq.empty, "objectStart", s"unexpected phase $phase")(using _frame)
            end match
        end objectStartDirect

        protected def objectEndDirect(): Unit = phase = PhaseInnerStarted

        def field(): String =
            if delegateReader.nonEmpty then delegateReader.get.field()
            else
                phase = PhaseVariantReturned
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
                if ok then phase = PhaseVariantReturned
                ok
        end matchField

        override def lastFieldName(): String =
            if delegateReader.nonEmpty && delegateDepth > 0 then delegateReader.get.lastFieldName()
            else _parsedFieldName.getOrElse("")

        def hasNextField(): Boolean =
            if delegateReader.nonEmpty && delegateDepth > 0 then delegateReader.get.hasNextField()
            else phase == PhaseOuterStarted

    end AdjacentReader

    /** Reader wrapper presenting a nested positional array `[wireName, payload]` as the wrapper
      * format `{variantName: payload}`. Reads the array, reverse-resolves element 0 to the variant
      * (aliases accepted), captures element 1, then re-presents. Uses the shared DelegatingWrapperReader scheme.
      */
    class TupleReader(
        inner: Reader,
        _frame: Frame,
        resolveVariant: VariantTags
    ) extends DelegatingWrapperReader(inner, _frame):

        private var variantName: Maybe[String] = Maybe.empty
        private var content: Maybe[Reader]     = Maybe.empty

        private def readWire(): Unit =
            val _ = inner.arrayStart()
            if !inner.hasNextElement() then
                throw MissingFieldException(Seq.empty, "<tuple tag>")(using _frame)
            val tag = resolveVariant.read(inner)
            if !inner.hasNextElement() then
                throw MissingFieldException(Seq.empty, "<tuple payload>")(using _frame)
            val body = inner.captureValue()
            while inner.hasNextElement() do inner.skip()
            inner.arrayEnd()
            variantName = Maybe(resolveVariant(tag)(using _frame))
            content = Maybe(body)
        end readWire

        protected def objectStartDirect(): Int =
            phase match
                case PhaseInitial =>
                    readWire()
                    phase = PhaseOuterStarted
                    1
                case _ =>
                    throw TypeMismatchException(Seq.empty, "objectStart", s"unexpected phase $phase")(using _frame)
            end match
        end objectStartDirect

        protected def objectEndDirect(): Unit = phase = PhaseInnerStarted

        def field(): String =
            if delegateReader.nonEmpty then delegateReader.get.field()
            else
                phase = PhaseVariantReturned
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
                if ok then phase = PhaseVariantReturned
                ok
        end matchField

        override def lastFieldName(): String =
            if delegateReader.nonEmpty && delegateDepth > 0 then delegateReader.get.lastFieldName()
            else _parsedFieldName.getOrElse("")

        def hasNextField(): Boolean =
            if delegateReader.nonEmpty && delegateDepth > 0 then delegateReader.get.hasNextField()
            else phase == PhaseOuterStarted

    end TupleReader

    /** Reader wrapper presenting a positional-flattened array `[wireName, f1, f2, ...]` as the wrapper
      * format `{variantName: {fieldName_i: elem_{i+1}}}` (field names restored from the schema). Reads
      * the array, reverse-resolves element 0, captures the remaining elements, checks the count
      * against the variant's known field count, and re-presents the named-field object. The inner
      * field iteration mirrors DiscriminatorReader's buffered-field state machine. Uses the shared
      * DelegatingWrapperReader scheme.
      */
    class TupleFlatReader(
        inner: Reader,
        _frame: Frame,
        resolveVariant: VariantTags,
        fieldsByVariant: Map[String, Chunk[String]]
    ) extends DelegatingWrapperReader(inner, _frame):

        // PhaseInitial: not started; PhaseOuterStarted: outer wrapper open, variant field pending;
        // PhaseVariantReturned: variant field returned, inner object about to start; PhaseInnerStarted: inner object, iterating fields; PhaseDone: done.
        private var variantName: Maybe[String] = Maybe.empty
        private var fieldNames: Chunk[String]  = Chunk.empty
        private var elements: Array[Reader]    = Array.empty
        private var fieldIdx: Int              = 0

        private def readWire(): Unit =
            val _ = inner.arrayStart()
            if !inner.hasNextElement() then
                throw MissingFieldException(Seq.empty, "<tupleFlat tag>")(using _frame)
            val tag      = resolveVariant.read(inner)
            val resolved = resolveVariant(tag)(using _frame)
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
                case PhaseInitial =>
                    readWire()
                    phase = PhaseOuterStarted
                    1
                case PhaseVariantReturned =>
                    fieldIdx = 0
                    phase = PhaseInnerStarted
                    fieldNames.size
                case _ =>
                    throw TypeMismatchException(Seq.empty, "objectStart", s"unexpected phase $phase")(using _frame)
            end match
        end objectStartDirect

        protected def objectEndDirect(): Unit =
            phase match
                case PhaseInnerStarted => phase = PhaseDone
                case PhaseDone         => ()
                case _                 => throw TypeMismatchException(Seq.empty, "objectEnd", s"unexpected phase $phase")(using _frame)
            end match
        end objectEndDirect

        def field(): String =
            if delegateReader.nonEmpty then delegateReader.get.field()
            else
                phase match
                    case PhaseOuterStarted =>
                        phase = PhaseVariantReturned
                        variantName.get
                    case PhaseInnerStarted =>
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
                if phase == PhaseInnerStarted then
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

        override private[kyo] def presentsSourceFieldNames: Boolean =
            phase == PhaseInnerStarted && delegateDepth == 0

        def hasNextField(): Boolean =
            if delegateReader.nonEmpty && delegateDepth > 0 then delegateReader.get.hasNextField()
            else
                delegateReader = Maybe.empty
                delegateDepth = 0
                phase match
                    case PhaseOuterStarted => true
                    case PhaseInnerStarted => fieldIdx < fieldNames.size
                    case _                 => false
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
    // One is built per record read, so what is fixed per schema is held through `tables` and the layout and read through defs: a
    // field per table would be copied into every instance.
    private class TransformAwareReader(
        inner: Reader,
        tables: ReadTables[?],
        denyUnknownFieldsEnabled: Boolean,
        flattened: Boolean,
        sumInput: Maybe[(String, Structure.Value)]
    ) extends Reader with StructureSource:

        private def reverseMap: Map[String, String]                                          = tables.layout.fieldReverse
        private def renamedSources: Set[String]                                              = tables.layout.renamedAway
        private def fieldReadOverrides: Map[WireKey.Key, (String, Schema.FieldTransform[?])] = tables.fieldReadOverrides
        private def sourceIndex: Map[String, Int]                                            = tables.sourceIndex
        private def idIndex: Map[Int, Int]                                                   = tables.idIndex
        private def flatReadMap: Map[String, (String, String)] = if flattened then tables.layout.flatReverse else Map.empty
        private def flatOwnKeys: Set[String]                   = if flattened then tables.layout.ownKeys else Set.empty
        private def optionalFlat: Set[String]                  = if flattened then tables.layout.optionalParents else Set.empty

        def frame: Frame = inner.frame

        private[internal] def readStructureFromWire(): Structure.Value =
            if _syntheticActive then
                val value = syntheticReader() match
                    case ir: Codec.IntrospectingReader => ir.readStructure()
                    case other                         => notIntrospecting(other)
                finishSyntheticScalar()
                value
            else
                inner match
                    case ir: Codec.IntrospectingReader => ir.readStructure()
                    case other                         => notIntrospecting(other)

        // A wrapper consumes nothing of its own; whether the input is exhausted is the reader it wraps.
        private[kyo] def requireEndOfInput(): Unit = inner.requireEndOfInput()

        // Share the wrapped reader's transform-override stack (see DelegatingWrapperReader).
        override private[kyo] def schemaTransformOverrides: List[Schema[?]]               = inner.schemaTransformOverrides
        override private[kyo] def schemaTransformOverrides_=(next: List[Schema[?]]): Unit = inner.schemaTransformOverrides = next

        // A nested field's schema installs its field-id pins on this wrapper; the wrapped reader is the one that matches field numbers.
        override def supportsFieldIdOverrides: Boolean                            = inner.supportsFieldIdOverrides
        override def withFieldIdOverrides(overrides: Map[String, Int]): this.type =
            discard(inner.withFieldIdOverrides(overrides))
            this
        override def fieldIdOverridesSnapshot: Map[String, Int] = inner.fieldIdOverridesSnapshot

        override def droppedFieldsMask(n: Int): Long =
            val innerMask = inner.droppedFieldsMask(n)
            if n >= 64 then tables.droppedMask | innerMask
            else (tables.droppedMask & ((1L << n) - 1L)) | innerMask
        end droppedFieldsMask

        override def absentDefaultedFieldsMask(n: Int, defaultableFieldsMask: Long): Long =
            inner.absentDefaultedFieldsMask(n, defaultableFieldsMask)

        override private[kyo] def missingOptionalIsAbsent: Boolean = inner.missingOptionalIsAbsent

        override def initFields(n: Int): Array[AnyRef] = inner.initFields(n)

        override def clearFields(n: Int): Unit = inner.clearFields(n)

        private var _translatedField: Maybe[String] = Maybe.empty
        private var _translatedByWrapper: Boolean   = false
        private var _matchedField: Boolean          = false
        private var _syntheticField: Boolean        = false
        private var _rawFieldName: String           = ""
        // The key is none of the names the wrapper acts on, so matching and naming it are the wrapped reader's, and `_rawFieldName`
        // is not read: it is set only where a strict schema names an unknown key.
        private var _passThrough: Boolean = false

        private var _syntheticActive: Boolean       = false
        private var _syntheticDepth: Int            = 0
        private var _syntheticReader: Maybe[Reader] = Maybe.empty
        // Null for a record with no injected and no flattened field, which then allocates none of the state they track.
        private val _injection: Injection =
            if tables.syntheticFields.nonEmpty || flattened then new Injection(tables.syntheticFields) else null

        private def markSeen(index: Int): Unit =
            if index < 64 then _injection.seenMask |= (1L << index)
            else
                if _injection.seenWide == null then _injection.seenWide = scala.collection.mutable.BitSet.empty
                _injection.seenWide += index

        private def flattenedOf(parent: String): Maybe[Chunk[(String, Structure.Value)]] =
            if _injection.flattenedValues == null then Maybe.empty else Maybe.fromOption(_injection.flattenedValues.get(parent))

        // Containers opened on the wrapped reader. The wrapper's record is level 1; a nested record, sequence or map read through this
        // wrapper sits deeper and belongs to its own schema, so there every field step goes straight to the wrapped reader: this
        // record's renames, flattened keys and injected fields apply to its own fields only. Strictness is the exception: it covers
        // the nested records too, so a nested key its record did not match is still rejected.
        private var _depth: Int     = 0
        private def nested: Boolean = _depth > 1

        // A source field counts as present on the wire when either its name key or its numeric-id key
        // was recorded, so suppression works whether the codec reported the field by name or by id.
        private def seenOnWire(sourceName: String): Boolean =
            sourceIndex.get(sourceName) match
                case Some(index) =>
                    if index < 64 then (_injection.seenMask & (1L << index)) != 0L
                    else _injection.seenWide != null && _injection.seenWide.contains(index)
                case None => false

        // Read the field name from the inner reader and translate it.
        // Records the translated field as seen so hasNextField skips injection for it.
        // When in synthetic mode, the name was already set by hasNextField; just return.
        override def fieldParse(): Unit =
            if _syntheticReader.nonEmpty then
                _syntheticReader.get.fieldParse()
            else if _syntheticActive then
                _matchedField = false
            else if nested then
                inner.fieldParse()
                if denyUnknownFieldsEnabled then _rawFieldName = inner.lastFieldName()
                _matchedField = false
                _syntheticField = false
            else
                inner.fieldParse()
                _matchedField = false
                _syntheticField = false
                val number = if denyUnknownFieldsEnabled then -1 else inner.lastFieldNumber
                if number >= 0 then parseNumberedKey(number)
                else
                    val rawName = wireKeyName()
                    _passThrough = rawName == null
                    if !_passThrough then parseNamedKey(rawName)
                end if
        end fieldParse

        // The key's name when it is one the wrapper acts on, found by matching its bytes, or null when it is none of them. A strict
        // schema names every key, since a key nothing matches is reported by name.
        private def wireKeyName(): String =
            if denyUnknownFieldsEnabled || !inner.matchesKeyBytes then inner.lastFieldName()
            else
                val probes                        = tables.probeBytes
                @tailrec def loop(i: Int): String =
                    if i >= probes.length then null
                    else if inner.matchField(probes(i)) then tables.probeNames(i)
                    else loop(i + 1)
                loop(0)
        end wireKeyName

        private def parseNamedKey(rawName: String): Unit =
            _rawFieldName = rawName
            val sourceName = inner.presentsSourceFieldNames
            // A flattened field's own name that is also one of its record's keys (a caption beside its entities) is that key: the
            // field is never written under its name, so the reader routes the key to the record instead of matching the field.
            val flatOwnKey  = !sourceName && flatReadMap.contains(rawName) && flatOwnKeys.contains(rawName)
            val renamedAway = !sourceName && (renamedSources.contains(rawName) || flatOwnKey)
            val mapped      = if sourceName then rawName else reverseMap.getOrElse(rawName, null)
            val translated  =
                if mapped != null then mapped
                else if renamedAway then "\u0000_invalid_renamed_field"
                else rawName
            _translatedByWrapper = mapped != null || renamedAway
            _translatedField = Maybe(translated)
            // Record the wire field under its name key, and additionally under its id key when the
            // token is numeric (the Protobuf path), so a present field is not later overwritten by
            // its configured default and a flattened parent is recognized on either codec.
            val translatedId = wireFieldId(translated)
            if _injection != null then
                val index = sourceIndex.getOrElse(translated, -1)
                if index >= 0 then markSeen(index)
                else if translatedId >= 0 then
                    val byId = idIndex.getOrElse(translatedId, -1)
                    if byId >= 0 then markSeen(byId)
                end if
            end if
            if fieldReadOverrides.nonEmpty then
                fieldReadOverrides.get(WireKey.name(translated)) match
                    case Some(found) => applyReadOverride(found)
                    case None => if translatedId >= 0 then fieldReadOverrides.get(WireKey.id(translatedId)).foreach(applyReadOverride)
            end if
        end parseNamedKey

        // A key reported by its field number is matched by the wrapped reader through the numbers the schema installs, so it is
        // never translated: it marks its field seen and takes its read override by number, and is named only if a failure asks.
        private def parseNumberedKey(number: Int): Unit =
            _passThrough = true
            if _injection != null then
                val byId = idIndex.getOrElse(number, -1)
                if byId >= 0 then markSeen(byId)
            if fieldReadOverrides.nonEmpty then fieldReadOverrides.get(WireKey.id(number)).foreach(applyReadOverride)
        end parseNumberedKey

        private def applyReadOverride(found: (String, Schema.FieldTransform[?])): Unit =
            val (sourceName, transform) = found
            if transform.read.isDefined then
                val rawResult =
                    try transform.read.get(inner)
                    catch case e: DecodeException => throw e.prependPath(sourceName)
                val svWriter = StructureValueWriter()(using frame)
                transform.writeDerived(rawResult, svWriter)
                _pendingSyntheticValue = svWriter.getResult
                // The field is matched by its source name, whatever key the wire held it under.
                _translatedField = Maybe(sourceName)
                _passThrough = false
                _syntheticActive = true
                _translatedByWrapper = true
                _matchedField = true
                _syntheticField = true
            end if
        end applyReadOverride

        override private[kyo] def matchesKeyBytes: Boolean =
            _syntheticReader.isEmpty && !_syntheticActive && nested && inner.matchesKeyBytes

        override private[kyo] def lastFieldNumber: Int =
            if _syntheticReader.isEmpty && !_syntheticActive && nested then inner.lastFieldNumber else -1

        override def matchField(nameBytes: Array[Byte]): Boolean =
            if _syntheticReader.isEmpty && !_syntheticActive && nested then
                val matched = inner.matchField(nameBytes)
                if matched then _matchedField = true
                matched
            else
                val matched =
                    if _syntheticReader.nonEmpty then _syntheticReader.get.matchField(nameBytes)
                    else if _passThrough && !_syntheticActive then inner.matchField(nameBytes)
                    else if _translatedField.isEmpty then false
                    else if _translatedByWrapper then sameName(_translatedField.get, nameBytes)
                    else
                        inner.matchField(nameBytes)
                if matched then _matchedField = true
                matched
        end matchField

        override def lastFieldName(): String =
            if _syntheticReader.nonEmpty then _syntheticReader.get.lastFieldName()
            else if !_syntheticActive && (nested || _passThrough) then inner.lastFieldName()
            else _translatedField.getOrElse("")

        // A key no field of the parent matched may be the flattened sum's, which reads the whole record, so it is not unknown here.
        private def strictUnknownField(): Boolean =
            denyUnknownFieldsEnabled && !_syntheticField && !_matchedField &&
                !flatReadMap.contains(_rawFieldName) && sumInput.isEmpty
        end strictUnknownField

        override def skip(): Unit =
            if _syntheticReader.isEmpty && !_syntheticActive && nested then
                if denyUnknownFieldsEnabled && !_matchedField then throw UnknownFieldException(Seq.empty, _rawFieldName)(using frame)
                else inner.skip()
            else if strictUnknownField() then
                throw UnknownFieldException(Seq.empty, _rawFieldName)(using frame)
            else if _syntheticReader.nonEmpty then
                _syntheticReader.get.skip()
            else if _syntheticActive then
                clearSynthetic()
            else if !_passThrough && !_syntheticField && !_matchedField && flatReadMap.contains(_rawFieldName) then
                val (parent, child) = flatReadMap(_rawFieldName)
                val captured        = inner.captureValue() match
                    case reader: Codec.IntrospectingReader => reader.readStructure()
                    case other                             => notIntrospecting(other)
                if _injection.flattenedValues == null then
                    _injection.flattenedValues = scala.collection.mutable.LinkedHashMap.empty[String, Chunk[(String, Structure.Value)]]
                val current = _injection.flattenedValues.getOrElse(parent, Chunk.empty)
                _injection.flattenedValues.update(parent, current :+ (child -> captured))
            else
                inner.skip()
        end skip

        private def clearSynthetic(): Unit =
            _syntheticActive = false
            _syntheticDepth = 0
            _syntheticReader = Maybe.empty
        end clearSynthetic

        private def finishSyntheticScalar(): Unit =
            if _syntheticDepth == 0 then clearSynthetic()
        end finishSyntheticScalar

        private def syntheticReader(): Reader =
            if _syntheticReader.isEmpty then
                _syntheticReader = Maybe(new StructureValueReader(_pendingSyntheticValue)(using frame))
            _syntheticReader.get
        end syntheticReader

        def objectStart(): Int =
            if _syntheticActive then
                val size = syntheticReader().objectStart()
                _syntheticDepth += 1
                size
            else
                _depth += 1
                inner.objectStart()

        // Set before the synthetic reader that reads it is created.
        private var _pendingSyntheticValue: Structure.Value = null

        def objectEnd(): Unit =
            if _syntheticActive then
                _syntheticReader.get.objectEnd()
                _syntheticDepth -= 1
                if _syntheticDepth == 0 then clearSynthetic()
            else
                _depth -= 1
                inner.objectEnd()

        override def arrayStart(): Int =
            if _syntheticActive then
                val size = syntheticReader().arrayStart()
                _syntheticDepth += 1
                size
            else
                _depth += 1
                inner.arrayStart()

        override def arrayEnd(): Unit =
            if _syntheticActive then
                _syntheticReader.get.arrayEnd()
                _syntheticDepth -= 1
                if _syntheticDepth == 0 then clearSynthetic()
            else
                _depth -= 1
                inner.arrayEnd()

        // field() reads the NEXT wire key of a map/keyed collection entry (Schema.scala's
        // Map[String, V] and tuple-as-object codecs call it in that role); it is unrelated to the
        // enclosing object's OWN field name, which lastFieldName() reports from _translatedField.
        // Delegating unconditionally (never consulting _translatedField) keeps a real, non-synthetic
        // Map field's keys wired to the underlying reader even when this object also carries a
        // field transform elsewhere.
        def field(): String =
            if _syntheticReader.nonEmpty then _syntheticReader.get.field()
            else inner.field()

        override def hasNextField(): Boolean =
            if _syntheticReader.nonEmpty then _syntheticReader.get.hasNextField()
            else if nested then inner.hasNextField()
            else if inner.hasNextField() then true
            else if _injection == null then false
            else
                val injection = _injection
                if !injection.flattenedPrepared then
                    // A flattened field with no flat key on the wire still decodes, from an empty record, since its child may
                    // have written no key at all (every field absent or omitted); a configured default for it takes precedence.
                    injection.pendingFlattened = (if flattened then tables.layout.parentSources else Chunk.empty).iterator
                        .filterNot(seenOnWire)
                        .flatMap { parent =>
                            sumInput match
                                case Maybe.Present((`parent`, whole)) => Some(parent -> whole)
                                case _                                =>
                                    flattenedOf(parent) match
                                        case Maybe.Present(fields) => Some(parent -> Structure.Value.Record(fields))
                                        case _                     =>
                                            if injection.pendingSynthetic.exists(_.name == parent) then None
                                            else
                                                // an optional record with none of its keys on the wire is absent
                                                val empty =
                                                    if optionalFlat.contains(parent) then Structure.Value.Null
                                                    else Structure.Value.Record(Chunk.empty)
                                                Some(parent -> empty)
                        }
                        .toList
                    injection.flattenedPrepared = true
                end if
                injection.pendingSynthetic = injection.pendingSynthetic.dropWhile { field =>
                    seenOnWire(field.name) || flattenedOf(field.name).nonEmpty
                }
                if injection.pendingFlattened.nonEmpty then
                    val (name, value) = injection.pendingFlattened.head
                    injection.pendingFlattened = injection.pendingFlattened.tail
                    _translatedField = Maybe(name)
                    _translatedByWrapper = true
                    _passThrough = false
                    _matchedField = false
                    _syntheticField = true
                    _rawFieldName = name
                    _pendingSyntheticValue = value
                    _syntheticActive = true
                    true
                else if injection.pendingSynthetic.nonEmpty then
                    val field = injection.pendingSynthetic.head
                    injection.pendingSynthetic = injection.pendingSynthetic.tail
                    _translatedField = Maybe(field.name)
                    _translatedByWrapper = true
                    _passThrough = false
                    _matchedField = false
                    _syntheticField = true
                    _rawFieldName = field.name
                    _pendingSyntheticValue = field.value(frame)
                    _syntheticActive = true
                    true
                else false
                end if
        end hasNextField

        override def hasNextElement(): Boolean =
            if _syntheticReader.nonEmpty then _syntheticReader.get.hasNextElement()
            else inner.hasNextElement()

        def string(): String =
            if _syntheticActive then
                val value = syntheticReader().string()
                finishSyntheticScalar()
                value
            else inner.string()

        def int(): Int =
            if _syntheticActive then
                val value = syntheticReader().int()
                finishSyntheticScalar()
                value
            else inner.int()

        def long(): Long =
            if _syntheticActive then
                val value = syntheticReader().long()
                finishSyntheticScalar()
                value
            else inner.long()

        def float(): Float =
            if _syntheticActive then
                val value = syntheticReader().float()
                finishSyntheticScalar()
                value
            else inner.float()

        def double(): Double =
            if _syntheticActive then
                val value = syntheticReader().double()
                finishSyntheticScalar()
                value
            else inner.double()

        def boolean(): Boolean =
            if _syntheticActive then
                val value = syntheticReader().boolean()
                finishSyntheticScalar()
                value
            else inner.boolean()

        def short(): Short =
            if _syntheticActive then
                val value = syntheticReader().short()
                finishSyntheticScalar()
                value
            else inner.short()

        def byte(): Byte =
            if _syntheticActive then
                val value = syntheticReader().byte()
                finishSyntheticScalar()
                value
            else inner.byte()

        def char(): Char =
            if _syntheticActive then
                val value = syntheticReader().char()
                finishSyntheticScalar()
                value
            else inner.char()

        def isNil(): Boolean =
            if _syntheticActive then
                val value = syntheticReader().isNil()
                if value then finishSyntheticScalar()
                value
            else inner.isNil()

        override def mapStart(): Int =
            if _syntheticActive then
                val size = syntheticReader().mapStart()
                _syntheticDepth += 1
                size
            else
                _depth += 1
                inner.mapStart()

        override def mapEnd(): Unit =
            if _syntheticActive then
                _syntheticReader.get.mapEnd()
                _syntheticDepth -= 1
                if _syntheticDepth == 0 then clearSynthetic()
            else
                _depth -= 1
                inner.mapEnd()

        override def hasNextEntry(): Boolean =
            if _syntheticReader.nonEmpty then _syntheticReader.get.hasNextEntry()
            else inner.hasNextEntry()

        def bytes(): Span[Byte] =
            if _syntheticActive then
                val value = syntheticReader().bytes()
                finishSyntheticScalar()
                value
            else inner.bytes()

        def bigInt(): BigInt =
            if _syntheticActive then
                val value = syntheticReader().bigInt()
                finishSyntheticScalar()
                value
            else inner.bigInt()

        def bigDecimal(): BigDecimal =
            if _syntheticActive then
                val value = syntheticReader().bigDecimal()
                finishSyntheticScalar()
                value
            else inner.bigDecimal()

        def instant(): java.time.Instant =
            if _syntheticActive then
                val value = syntheticReader().instant()
                finishSyntheticScalar()
                value
            else inner.instant()

        def duration(): java.time.Duration =
            if _syntheticActive then
                val value = syntheticReader().duration()
                finishSyntheticScalar()
                value
            else inner.duration()

        override def captureValue(): Reader =
            if _syntheticActive && _syntheticDepth > 0 then _syntheticReader.get.captureValue()
            else if _syntheticActive then
                val reader = new StructureValueReader(_pendingSyntheticValue)(using frame)
                clearSynthetic()
                reader
            else inner.captureValue()
        override def release(): Unit = inner.release()
    end TransformAwareReader

end SchemaSerializer
