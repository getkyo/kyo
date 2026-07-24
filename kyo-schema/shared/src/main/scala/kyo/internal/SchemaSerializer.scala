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

    final private case class SyntheticField(name: String, value: () => Structure.Value)

    /** Decode-time lookup key that keeps the field-name namespace and the numeric field-id namespace
      * disjoint. A self-describing codec reports a field by name; a binary codec (Protobuf) reports it
      * by its numeric field id. Registering an entry under BOTH a `ByName` and a `ByFieldId` key lets a
      * lookup by either wire form succeed, and the two opaque types make a name string and an id integer
      * un-aliasable inside one map: `ByName` is a `String` key, `ByFieldId` an `Int` key, so they occupy
      * disjoint slots even when their textual forms coincide. `ByFieldId` is opaque over `Int` (field ids
      * fit in `Int`); it boxes only on the id side and only at lookup, a short-lived non-escaping box.
      */
    private object WireKey:
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
      * name. Accepting unicode digits here would misclassify such a name as an id. This is the same
      * numeric-tag classification `matchField` does via `parsed.toInt`, without the throw on a non-id.
      */
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

        val structWriter = StructureValueWriter()
        schema.rawSerializeWrite(value, structWriter)
        val original = structWriter.getResult

        // Rename resolution, hoisted so both the field-transform application below and the
        // wire-shape hint (topShape) share one computation. forwardMap/resolveTarget/resolvedRenames
        // are schema-level (independent of the captured value), so computing them once here is safe
        // regardless of whether schema.structure turns out to be a Product or something else.
        val forwardMap = schema.renamedFields.toMap
        def resolveTarget(name: String): String =
            forwardMap.get(name) match
                case Some(next) => resolveTarget(next)
                case None       => name
        val resolvedRenames: Map[String, String] =
            if schema.renamedFields.isEmpty then Map.empty
            else
                schema.sourceFields.flatMap { sf =>
                    if forwardMap.contains(sf.name) then Some(sf.name -> resolveTarget(sf.name))
                    else None
                }.toMap
        val renamedSourceNames = resolvedRenames.keySet

        // Field names carrying a write-direction transform: their materialized value is produced by
        // the user-supplied transform, not by the field's own declared type, so the shape hint below
        // must not apply the declared type to them (a transform is free to change the wire shape,
        // e.g. a Map turned into a list of pairs).
        val transformOverrideNames: Set[String] =
            schema.fieldTransforms.collect { case (name, t) if t.write.isDefined => name }.toSet

        // Shape hint for writeStructureValue: this schema's own product shape with each source
        // field's wire (post-rename) name substituted for its declared name, so a renamed field's
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
                        .map(f => f.copy(name = resolvedRenames.getOrElse(f.name, f.name)))
                ))
            case _ =>
                Maybe.empty

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
                            val fieldWriter        = StructureValueWriter()
                            transform.write.get(rawFieldValue, fieldWriter)
                            name -> fieldWriter.getResult
                    }.toMap
                if overrideMap.isEmpty then originalFields
                else originalFields.map { (name, v) => name -> overrideMap.getOrElse(name, v) }
            case Structure.Value.Record(originalFields) =>
                originalFields
            case _ =>
                Chunk.empty

        val transformed = original match
            case Structure.Value.Record(_) =>
                val originalFields = baseFields
                schema.structure match
                    case _: Structure.Type.Product =>
                        // Field-level transforms (drop/rename/computed/convention) apply only to a product's
                        // own fields. For a sum schema the materialized Record is a single-field wrapper whose
                        // key is the variant name, not a data field; that key is governed by the variant-naming
                        // layer and must not be run through applyFieldConvention here.

                        // Transform original fields: drop, skip renamed sources, and omit empty/absent
                        // configured fields (keyed off the SOURCE name, before applyFieldConvention).
                        val transformedFields = originalFields.flatMap { (name, reflValue) =>
                            if schema.droppedFields.contains(name) then
                                Chunk.empty
                            else if renamedSourceNames.contains(name) then
                                Chunk.empty // added below with new name
                            else if omitField(schema, name, reflValue) then
                                Chunk.empty // omitted: empty/absent under an effective omit policy
                            else
                                Chunk((name, reflValue))
                        }

                        // Add renamed fields with their values, applying the same omit gate
                        val renamedFieldValues = Chunk.from(resolvedRenames.flatMap { (sourceName, targetName) =>
                            originalFields.find(_._1 == sourceName)
                                .filterNot((_, v) => omitField(schema, sourceName, v))
                                .map((_, v) => (targetName, v))
                        })

                        // Add computed fields
                        val computedFieldValues = schema.computedFields.map { (name, compute) =>
                            (name, anyToStructureValue(compute(value)))
                        }

                        val allFields = transformedFields ++ renamedFieldValues ++ computedFieldValues
                        Structure.Value.Record(applyFieldConvention(schema, resolvedRenames.values.toSet, allFields))
                    case _ =>
                        Structure.Value.Record(originalFields)
                end match
            case other =>
                other

        // Rewrite the materialized value tree into the configured wire shape. External passes
        // through (byte-identical wrapper object); the other cases rewrite at the value-tree level,
        // where a non-object payload is still intact (the flatten's non-record drop is what NOT
        // injecting into a Record avoids).
        val selected = selectRepresentation(schema, writer)
        val output = selected match
            case Schema.UnionRepresentation.External =>
                transformed
            case Schema.UnionRepresentation.Internal(tagKey) =>
                flattenWithDiscriminator(transformed, tagKey, resolveVariantWire(schema))
            case Schema.UnionRepresentation.Adjacent(tagKey, contentKey) =>
                adjacentEncode(transformed, tagKey, contentKey, resolveVariantWire(schema))
            case Schema.UnionRepresentation.Tuple =>
                requireTopLevelCapable(writer, "Tuple")
                tupleEncode(transformed, resolveVariantWire(schema))
            case Schema.UnionRepresentation.TupleFlat =>
                requireTopLevelCapable(writer, "TupleFlat")
                tupleFlatEncode(transformed, resolveVariantWire(schema))
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

        writeStructureValue(writer, output, outputShape)
    end writeWithTransforms

    /** True iff `sourceName`'s value should be omitted on encode under the schema's effective omit
      * policy for that field. A per-field `omitPolicies` entry shadows the schema-wide flags. A
      * `WhenNone` policy omits a `Structure.Value.Null`; a `WhenEmpty` policy omits an empty
      * `Sequence` / `MapEntries`. Schema-wide `omitNoneAll` / `omitEmptyCollectionsAll` apply to a
      * field with no per-field entry.
      */
    private def omitField[A](schema: Schema[A], sourceName: String, value: Structure.Value): Boolean =
        val perField = schema.omitPolicies.collectFirst { case (n, p) if n == sourceName => p }
        perField match
            case Some(Schema.OmitPolicy.WhenNone)        => isNullValue(value)
            case Some(Schema.OmitPolicy.WhenEmpty)       => isEmptyOmittableCollection(schema, sourceName, value)
            case Some(Schema.OmitPolicy.When(predicate)) => predicate(value)
            case Some(Schema.OmitPolicy.WhenDefault) =>
                schema.fieldMaterializedDefaults.collectFirst { case (n, default) if n == sourceName => default } match
                    case Some(default) => default == value
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
                    case None =>
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
        given Frame = reader.frame
        val captured = reader.captureValue() match
            case ir: Codec.IntrospectingReader => ir.readStructure()
            case _ =>
                throw SchemaNotSerializableException(
                    "representation-chain decode requires a self-describing reader (such as: Json, Yaml, Ion, MsgPack)"
                )
        @tailrec def attempt(idx: Int): A =
            val rep    = chain(idx)
            val fresh  = new StructureValueReader(captured)
            val result = Result.catching[SchemaException](readForRepresentation(schema, fresh, rep))
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
            case Schema.UnionRepresentation.Internal(tagKey) => readWithDiscriminatorField(schema, reader, tagKey)
            case Schema.UnionRepresentation.Adjacent(tk, ck) => readAdjacent(schema, reader, tk, ck)
            case Schema.UnionRepresentation.Tuple            => readTuple(schema, reader)
            case Schema.UnionRepresentation.TupleFlat        => readTupleFlat(schema, reader)
            case Schema.UnionRepresentation.Untagged         => readUntagged(schema, reader)
    end readForRepresentation

    /** Internal-format decode using an explicit tag key (as opposed to `readWithDiscriminator` which
      * reads the tag key from `schema.discriminatorField`). Used by `readForRepresentation` so that a
      * chain entry of `Internal(tagKey)` works even when the schema was configured via `representations`
      * without calling `.discriminator(tagKey)`.
      */
    private def readWithDiscriminatorField[A](schema: Schema[A], reader: Reader, tagKey: String): A =
        given Frame    = reader.frame
        val discReader = new DiscriminatorReader(reader, tagKey, reader.frame, variantReverse(schema))
        if schema.renamedFields.nonEmpty || schema.droppedFields.nonEmpty then
            readWithTransforms(schema, discReader)
        else
            schema.rawSerializeRead(discReader)
        end if
    end readWithDiscriminatorField

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
    private def requireTopLevelCapable(writer: Writer, representation: String)(using Frame): Unit =
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
    private def resolveVariantWire[A](schema: Schema[A])(using Frame): String => String =
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
        val flattenedReadMap                = flattenedReadFields(schema)

        // renamedSources: original field names that have been renamed away (no longer valid in JSON)
        val renamedSources: Set[String] =
            if schema.renamedFields.isEmpty then Set.empty
            else schema.sourceFields.filter(sf => forwardMap.contains(sf.name)).map(_.name).toSet

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

        def materializeDefault(fieldDefault: Schema.FieldDefault): Structure.Value =
            val writer = StructureValueWriter()
            fieldDefault.writeDefault(fieldDefault.supplier(), writer)
            writer.getResult
        end materializeDefault

        val defaultByName = schema.fieldDefaults.toMap
        val syntheticFields =
            schema.sourceFields.flatMap { field =>
                if schema.droppedFields.contains(field.name) then None
                else
                    defaultByName.get(field.name) match
                        case Some(fieldDefault) =>
                            Some(SyntheticField(field.name, () => materializeDefault(fieldDefault)))
                        case None if omitDefaultedNames.contains(field.name) =>
                            if isOrderedDictOrDictTag(field) then
                                emptyMappingWireValue(schema, field.name)
                                    .map(v => SyntheticField(field.name, () => v))
                            else
                                val zero = zeroForField(field)
                                if zero == null then None
                                else Some(SyntheticField(field.name, () => zeroToStructureValue(zero)))
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
                            WireKey.name(name)                   -> (name, t),
                            WireKey.id(CodecMacro.fieldId(name)) -> (name, t)
                        )
                }.flatten.toMap

        val transformReader =
            if !schema.denyUnknownFieldsEnabled && reverseMap.isEmpty && renamedSources.isEmpty &&
                droppedIndices.isEmpty && syntheticFields.isEmpty && flattenedReadMap.isEmpty &&
                fieldReadOverrides.isEmpty
            then
                reader
            else
                new TransformAwareReader(
                    reader,
                    reverseMap,
                    renamedSources,
                    droppedIndices,
                    syntheticFields,
                    schema.denyUnknownFieldsEnabled,
                    flattenedReadMap,
                    fieldReadOverrides
                )

        schema.rawSerializeRead(transformReader)
    end readWithTransforms

    private def flattenedReadFields[A](schema: Schema[A]): Map[String, (String, String)] =
        if schema.flattenedReadFields.isEmpty then Map.empty
        else
            val direct = schema.flattenedReadFields.map((child, parent) => child -> (parent, child)).toMap
            schema.variantNaming.fieldCase match
                case Maybe.Present(nc) =>
                    val fn = NameCaseConversion.convert(nc)
                    direct ++ schema.flattenedReadFields.map((child, parent) => fn(child) -> (parent, child)).toMap
                case _ =>
                    direct
            end match
        end if
    end flattenedReadFields

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

    /** Writes a Structure.Value tree to a Writer. Reverse of StructureValueWriter.
      *
      * `shape` is an optional type hint carried down from the originating Schema's structure. It
      * exists to break a genuine ambiguity in `Structure.Value`: `StructureValueWriter` materializes
      * both a product and a string-keyed map as `Record` (`mapStart`/`mapEnd` build the same
      * `ObjectFrame` as `objectStart`/`objectEnd`), so replaying a `Record` with no further
      * information cannot tell the two apart. That is harmless for a self-describing codec, where an
      * object and a map write identically, but Protobuf's wire encoding of the two is NOT
      * interchangeable: an object is one nested sub-message under the field's own number, while a map
      * is a REPEATED MapEntry sub-message per entry (key at field 1, value at field 2) under that
      * same number. Replaying a map with object framing corrupts the wire (each entry key becomes a
      * bogus hash-derived field number instead of a MapEntry key). When `shape` resolves to
      * `Mapping`, the `Record` writes as a map; otherwise (no hint, or a genuine `Product`) it writes
      * as an object, matching the shape-free behavior every caller other than `writeWithTransforms`
      * still relies on.
      */
    def writeStructureValue(writer: Writer, value: Structure.Value, shape: Maybe[Structure.Type] = Maybe.empty): Unit =
        value match
            case Structure.Value.Record(fields) =>
                val resolvedShape = shape.map(unwrapOptionalShape)
                resolvedShape match
                    case Maybe.Present(Structure.Type.Mapping(_, _, _, valueType)) =>
                        val valueShape = Maybe(unwrapOptionalShape(valueType))
                        writer.mapStart(fields.size)
                        fields.foreach { (name, v) =>
                            writer.fieldBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0)
                            writeStructureValue(writer, v, valueShape)
                        }
                        writer.mapEnd()
                    case _ =>
                        val fieldShapes: Map[String, Structure.Type] = resolvedShape match
                            case Maybe.Present(p: Structure.Type.Product) =>
                                p.fields.iterator.map(f => f.name -> unwrapOptionalShape(f.fieldType)).toMap
                            case _ =>
                                Map.empty
                        writer.objectStart("", fields.size)
                        fields.foreach { (name, v) =>
                            writer.fieldBytes(
                                name.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                CodecMacro.fieldId(name)
                            )
                            writeStructureValue(writer, v, Maybe.fromOption(fieldShapes.get(name)))
                        }
                        writer.objectEnd()
                end match
            case Structure.Value.Sequence(elements) =>
                val elemShape = shape.map(unwrapOptionalShape) match
                    case Maybe.Present(Structure.Type.Collection(_, _, elem)) => Maybe(unwrapOptionalShape(elem))
                    case _                                                    => Maybe.empty
                writer.arrayStart(elements.size)
                elements.foreach(e => writeStructureValue(writer, e, elemShape))
                writer.arrayEnd()
            case Structure.Value.MapEntries(entries) =>
                // Shape-aware MapEntries:
                //   * all-String keys -> JSON object with each key as a field; round-trips through the universal Record shape.
                //   * mixed/non-String keys -> array-of-pairs; non-String keys are inexpressible as JSON field names.
                val (keyShape, valueShape) = shape.map(unwrapOptionalShape) match
                    case Maybe.Present(Structure.Type.Mapping(_, _, k, v)) =>
                        (Maybe(unwrapOptionalShape(k)), Maybe(unwrapOptionalShape(v)))
                    case _ =>
                        (Maybe.empty, Maybe.empty)
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
                        writeStructureValue(writer, v, valueShape)
                    }
                    writer.mapEnd()
                else
                    writer.arrayStart(entries.size)
                    entries.foreach { (k, v) =>
                        writer.arrayStart(2)
                        writeStructureValue(writer, k, keyShape)
                        writeStructureValue(writer, v, valueShape)
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
            case Structure.Value.Decimal(d)  => writer.double(d)
            case Structure.Value.Bool(b)     => writer.boolean(b)
            case Structure.Value.BigNum(bd)  => writer.bigDecimal(bd)
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

    /** True iff `field`'s declared type is `OrderedDict[K, V]` or `Dict[K, V]`. Both are opaque
      * types over an erased union (`Span[K | V] | TreeSeqMap[K, V]` / `Span[K | V] | HashMap[K, V]`),
      * so their `Tag.show` is the SAME opaque-bound string for every key/value instantiation (unlike
      * `Map`, whose show carries the element types): neither a `<:<` check nor a `scala.collection.*`
      * show-prefix check can discriminate them. A show-prefix check against the opaque type's own
      * qualified name is the only reliable discriminator, the same idiom `isMapTag` uses for its own
      * variance gap.
      */
    private def isOrderedDictOrDictTag(field: Field[?, ?]): Boolean =
        val show = field.tag.show
        show.startsWith("(kyo.OrderedDict$package$.OrderedDict ") ||
        show.startsWith("(kyo.Dict$package$.Dict ")
    end isOrderedDictOrDictTag

    /** True iff `field`'s declared type is a sequence-like collection, a set, or a map (including
      * the opaque `OrderedDict`/`Dict` map types): the exact set the encode-time omit gate and the
      * decode-time synthetic-injection gate both consult, so an empty product (which also
      * materializes as an empty `Record`) is never mistaken for an empty collection. `OrderedDict`
      * and `Dict` fields synthesize their decode-time zero value from the schema's declared
      * structure (see [[emptyMappingWireValue]]) rather than from [[zeroForField]], since their
      * opaque erasure gives `zeroForField` no runtime shape to introspect.
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

    /** Returns the empty wire-shape `Structure.Value` for `fieldName`'s declared field type, when
      * that field is an `OrderedDict`/`Dict` (a `Structure.Type.Mapping`). Used in place of
      * [[zeroForField]] + [[zeroToStructureValue]] for these two types: both are opaque types whose
      * empty value erases to a bare `Span`-backed array with no reliable runtime shape to
      * pattern-match (unlike `Map`, a real generic class `zeroToStructureValue` matches directly), so
      * the empty value is derived from the field's DECLARED structure instead of from an instance.
      *
      * A String key selects the `Record` (object) form, any other key the `Sequence` (array) form.
      * This matches the default given resolution: the object-form given (`stringDictSchema`,
      * `stringOrderedDictSchema`) is the more specific one for a String key and wins by default, and the
      * array-form given (`dictSchema`, `orderedDictSchema`) is the only one for every other key. It is
      * derived from the declared key structure because the declared structure is all that is reachable
      * here; the wire form is a property of the bound given, and the field's own writer is not exposed
      * on this path.
      *
      * Known boundary: a caller that explicitly binds the array-form given for a String key (rather
      * than the object-form default) declares a structure byte-identical to the object-form given's,
      * so this returns the object empty value while the bound reader expects the array form, and the
      * decode fails with a typed `TypeMismatchException`. It fails loud, never silently, and only under
      * that explicit non-default binding. Tracked in getkyo/kyo#1748.
      *
      * Returns `None` when `schema.structure` is not a `Product`, or carries no field named
      * `fieldName`; this should not happen for a schema whose `sourceFields` supplied `fieldName` in
      * the first place, but the empty result keeps the caller total rather than throwing.
      */
    private def emptyMappingWireValue[A](schema: Schema[A], fieldName: String): Option[Structure.Value] =
        schema.structure match
            case p: Structure.Type.Product =>
                p.fields.find(_.name == fieldName).map { f =>
                    unwrapOptionalShape(f.fieldType) match
                        case Structure.Type.Mapping(_, _, keyType, _) =>
                            unwrapOptionalShape(keyType) match
                                case Structure.Type.Primitive(Structure.PrimitiveKind.String, _) =>
                                    Structure.Value.Record(Chunk.empty)
                                case _ =>
                                    Structure.Value.Sequence(Chunk.empty)
                        case _ =>
                            Structure.Value.Sequence(Chunk.empty)
                }
            case _ =>
                None
    end emptyMappingWireValue

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
        given Frame    = reader.frame
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
        given Frame   = reader.frame
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
        given Frame   = reader.frame
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
        given Frame         = reader.frame
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
        // Type-union schemas (derived by the union arm of the macro) use multi-probe
        // to detect ambiguity. Nominal sealed sums use first-declared-wins below.
        // The discriminant is the Structure.Type.Sum name: union derivation always
        // produces "Union", while nominal sums always use the sealed type's own name.
        schema.structure match
            case Structure.Type.Sum(name, _, _, _, _, _) if name == "Union" =>
                readUnionMultiProbe(schema, reader)
            case _ =>
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
        val tree = reader.captureValue() match
            case ir: Codec.IntrospectingReader => ir.readStructure()
            case _ =>
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
                val fresh  = new StructureValueReader(tree)
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

    /** The variant wire names in declaration order, for the NoVariantMatchException list. Reuses the
      * forward resolver so the names match what encode would emit for each variant.
      */
    private def untaggedVariantWireNames[A](schema: Schema[A])(using Frame): Chunk[String] =
        val resolveWire = resolveVariantWire(schema)
        Schema.variantScalaNames(schema.structure).map(resolveWire)
    end untaggedVariantWireNames

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

    /** Builds the variant reverse-map (wire primary or alias -> Scala variant name). An
      * unresolved wire string maps to itself, so it matches no variant and reaches
      * `UnknownVariantException`.
      */
    private def variantReverse[A](schema: Schema[A])(using Frame): String => String =
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

        // A wrapper consumes nothing of its own; whether the input is exhausted is the reader it wraps.
        private[kyo] def requireEndOfInput(): Unit = inner.requireEndOfInput()

        protected var delegateReader: Maybe[Reader]   = Maybe.empty
        protected var delegateDepth: Int              = 0
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

        override def absentDefaultedFieldsMask(n: Int, defaultableFieldsMask: Long): Long =
            delegateReader match
                case Present(reader) => reader.absentDefaultedFieldsMask(n, defaultableFieldsMask)
                case _               => inner.absentDefaultedFieldsMask(n, defaultableFieldsMask)
            end match
        end absentDefaultedFieldsMask

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

        // PhaseInitial: not yet started
        // PhaseOuterStarted: outer object started, about to return variant name as field
        // PhaseVariantReturned: variant field returned, inner object about to start
        // PhaseInnerStarted: inner object started, iterating buffered fields
        // PhaseDone: done
        private var variantName: Maybe[String]                     = Maybe.empty
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
                        resolveVariant(variantName.get)
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
                    case PhaseOuterStarted => true
                    case PhaseInnerStarted => fieldIdx < bufferedFields.get.length
                    case _                 => false
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

        // PhaseInitial: not started; PhaseOuterStarted: outer wrapper open, variant field pending;
        // PhaseVariantReturned: variant field returned, content delegate about to drive. This
        // two-level reader has no deeper state, so PhaseInnerStarted is reused as its terminal state.
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
    final class TupleFlatReader(
        inner: Reader,
        _frame: Frame,
        resolveVariant: String => String,
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
    final private class TransformAwareReader(
        inner: Reader,
        reverseMap: Map[String, String],
        renamedSources: Set[String],
        droppedIndices: Map[Int, Field[?, ?]],
        syntheticFields: List[SyntheticField] = Nil,
        denyUnknownFieldsEnabled: Boolean = false,
        flattenedReadFields: Map[String, (String, String)] = Map.empty,
        fieldReadOverrides: Map[WireKey.Key, (String, Schema.FieldTransform[?])] = Map.empty
    ) extends Reader:

        def frame: Frame = inner.frame

        // A wrapper consumes nothing of its own; whether the input is exhausted is the reader it wraps.
        private[kyo] def requireEndOfInput(): Unit = inner.requireEndOfInput()

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

        override def absentDefaultedFieldsMask(n: Int, defaultableFieldsMask: Long): Long =
            inner.absentDefaultedFieldsMask(n, defaultableFieldsMask)

        override def initFields(n: Int): Array[AnyRef] = inner.initFields(n)

        override def clearFields(n: Int): Unit = inner.clearFields(n)

        private var _translatedField: Maybe[String] = Maybe.empty
        private var _translatedByWrapper: Boolean   = false
        private var _matchedField: Boolean          = false
        private var _syntheticField: Boolean        = false
        private var _rawFieldName: String           = ""

        // Synthetic injection state for flattened parent replay and configured missing-field values.
        // _seenFromWire: WireKey of every field read from the real wire; used to skip injection for
        //   fields that already had a value on the wire (prevents overwriting non-empty data). Recorded
        //   under both the name and the id key when the wire token is numeric, so the synthetic and
        //   flattened-parent checks (which know the source name) match it on either codec.
        // List, not Chunk: these are drained head/tail as the reader yields each pending field,
        // and List gives O(1) head/tail on this hot decode path.
        private var _pendingSyntheticFields: List[SyntheticField]      = syntheticFields
        private var _pendingFlattened: List[(String, Structure.Value)] = Nil
        private var _flattenedPrepared: Boolean                        = false
        private var _syntheticActive: Boolean                          = false
        private var _syntheticDepth: Int                               = 0
        private var _syntheticReader: Maybe[Reader]                    = Maybe.empty
        private val _flattenedValues: scala.collection.mutable.LinkedHashMap[String, Chunk[(String, Structure.Value)]] =
            scala.collection.mutable.LinkedHashMap.empty[String, Chunk[(String, Structure.Value)]]
        private val _seenFromWire: scala.collection.mutable.HashSet[WireKey.Key] =
            scala.collection.mutable.HashSet.empty[WireKey.Key]

        // A source field counts as present on the wire when either its name key or its numeric-id key
        // was recorded, so suppression works whether the codec reported the field by name or by id.
        private def seenOnWire(sourceName: String): Boolean =
            _seenFromWire.contains(WireKey.name(sourceName)) ||
                _seenFromWire.contains(WireKey.id(CodecMacro.fieldId(sourceName)))

        // Read the field name from the inner reader and translate it.
        // Records the translated name in _seenFromWire so hasNextField skips injection for it.
        // When in synthetic mode, the name was already set by hasNextField; just return.
        override def fieldParse(): Unit =
            if _syntheticReader.nonEmpty then
                _syntheticReader.get.fieldParse()
            else if _syntheticActive then
                _matchedField = false
            else
                inner.fieldParse()
                val displayName = inner.lastFieldName()
                val rawName     = displayName
                _rawFieldName = if displayName.isEmpty then rawName else displayName
                _matchedField = false
                _syntheticField = false
                val renamedAway = renamedSources.contains(rawName)
                val mapped      = reverseMap.get(rawName)
                val translated = mapped.getOrElse(
                    if renamedAway then "\u0000_invalid_renamed_field"
                    else rawName
                )
                _translatedByWrapper = mapped.isDefined || renamedAway
                _translatedField = Maybe(translated)
                // Record the wire field under its name key, and additionally under its id key when the
                // token is numeric (the Protobuf path), so a present field is not later overwritten by
                // its configured default and a flattened parent is recognized on either codec.
                val translatedId = wireFieldId(translated)
                _seenFromWire += WireKey.name(translated)
                if translatedId >= 0 then _seenFromWire += WireKey.id(translatedId)
                val readOverride =
                    fieldReadOverrides.get(WireKey.name(translated)) match
                        case hit @ Some(_) => hit
                        case None          => if translatedId >= 0 then fieldReadOverrides.get(WireKey.id(translatedId)) else None
                readOverride match
                    case Some((sourceName, transform)) if transform.read.isDefined =>
                        val rawResult = transform.read.get(inner)
                        val svWriter  = StructureValueWriter()
                        transform.writeDerived(rawResult, svWriter)
                        _pendingSyntheticValue = svWriter.getResult
                        // Rewrite _translatedField to the source name so matchField's
                        // string comparison succeeds even when `translated` was a numeric
                        // field-ID string (Protobuf wire path).
                        _translatedField = Maybe(sourceName)
                        _syntheticActive = true
                        _translatedByWrapper = true
                        _matchedField = true
                        _syntheticField = true
                    case _ => ()
                end match
        end fieldParse

        override def matchField(nameBytes: Array[Byte]): Boolean =
            val matched =
                if _syntheticReader.nonEmpty then _syntheticReader.get.matchField(nameBytes)
                else if _translatedField.isEmpty then false
                else if _translatedByWrapper then
                    val expected = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)
                    _translatedField.get == expected
                else
                    inner.matchField(nameBytes)
            if matched then _matchedField = true
            matched
        end matchField

        override def lastFieldName(): String =
            if _syntheticReader.nonEmpty then _syntheticReader.get.lastFieldName()
            else _translatedField.getOrElse("")

        private def strictUnknownField(): Boolean =
            denyUnknownFieldsEnabled && !_syntheticField && !_matchedField &&
                !flattenedReadFields.contains(_rawFieldName)
        end strictUnknownField

        override def skip(): Unit =
            if strictUnknownField() then
                throw UnknownFieldException(Seq.empty, _rawFieldName)(using frame)
            else if _syntheticReader.nonEmpty then
                _syntheticReader.get.skip()
            else if _syntheticActive then
                clearSynthetic()
            else if !_syntheticField && !_matchedField && flattenedReadFields.contains(_rawFieldName) then
                val (parent, child) = flattenedReadFields(_rawFieldName)
                val captured = inner.captureValue() match
                    case reader: Codec.IntrospectingReader => reader.readStructure()
                    case other =>
                        throw TypeMismatchException(Seq.empty, "introspecting reader", other.getClass.getName)(using frame)
                val current = _flattenedValues.getOrElse(parent, Chunk.empty)
                _flattenedValues.update(parent, current :+ (child -> captured))
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
            else inner.objectStart()

        private var _pendingSyntheticValue: Structure.Value = Structure.Value.Record(Chunk.empty)

        def objectEnd(): Unit =
            if _syntheticActive then
                _syntheticReader.get.objectEnd()
                _syntheticDepth -= 1
                if _syntheticDepth == 0 then clearSynthetic()
            else inner.objectEnd()

        override def arrayStart(): Int =
            if _syntheticActive then
                val size = syntheticReader().arrayStart()
                _syntheticDepth += 1
                size
            else inner.arrayStart()

        override def arrayEnd(): Unit =
            if _syntheticActive then
                _syntheticReader.get.arrayEnd()
                _syntheticDepth -= 1
                if _syntheticDepth == 0 then clearSynthetic()
            else inner.arrayEnd()

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
            else if inner.hasNextField() then true
            else
                if !_flattenedPrepared then
                    _pendingFlattened = _flattenedValues.iterator
                        .filterNot((parent, _) => seenOnWire(parent))
                        .map((parent, fields) => parent -> Structure.Value.Record(fields))
                        .toList
                    _flattenedPrepared = true
                end if
                _pendingSyntheticFields = _pendingSyntheticFields.dropWhile { field =>
                    seenOnWire(field.name) || _flattenedValues.contains(field.name)
                }
                if _pendingFlattened.nonEmpty then
                    val (name, value) = _pendingFlattened.head
                    _pendingFlattened = _pendingFlattened.tail
                    _translatedField = Maybe(name)
                    _translatedByWrapper = true
                    _matchedField = false
                    _syntheticField = true
                    _rawFieldName = name
                    _pendingSyntheticValue = value
                    _syntheticActive = true
                    true
                else if _pendingSyntheticFields.nonEmpty then
                    val field = _pendingSyntheticFields.head
                    _pendingSyntheticFields = _pendingSyntheticFields.tail
                    _translatedField = Maybe(field.name)
                    _translatedByWrapper = true
                    _matchedField = false
                    _syntheticField = true
                    _rawFieldName = field.name
                    _pendingSyntheticValue = field.value()
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
            else inner.mapStart()

        override def mapEnd(): Unit =
            if _syntheticActive then
                _syntheticReader.get.mapEnd()
                _syntheticDepth -= 1
                if _syntheticDepth == 0 then clearSynthetic()
            else inner.mapEnd()

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
            if _syntheticActive then
                val reader = new StructureValueReader(_pendingSyntheticValue)(using frame)
                clearSynthetic()
                reader
            else inner.captureValue()
        override def release(): Unit = inner.release()
    end TransformAwareReader

end SchemaSerializer
