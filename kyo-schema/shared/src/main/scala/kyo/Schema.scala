package kyo

import kyo.Codec.Reader
import kyo.Codec.Writer
import kyo.internal.JsonWriter
import kyo.internal.StructureValueReader
import kyo.internal.StructureValueWriter
import scala.annotation.nowarn
import scala.annotation.publicInBinary
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.compiletime.*
import scala.deriving.Mirror
import scala.language.dynamics

/** Composable metadata and serialization descriptor for a structured Scala type.
  *
  * Schema[A] provides validation, serialization, structural introspection, and type-safe transforms for type A. A Schema is obtained via
  * `Schema.derived[A]` (typeclass derivation) or via `Schema[A]` (which also resolves the `Focused` type member) and can then be enriched
  * with checks, documentation, constraints, examples, and field transforms.
  *
  * The `Focused` type member tracks the current structural representation of A through successive transform calls. It starts as the full
  * field intersection of the original case class and narrows or changes as `drop`, `rename`, and `add` are applied. Although transform
  * methods return `Any` due to `transparent inline`, at runtime the result is always `Schema[A] { type Focused = F' }` where `F'` reflects
  * the new shape.
  *
  * Serialization (JSON / Protobuf) is not on Schema itself. Use [[Json]] and [[Protobuf]] as entry points; they require a `Schema[A]` given
  * to be in scope.
  *
  * {{{
  * val schema = Schema[User]
  *   .check(_.name)(_.nonEmpty, "name required")
  *   .doc(_.email)("user email address")
  *
  * val json   = Json.encode(user)
  * val errors = schema.validate(user)
  * }}}
  *
  * @tparam A
  *   the source type this schema describes
  *
  * @see
  *   [[Focus]] for field-level navigation and validation
  * @see
  *   [[Compare]] for read-only field-by-field comparison of two values
  * @see
  *   [[Modify]] for accumulating and applying field mutations
  * @see
  *   [[Changeset]] for serializable, transmittable diffs
  * @see
  *   [[Convert]] for bidirectional type conversion
  * @see
  *   [[Structure]] for runtime structural introspection
  */
abstract class Schema[A] @publicInBinary private[kyo] (
    private[kyo] val segments: Seq[String],
    val examples: Chunk[A] = Chunk.empty,
    val fieldDocs: Map[Seq[String], String] = Map.empty,
    val fieldDeprecated: Map[Seq[String], String] = Map.empty,
    val constraints: Seq[Schema.Constraint] = Seq.empty,
    val droppedFields: Set[String] = Set.empty,
    val renamedFields: Chunk[(String, String)] = Chunk.empty,
    val computedFields: Chunk[(String, A => Any)] = Chunk.empty,
    val sourceFields: Seq[Field[?, ?]] = Seq.empty,
    private[kyo] val checks: Seq[A => Seq[ValidationFailedException]] = Seq.empty,
    private[kyo] val documentation: Maybe[String] = Maybe.empty,
    private[kyo] val fieldIdOverrides: Map[Seq[String], Int] = Map.empty,
    private[kyo] val discriminatorField: Maybe[String] = Maybe.empty,
    @publicInBinary private[kyo] val variantNaming: Schema.VariantNaming = Schema.VariantNaming(),
    @publicInBinary private[kyo] val representation: Schema.UnionRepresentation = Schema.UnionRepresentation.External,
    @publicInBinary private[kyo] val representationChain: Maybe[Chunk[Schema.UnionRepresentation]] = Maybe.empty,
    @publicInBinary private[kyo] val omitPolicies: Chunk[(String, Schema.OmitPolicy)] = Chunk.empty,
    @publicInBinary private[kyo] val omitNoneAll: Boolean = false,
    @publicInBinary private[kyo] val omitEmptyCollectionsAll: Boolean = false,
    @publicInBinary private[kyo] val unionAmbiguityPolicy: Schema.UnionAmbiguity = Schema.UnionAmbiguity.Strict,
    @publicInBinary private[kyo] val variantDecoders: Chunk[Codec.Reader => Any] = Chunk.empty,
    @publicInBinary private[kyo] val denyUnknownFieldsEnabled: Boolean = false,
    @publicInBinary private[kyo] val fieldDefaults: Chunk[(String, Schema.FieldDefault)] = Chunk.empty,
    @publicInBinary private[kyo] val fieldTransforms: Chunk[(String, Schema.FieldTransform[A])] =
        Chunk.empty[(String, Schema.FieldTransform[A])],
    @publicInBinary private[kyo] val fieldMaterializedDefaults: Chunk[(String, Structure.Value)] = Chunk.empty
):

    /** The structural representation type. Set by factory/transforms. */
    type Focused

    @publicInBinary private[kyo] def flattenedReadFields: Chunk[(String, String)] = Chunk.empty

    // --- Abstract codec and focus methods. Each concrete Schema[A] overrides
    // these via `Schema.init` / `Schema.initFocused`, which inline the caller's
    // lambda expression directly into the method body: no Function closure is
    // materialized per derivation. See companion object `Schema.init`.

    /** Serialize a value of A to the given Writer. */
    @publicInBinary private[kyo] def serializeWrite(value: A, writer: Writer): Unit

    /** Deserialize a value of A from the given Reader. */
    @publicInBinary private[kyo] def serializeRead(reader: Reader): A

    /** Raw write/read WITHOUT applying this schema's own structural transforms (drop / rename / computed /
      * discriminator). `SchemaSerializer`'s transform path calls these to obtain the untransformed
      * structure of the underlying schema; for a schema with no transforms they equal `serializeWrite` /
      * `serializeRead`. `Schema.init` overrides them with the raw `writeFn` / `readFn`.
      */
    @publicInBinary private[kyo] def rawSerializeWrite(value: A, writer: Writer): Unit = serializeWrite(value, writer)
    @publicInBinary private[kyo] def rawSerializeRead(reader: Reader): A               = serializeRead(reader)

    /** Transform-aware write/read. Applies this schema's structural transforms via `SchemaSerializer`.
      * These are NON-inline so the `private[kyo]` `SchemaSerializer` reference is compiled here in
      * `package kyo`, never inlined into a user `derives Schema` site (where it would be inaccessible).
      * `Schema.init`'s inline `serializeWrite` / `serializeRead` call these only when transforms exist.
      */
    @publicInBinary private[kyo] def transformedWrite(value: A, writer: Writer): Unit =
        internal.SchemaSerializer.writeWithTransforms(this, value, writer)(using Frame.internal)
    @publicInBinary private[kyo] def transformedRead(reader: Reader): A =
        representationChain match
            case Maybe.Present(chain) =>
                internal.SchemaSerializer.readChain(this, reader, chain)
            case Maybe.Absent =>
                representation match
                    case Schema.UnionRepresentation.External =>
                        internal.SchemaSerializer.readWithTransforms(this, reader)
                    case Schema.UnionRepresentation.Internal(_) =>
                        internal.SchemaSerializer.readWithDiscriminator(this, reader)
                    case Schema.UnionRepresentation.Adjacent(tagKey, contentKey) =>
                        internal.SchemaSerializer.readAdjacent(this, reader, tagKey, contentKey)
                    case Schema.UnionRepresentation.Tuple =>
                        internal.SchemaSerializer.readTuple(this, reader)
                    case Schema.UnionRepresentation.TupleFlat =>
                        internal.SchemaSerializer.readTupleFlat(this, reader)
                    case Schema.UnionRepresentation.Untagged =>
                        internal.SchemaSerializer.readUntagged(this, reader)

    /** Get the focused value out of a root A. */
    @publicInBinary private[kyo] def getter(value: A): Maybe[Any]

    /** Set the focused value into a root A, returning the updated A. */
    @publicInBinary private[kyo] def setter(value: A, next: Any): A

    /** Precomputed flag: true iff this schema has any serialization-time transforms (dropped / renamed / computed fields or a
      * discriminator). Enables a single boolean branch on the write hot path instead of four `isEmpty` probes.
      */
    @publicInBinary private[kyo] val hasTransforms: Boolean =
        droppedFields.nonEmpty || renamedFields.nonEmpty ||
            computedFields.nonEmpty || discriminatorField.isDefined || variantNaming.nonEmpty ||
            representation.nonDefault || representationChain.isDefined ||
            omitPolicies.nonEmpty || omitNoneAll || omitEmptyCollectionsAll ||
            fieldTransforms.nonEmpty

    /** Read-side transform flag: discriminator / rename / drop (computed fields are write-only). Marked
      * `@publicInBinary` so `Schema.init`'s inline `serializeRead` can branch on it from a user
      * `derives Schema` site without an inaccessible `private[kyo]` reference.
      */
    @publicInBinary private[kyo] val hasReadTransforms: Boolean =
        droppedFields.nonEmpty || renamedFields.nonEmpty || discriminatorField.isDefined ||
            variantNaming.nonEmpty || representation.nonDefault || representationChain.isDefined ||
            omitPolicies.nonEmpty || omitNoneAll || omitEmptyCollectionsAll ||
            (unionAmbiguityPolicy != Schema.UnionAmbiguity.Strict) ||
            denyUnknownFieldsEnabled || fieldDefaults.nonEmpty || fieldTransforms.nonEmpty

    /** Pre-built root navigator for focus lambda resolution.
      *
      * Delegates to the abstract `getter` / `setter` methods rather than to deleted val fields.
      */
    private[kyo] val rootSelect: Focus.Select[A, Focused] =
        Focus.Select.create[A, Focused](
            (a: A) => getter(a).asInstanceOf[Maybe[Focused]],
            (a: A, v: Focused) => setter(a, v),
            segments,
            false,
            Maybe(this)
        )

    // --- Public API ---

    /** Encodes a value to bytes using the given codec.
      *
      * Instance-level entry point for serialization. Use it when you have a named or transformed schema and want to encode directly through
      * it, rather than passing it as an implicit to `Json.encode` or `Protobuf.encode`.
      *
      * {{{
      * val s = Schema[User].drop(_.password)
      * val bytes: Span[Byte] = s.encode[Json](user)
      * val bytes: Span[Byte] = s.encode[Protobuf](user)
      * }}}
      *
      * @param value
      *   the value to encode
      * @return
      *   the encoded bytes in the codec's wire format
      */
    def encode[C <: Codec](value: A)(using codec: C, frame: Frame): Span[Byte] =
        val w = codec.newWriter()
        writeTo(value, w)
        w.result()
    end encode

    /** Encodes a value to a String using the given codec.
      *
      * Convenience method that converts the encoded bytes to a UTF-8 string. Primarily useful with JSON where the output is human-readable
      * text.
      *
      * {{{
      * val s = Schema[User].rename(_.name, "userName")
      * val json: String = s.encodeString[Json](user)
      * // {"userName":"Alice","age":30}
      * }}}
      *
      * @param value
      *   the value to encode
      * @return
      *   the encoded value as a UTF-8 string
      */
    def encodeString[C <: Codec](value: A)(using codec: C, frame: Frame): String =
        val writer = codec.newWriter()
        internal.SchemaSerializer.writeTo(this, value, writer)
        writer.resultString
    end encodeString

    /** Decodes a value from bytes using the given codec.
      *
      * Instance-level entry point for deserialization. Use it when you have a named or transformed schema and want to decode directly
      * through it.
      *
      * {{{
      * val s = Schema[User].rename(_.name, "userName")
      * val result: Result[DecodeException, User] = s.decode[Json](bytes)
      * }}}
      *
      * @param input
      *   the encoded bytes to decode
      * @return
      *   the decoded value, or a DecodeException if the input is malformed or does not match the schema
      */
    def decode[C <: Codec](input: Span[Byte])(using codec: C, frame: Frame): Result[DecodeException, A] =
        Result.catching[DecodeException](readFrom(codec.newReader(input)))

    /** Decodes a value from a String using the given codec.
      *
      * Convenience method that converts the input string to UTF-8 bytes before decoding. Primarily useful with JSON.
      *
      * {{{
      * val s = Schema[User].rename(_.name, "userName")
      * val result = s.decodeString[Json]("""{"userName":"Alice","age":30}""")
      * // Result.Success(User("Alice", 30))
      * }}}
      *
      * @param input
      *   the string to decode
      * @return
      *   the decoded value, or a DecodeException if the input is malformed or does not match the schema
      */
    def decodeString[C <: Codec](input: String)(using codec: C, frame: Frame): Result[DecodeException, A] =
        decode[C](Span.fromUnsafe(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)))

    /** Adds a cross-field validation check.
      *
      * The predicate receives the root value, enabling checks that span multiple fields. Checks accumulate and are only executed when
      * `.validate` is called.
      *
      * {{{
      * Schema[Order].check(o => o.total >= 0, "total must be non-negative")
      * }}}
      *
      * For single-field checks, prefer the overload `check(_.field)(pred, msg)`.
      *
      * @param pred
      *   predicate that must return true for the value to be valid
      * @param msg
      *   error message when the predicate fails
      * @return
      *   a new Schema with the check accumulated
      */
    def check(pred: A => Boolean, msg: String)(using frame: Frame): Schema[A] { type Focused = Schema.this.Focused } =
        val segs = segments
        val rootCheck: A => Seq[ValidationFailedException] = (root: A) =>
            if pred(root) then Seq.empty
            else Seq(ValidationFailedException(segs, msg)(using frame))
        Schema.copyWith(this)(
            checks = checks :+ rootCheck
        )
    end check

    /** Adds a field-level validation check using a focus lambda to identify the field.
      *
      * The focus lambda navigates to the field, then the predicate is applied to the field's value. The check is accumulated on the root
      * Schema[A] (Focused is unchanged). This enables chaining checks on different fields:
      * {{{
      * Schema[User]
      *     .check(_.name)(_.nonEmpty, "required")    // Schema[User]
      *     .check(_.age)(_ > 0, "positive")          // Schema[User]
      * }}}
      */
    inline def check[V](inline focus: Focus.Select[A, Focused] => Focus.Select[A, V])(
        pred: V => Boolean,
        msg: String
    )(using Frame): Schema[A] { type Focused = Schema.this.Focused } =
        val navigated = focus(rootSelect)
        Schema.fieldCheck[A, V](this, navigated.getter, navigated.segments, pred, msg)
    end check

    /** Runs all accumulated checks against a value.
      *
      * @param root
      *   the value to validate
      * @return
      *   a Chunk of validation errors; empty if all checks pass
      */
    def validate(root: A): Chunk[ValidationFailedException] =
        Chunk.from(checks.flatMap(_(root)))

    /** Sets root-level documentation on this schema.
      *
      * @param description
      *   human-readable description of the type
      * @return
      *   a new Schema with the description set
      */
    def doc(description: String): Schema[A] { type Focused = Schema.this.Focused } =
        Schema.copyWith(this)(
            doc = Maybe(description)
        )
    end doc

    /** Returns the root-level documentation for this schema, or Maybe.empty if none set. */
    def doc: Maybe[String] = documentation

    /** Sets a discriminator field name for sealed trait serialization.
      *
      * When set, sealed trait variants are serialized in a flat format with the discriminator field identifying the variant, rather than
      * the default wrapper-object format.
      *
      * Default (wrapper format): `{"Circle": {"radius": 5.0}}`
      *
      * With discriminator("type"): `{"type": "Circle", "radius": 5.0}`
      *
      * @param fieldName
      *   the name of the discriminator field in the serialized output
      * @return
      *   a new Schema with the discriminator set
      */
    def discriminator(fieldName: String): Schema[A] { type Focused = Schema.this.Focused } =
        Schema.copyWith(this)(
            discriminatorField = Maybe(fieldName),
            representation = Schema.UnionRepresentation.Internal(fieldName)
        )
    end discriminator

    /** Selects the adjacently-tagged wire representation for a sum type.
      *
      * Encodes each variant as a two-field object: `tagKey` holds the resolved variant wire name
      * and `contentKey` holds the variant payload. Unlike the flat discriminator, the payload is
      * carried whole in the content position, so a non-object payload (a bare scalar, an array,
      * null) survives the round-trip. Decode reads the tag, resolves the variant (accepting any
      * configured alias), and reconstructs from the content. The tag resolves through the variant
      * naming layer. A decode whose input lacks `tagKey` fails with `MissingTagKeyException`.
      *
      * {{{
      * val s = Schema[Shape].adjacent("type", "content")
      * // Json.encode(Circle(10.0)) == """{"type":"Circle","content":{"radius":10.0}}"""
      * }}}
      */
    def adjacent(tagKey: String, contentKey: String): Schema[A] { type Focused = Schema.this.Focused } =
        Schema.copyWith(this)(
            representation = Schema.UnionRepresentation.Adjacent(tagKey, contentKey)
        )
    end adjacent

    /** Selects the nested positional-array (tuple) wire representation for a sum type.
      *
      * Encodes each variant as a two-element array: element 0 the resolved variant wire name,
      * element 1 the variant payload. A non-object payload rides through as the second element.
      * Decode reads element 0 as the tag (accepting any configured alias) and reconstructs from
      * element 1. The tag resolves through the variant naming layer. On a codec that cannot express
      * a top-level array (Protobuf), encode fails with `RepresentationUnsupportedException`.
      *
      * {{{
      * val s = Schema[Shape].tupleTagged
      * // Json.encode(Circle(10.0)) == """["Circle",{"radius":10.0}]"""
      * }}}
      */
    def tupleTagged: Schema[A] { type Focused = Schema.this.Focused } =
        Schema.copyWith(this)(
            representation = Schema.UnionRepresentation.Tuple
        )
    end tupleTagged

    /** Selects the positional-flattened (tuple-flat) wire representation for a sum type.
      *
      * Encodes each variant as an array whose element 0 is the resolved variant wire name and whose
      * remaining elements are the variant's payload field values spread positionally in declaration
      * order; field names are dropped. A field that is itself a record is one nested array element,
      * not deep-flattened. Decode reverse-resolves element 0 to the variant (accepting any configured
      * alias) and pairs the remaining elements with the variant's declaration-ordered fields; an
      * element count that does not match the variant's field count fails with a typed decode error.
      * The tag resolves through the variant naming layer. On a codec that cannot express a top-level
      * array (Protobuf), encode fails with `RepresentationUnsupportedException`. Unlike
      * `tupleTagged`, which nests the whole payload as the second element, this form spreads the
      * payload fields as separate elements.
      *
      * {{{
      * val s = Schema[Shape].tupleFlat
      * // Json.encode(Triangle(10.0, 10.0, 10.0)) == """["Triangle",10.0,10.0,10.0]"""
      * }}}
      */
    def tupleFlat: Schema[A] { type Focused = Schema.this.Focused } =
        Schema.copyWith(this)(
            representation = Schema.UnionRepresentation.TupleFlat
        )
    end tupleFlat

    /** Selects the untagged wire representation for a sum type.
      *
      * Encodes each variant as its bare payload with no tag or wrapper. Decode attempts each
      * variant's decoder in declaration order over a non-destructively captured copy of the input
      * and returns the first variant that decodes without error; if no variant matches, decode fails
      * with `NoVariantMatchException` listing the attempted variants. No tag is emitted or read, so
      * variant naming and aliases do not apply to a tag; field-level naming still applies to the
      * payload. Untagged decode requires a self-describing codec (such as: Json, Yaml, Ion, MsgPack); on a
      * non-self-describing codec (Protobuf) decode fails with a typed self-describing-reader error.
      *
      * {{{
      * val s = Schema[Shape].untagged
      * // Json.encode(Circle(10.0)) == """{"radius":10.0}"""
      * }}}
      */
    def untagged: Schema[A] { type Focused = Schema.this.Focused } =
        Schema.copyWith(this)(
            representation = Schema.UnionRepresentation.Untagged
        )
    end untagged

    /** Configures an ordered fallback chain of wire representations for a sum type.
      *
      * Encode selects the highest-priority entry the active codec can express; decode tries the
      * entries in declared order and returns the first that parses to a valid value. The first
      * entry is the primary; later entries degrade for codecs that cannot express the primary.
      * `External` is always expressible and is the implicit chain floor. A chain containing a
      * duplicate representation is rejected with `DuplicateRepresentationException` at this call.
      *
      * {{{
      * val s = Schema[Shape].representations(
      *     Schema.UnionRepresentation.TupleFlat,
      *     Schema.UnionRepresentation.Adjacent("type", "content"),
      *     Schema.UnionRepresentation.External
      * )
      * }}}
      */
    def representations(
        first: Schema.UnionRepresentation,
        rest: Schema.UnionRepresentation*
    )(using Frame): Schema[A] { type Focused = Schema.this.Focused } =
        val chain = first +: Chunk.from(rest)
        Schema.checkRepresentationChain(chain)
        Schema.copyWith(this)(
            representation = first,
            representationChain = Maybe(chain)
        )
    end representations

    /** Configures a single-fallback representation hint: the current primary, then `fallback`.
      *
      * Sugar over `representations(currentPrimary, fallback)`. The current primary is this schema's
      * `representation` (the default `External` unless a representation builder set it). Rejects a
      * `fallback` equal to the current primary with `DuplicateRepresentationException` at this call.
      *
      * {{{
      * val s = Schema[Shape].tupleFlat.orElseRepresentation(Schema.UnionRepresentation.External)
      * }}}
      */
    def orElseRepresentation(
        fallback: Schema.UnionRepresentation
    )(using Frame): Schema[A] { type Focused = Schema.this.Focused } =
        representations(representation, fallback)
    end orElseRepresentation

    /** The representation this schema's chain selects for the given codec capabilities.
      *
      * Total and deterministic: the same `(schema, capabilities)` always returns the same
      * representation. Returns the highest-priority chain entry the capabilities admit; with no
      * chain configured, returns this schema's single `representation`. `External` is always
      * admitted and is the chain floor, so this never throws. Exposed for tooling and tests.
      */
    def representationFor(capabilities: Codec.Capabilities): Schema.UnionRepresentation =
        representationChain match
            case Maybe.Present(chain) =>
                chain.find(rep => Schema.representationExpressibleBy(rep, capabilities))
                    .getOrElse(Schema.UnionRepresentation.External)
            case Maybe.Absent =>
                representation
    end representationFor

    /** Sets the ambiguity policy for untagged union decode.
      *
      * Under `Strict` (the default), a wire value that decodes successfully against more than one
      * member raises `AmbiguousVariantMatchException` listing the matched members. Under
      * `FirstMatch`, the first-declared member that decodes successfully is returned.
      *
      * This is a decode-only configuration: encode behavior is not affected. The policy is
      * preserved through any subsequent builder calls.
      *
      * {{{
      * val s = summon[Schema[Int | Long]].unionAmbiguity(Schema.UnionAmbiguity.FirstMatch)
      * // Json.decode[Int | Long]("42") returns Result.succeed(42) (Int wins as first-declared)
      * }}}
      */
    def unionAmbiguity(policy: Schema.UnionAmbiguity): Schema[A] { type Focused = Schema.this.Focused } =
        Schema.copyWith(this)(unionAmbiguityPolicy = policy)
    end unionAmbiguity

    /** Maps Scala sealed-trait variant names to wire discriminator values.
      *
      * Each pair is `(scalaVariantName, wireName)`. Under `.discriminator(field)`, the
      * wire string written for a variant becomes its mapped name, and decode reverse-maps
      * the wire string back to the Scala variant before dispatch. Explicit pairs here
      * override any `renameAllVariants` convention for the same variant. A wire name
      * targeted by two distinct variants is rejected with `VariantNameCollisionException`
      * at this call. Each referenced Scala variant name is validated against the type's
      * derived variant set; an unknown name raises `UnknownVariantException` at this call,
      * mirroring field `rename`'s from-must-exist guarantee. Without a `.discriminator(field)`
      * this configuration has no effect: the default wrapper-object format keeps the Scala
      * variant names.
      *
      * {{{
      * val s = Schema[Shape].discriminator("type").variantNames("Circle" -> "circle")
      * // Json.encode(Circle(5.0)) == """{"type":"circle","radius":5.0}"""
      * }}}
      */
    def variantNames(mappings: (String, String)*)(using Frame): Schema[A] { type Focused = Schema.this.Focused } =
        val pairs = Chunk.from(mappings)
        val known = Schema.variantScalaNames(structure)
        Schema.checkVariantNamesExist(known, pairs.map(_._1))
        val merged = Schema.mergeVariantPairs(variantNaming.variantPairs, pairs)
        Schema.checkVariantTargets(merged)
        Schema.copyWith(this)(
            variantNaming = variantNaming.copy(variantPairs = merged)
        )
    end variantNames

    /** Derives every variant's wire name from its Scala name by a case convention.
      *
      * Applies under `.discriminator(field)`. Explicit `variantNames` pairs override the
      * convention per variant. If the convention maps two variants to one wire name,
      * `VariantNameCollisionException` is raised at the first encode/decode. Without a
      * discriminator this configuration has no effect. Any previously registered variant
      * aliases are checked against the new convention-derived primaries at this call;
      * an alias that collides with a convention-derived primary raises
      * `VariantNameCollisionException`.
      *
      * {{{
      * val s = Schema[Shape].discriminator("type").renameAllVariants(Schema.NameCase.SnakeCase)
      * }}}
      */
    def renameAllVariants(nameCase: Schema.NameCase)(using Frame): Schema[A] { type Focused = Schema.this.Focused } =
        val updatedNaming   = variantNaming.copy(variantCase = Maybe(nameCase))
        val newEffectiveSet = Schema.effectiveVariantWires(structure, updatedNaming).toSet
        Schema.checkVariantAliases(newEffectiveSet, variantNaming.variantAliases)
        Schema.copyWith(this)(
            variantNaming = updatedNaming
        )
    end renameAllVariants

    /** Accepts alternate wire discriminator values for a variant on decode.
      *
      * `variantWireName` is the variant's effective wire name (after any `variantNames` /
      * `renameAllVariants`); `aliases` are additional strings decode resolves to that same
      * variant. Encode always emits the primary wire name. `variantWireName` is validated
      * to resolve to a known variant; an unresolvable name raises `UnknownVariantException`
      * at this call. An alias that collides with another variant's primary or alias is
      * rejected with `VariantNameCollisionException` at this call. Requires a discriminator
      * to have any effect.
      *
      * {{{
      * val s = Schema[Shape].discriminator("type").variantNames("Circle" -> "circle")
      *     .variantAlias("circle", "round", "disc")
      * }}}
      */
    def variantAlias(variantWireName: String, aliases: String*)(using Frame): Schema[A] { type Focused = Schema.this.Focused } =
        val wires = Schema.effectiveVariantWires(structure, variantNaming)
        Schema.checkVariantWireExists(wires, variantWireName)
        val added        = Chunk.from(aliases).map(a => (a, variantWireName))
        val merged       = variantNaming.variantAliases ++ added
        val effectiveSet = wires.toSet
        Schema.checkVariantAliases(effectiveSet, merged)
        Schema.copyWith(this)(
            variantNaming = variantNaming.copy(variantAliases = merged)
        )
    end variantAlias

    /** Derives every field's wire name from its Scala name by a case convention.
      *
      * Serialization-only: does NOT change `Focused` (unlike `rename`). Explicit per-field
      * `rename` overrides the convention. A convention that maps two fields to one wire
      * name raises `FieldNameCollisionException` at the first encode/decode. An alias
      * already registered whose target collides with a convention-derived primary wire name
      * raises `FieldNameCollisionException` at this call.
      *
      * {{{
      * val s = Schema[Account].renameAllFields(Schema.NameCase.SnakeCase)
      * // Json.encode(Account("a","b")) == """{"first_name":"a","last_name":"b"}"""
      * }}}
      */
    def renameAllFields(nameCase: Schema.NameCase)(using Frame): Schema[A] { type Focused = Schema.this.Focused } =
        val updatedNaming = variantNaming.copy(fieldCase = Maybe(nameCase))
        val newPrimaries  = Schema.effectiveFieldWireNames(sourceFields, renamedFields, updatedNaming)
        Schema.checkFieldAliases(Chunk.empty, variantNaming.fieldAliases, newPrimaries)
        Schema.copyWith(this)(
            variantNaming = updatedNaming
        )
    end renameAllFields

    /** Accepts alternate wire names for a field on decode (string form).
      *
      * `fieldWireName` is the field's effective wire name; `aliases` are accepted on
      * decode and resolve to the same field. Encode always emits the primary. Collisions
      * raise `FieldNameCollisionException` at this call.
      *
      * {{{
      * val s = Schema[Account].alias("first_name", "fname")
      * }}}
      */
    def alias(fieldWireName: String, aliases: String*)(using Frame): Schema[A] { type Focused = Schema.this.Focused } =
        val added     = Chunk.from(aliases).map(a => (a, fieldWireName))
        val merged    = variantNaming.fieldAliases ++ added
        val primaries = Schema.effectiveFieldWireNames(sourceFields, renamedFields, variantNaming)
        Schema.checkFieldAliases(variantNaming.fieldAliases, merged, primaries)
        Schema.copyWith(this)(
            variantNaming = variantNaming.copy(fieldAliases = merged)
        )
    end alias

    /** Field-alias overload identifying the field by a Focus lambda.
      *
      * Lambda form of `alias(fieldWireName, ...)`. The field is identified by the lambda
      * (IDE autocompletion); the alias is registered against the field's Scala name and
      * accepted on decode. Aliases are accepted on decode; Focused unchanged.
      *
      * {{{
      * val s = Schema[Account].alias(_.firstName)("first_name", "fname")
      * }}}
      */
    inline def alias[V](inline focus: Focus.Select[A, Focused] => Focus.Select[A, V])(
        aliases: String*
    )(using Frame): Schema[A] { type Focused = Schema.this.Focused } =
        val navigated = focus(rootSelect)
        alias(navigated.segments.last, aliases*)
    end alias

    /** Schema-wide policy: omit optional fields (None / Maybe.empty) on encode instead of writing
      * a null-valued key, so the encoded form carries no null-valued keys and covers
      * transform-injected optional slots. Per-field `omit(_.x).whenNone` overrides this for a
      * specific field.
      */
    def omitNone: Schema[A] { type Focused = Schema.this.Focused } =
        Schema.copyWith(this)(omitNoneAll = true)
    end omitNone

    /** Schema-wide policy: omit empty collection/map fields on encode (emit no key, not `[]`/`{}`),
      * and decode-default a missing collection/map field to the typed empty value. Per-field
      * `omit(_.x).whenEmpty` overrides this for a specific field.
      */
    def omitEmptyCollections: Schema[A] { type Focused = Schema.this.Focused } =
        Schema.copyWith(this)(omitEmptyCollectionsAll = true)
    end omitEmptyCollections

    /** Rejects input fields that do not match this schema's effective read field set.
      *
      * The policy is read-side only. Encode keeps the same output shape, while decode checks the
      * object field names accepted after field naming, aliases, and schema transforms have been
      * applied.
      */
    def denyUnknownFields: Schema[A] { type Focused = Schema.this.Focused } =
        Schema.copyWith(this)(denyUnknownFieldsEnabled = true)
    end denyUnknownFields

    /** Registers a decode-time supplier for the focused field.
      *
      * The supplier is used when the focused field is absent from input. The focused structural type
      * is preserved because the Scala field remains part of the schema; only the read policy gains a
      * fallback for missing input.
      */
    transparent inline def default[V](inline focus: Focus.Select[A, Focused] => Focus.Select[A, V])(
        supplier: => V
    )(using Frame): Schema[A] { type Focused = Schema.this.Focused } =
        ${ internal.SchemaTransformMacro.defaultFocusImpl[A, Focused, V]('this, 'focus, 'supplier) }
    end default

    /** Begins a per-field omit policy on the focused field. Finish with `.whenEmpty` or `.whenNone`.
      * The field is identified by the lens at compile time; `Focused` is preserved.
      *
      * {{{
      * val s = Schema[Cart].omit(_.items).whenEmpty
      * }}}
      */
    transparent inline def omit[V](inline focus: Focus.Select[A, Focused] => Focus.Select[A, V])(using
        Frame
    ): Schema.OmitWhen[A, Schema.this.Focused] =
        ${ internal.SchemaTransformMacro.omitFocusImpl[A, Focused, V]('this, 'focus) }
    end omit

    /** Sets per-field documentation using a focus lambda to identify the field.
      *
      * @param focus
      *   Focus lambda navigating to the field
      * @param description
      *   Human-readable description of the field
      */
    inline def doc[V](inline focus: Focus.Select[A, Focused] => Focus.Select[A, V])(description: String)
        : Schema[A] { type Focused = Schema.this.Focused } =
        val navigated = focus(rootSelect)
        Schema.withFieldDoc[A](this, navigated.segments, description)
    end doc

    /** Adds an example value to this schema.
      *
      * Multiple examples accumulate in order.
      *
      * @param value
      *   An example value of type A
      */
    def example(value: A): Schema[A] { type Focused = Schema.this.Focused } =
        Schema.copyWith(this)(
            examples = examples :+ value
        )
    end example

    /** Marks a field as deprecated using a focus lambda to identify the field.
      *
      * @param focus
      *   Focus lambda navigating to the field
      * @param reason
      *   Human-readable deprecation reason
      */
    inline def deprecated[V](inline focus: Focus.Select[A, Focused] => Focus.Select[A, V])(reason: String)
        : Schema[A] { type Focused = Schema.this.Focused } =
        val navigated = focus(rootSelect)
        Schema.withFieldDeprecated[A](this, navigated.segments, reason)
    end deprecated

    /** Returns the set of field/variant names for this schema's structural type.
      *
      * Requires a `Fields` instance for the focused type, summoned at the call site.
      */
    inline def fieldNames(using f: Fields[Focused]): Set[String] = f.names

    /** Returns the field descriptors for this schema's structural type.
      *
      * Requires a `Fields` instance for the focused type, summoned at the call site.
      */
    inline def fieldDescriptors(using f: Fields[Focused]): Seq[Field[?, ?]] = f.fields

    // --- Typed constraint methods ---

    /** Adds a minimum numeric constraint on the focused field.
      *
      * Both records a runtime check (value must be >= minimum) and stores a Constraint for JSON Schema enrichment.
      *
      * @param focus
      *   Focus lambda navigating to the numeric field
      * @param value
      *   The minimum value (inclusive)
      */
    inline def checkMin[V](inline focus: Focus.Select[A, Focused] => Focus.Select[A, V])(
        value: Double
    )(using frame: Frame, num: Numeric[V]): Schema[A] { type Focused = Schema.this.Focused } =
        val navigated          = focus(rootSelect)
        val pred: V => Boolean = v => num.toDouble(v) >= value
        Schema.fieldCheckWithConstraint[A, V](
            this,
            navigated.getter,
            navigated.segments,
            pred,
            s"must be >= $value",
            Schema.Constraint.Min(navigated.segments, value, exclusive = false)
        )
    end checkMin

    /** Adds a maximum numeric constraint on the focused field.
      *
      * Both records a runtime check (value must be <= maximum) and stores a Constraint for JSON Schema enrichment.
      */
    inline def checkMax[V](inline focus: Focus.Select[A, Focused] => Focus.Select[A, V])(
        value: Double
    )(using frame: Frame, num: Numeric[V]): Schema[A] { type Focused = Schema.this.Focused } =
        val navigated          = focus(rootSelect)
        val pred: V => Boolean = v => num.toDouble(v) <= value
        Schema.fieldCheckWithConstraint[A, V](
            this,
            navigated.getter,
            navigated.segments,
            pred,
            s"must be <= $value",
            Schema.Constraint.Max(navigated.segments, value, exclusive = false)
        )
    end checkMax

    /** Adds an exclusive minimum numeric constraint on the focused field.
      *
      * Both records a runtime check (value must be > minimum) and stores a Constraint for JSON Schema enrichment.
      */
    inline def checkExclusiveMin[V](inline focus: Focus.Select[A, Focused] => Focus.Select[A, V])(
        value: Double
    )(using frame: Frame, num: Numeric[V]): Schema[A] { type Focused = Schema.this.Focused } =
        val navigated          = focus(rootSelect)
        val pred: V => Boolean = v => num.toDouble(v) > value
        Schema.fieldCheckWithConstraint[A, V](
            this,
            navigated.getter,
            navigated.segments,
            pred,
            s"must be > $value",
            Schema.Constraint.Min(navigated.segments, value, exclusive = true)
        )
    end checkExclusiveMin

    /** Adds an exclusive maximum numeric constraint on the focused field.
      *
      * Both records a runtime check (value must be < maximum) and stores a Constraint for JSON Schema enrichment.
      */
    inline def checkExclusiveMax[V](inline focus: Focus.Select[A, Focused] => Focus.Select[A, V])(
        value: Double
    )(using frame: Frame, num: Numeric[V]): Schema[A] { type Focused = Schema.this.Focused } =
        val navigated          = focus(rootSelect)
        val pred: V => Boolean = v => num.toDouble(v) < value
        Schema.fieldCheckWithConstraint[A, V](
            this,
            navigated.getter,
            navigated.segments,
            pred,
            s"must be < $value",
            Schema.Constraint.Max(navigated.segments, value, exclusive = true)
        )
    end checkExclusiveMax

    /** Adds a minimum length constraint on a String field.
      *
      * Both records a runtime check (string length must be >= minimum) and stores a Constraint for JSON Schema enrichment.
      */
    inline def checkMinLength(inline focus: Focus.Select[A, Focused] => Focus.Select[A, String])(
        value: Int
    )(using frame: Frame): Schema[A] { type Focused = Schema.this.Focused } =
        val navigated = focus(rootSelect)
        Schema.fieldCheckWithConstraint[A, String](
            this,
            navigated.getter,
            navigated.segments,
            _.length >= value,
            s"length must be >= $value",
            Schema.Constraint.MinLength(navigated.segments, value)
        )
    end checkMinLength

    /** Adds a maximum length constraint on a String field.
      *
      * Both records a runtime check (string length must be <= maximum) and stores a Constraint for JSON Schema enrichment.
      */
    inline def checkMaxLength(inline focus: Focus.Select[A, Focused] => Focus.Select[A, String])(
        value: Int
    )(using frame: Frame): Schema[A] { type Focused = Schema.this.Focused } =
        val navigated = focus(rootSelect)
        Schema.fieldCheckWithConstraint[A, String](
            this,
            navigated.getter,
            navigated.segments,
            _.length <= value,
            s"length must be <= $value",
            Schema.Constraint.MaxLength(navigated.segments, value)
        )
    end checkMaxLength

    /** Adds a regex pattern constraint on a String field.
      *
      * Both records a runtime check (string must match regex) and stores a Constraint for JSON Schema enrichment.
      *
      * Platform note: uses `java.util.regex.Pattern`. Scala.js and Scala Native emulate this via translation and don't support all JVM
      * regex features. Unsupported on non-JVM platforms:
      *   - Possessive quantifiers (`a++`, `a*+`, `a?+`)
      *   - Atomic groups (`(?>...)`)
      *   - Some Unicode property classes (`\p{...}`)
      *   - Lookbehind on older JS engines
      *
      * For cross-platform code, use POSIX regex features: character classes, anchors, alternation, basic quantifiers, capture groups,
      * backreferences, and simple lookahead.
      */
    inline def checkPattern(inline focus: Focus.Select[A, Focused] => Focus.Select[A, String])(
        regex: String
    )(using frame: Frame): Schema[A] { type Focused = Schema.this.Focused } =
        val navigated = focus(rootSelect)
        Schema.fieldCheckWithConstraint[A, String](
            this,
            navigated.getter,
            navigated.segments,
            _.matches(regex),
            s"must match pattern $regex",
            Schema.Constraint.Pattern(navigated.segments, regex)
        )
    end checkPattern

    /** Adds a format annotation on a String field.
      *
      * Format is advisory in JSON Schema: no runtime validation check is produced. Only stores a Constraint for JSON Schema enrichment.
      */
    inline def checkFormat(inline focus: Focus.Select[A, Focused] => Focus.Select[A, String])(
        fmt: String
    ): Schema[A] { type Focused = Schema.this.Focused } =
        val navigated = focus(rootSelect)
        Schema.fieldConstraintOnly[A, String](
            this,
            Schema.Constraint.Format(navigated.segments, fmt)
        )
    end checkFormat

    /** Adds a minimum items constraint on a collection field.
      *
      * Both records a runtime check (collection size must be >= minimum) and stores a Constraint for JSON Schema enrichment.
      */
    inline def checkMinItems[C <: Iterable[?]](inline focus: Focus.Select[A, Focused] => Focus.Select[A, C])(
        value: Int
    )(using frame: Frame): Schema[A] { type Focused = Schema.this.Focused } =
        val navigated = focus(rootSelect)
        Schema.fieldCheckWithConstraint[A, C](
            this,
            navigated.getter,
            navigated.segments,
            _.size >= value,
            s"must have >= $value items",
            Schema.Constraint.MinItems(navigated.segments, value)
        )
    end checkMinItems

    /** Adds a maximum items constraint on a collection field.
      *
      * Both records a runtime check (collection size must be <= maximum) and stores a Constraint for JSON Schema enrichment.
      */
    inline def checkMaxItems[C <: Iterable[?]](inline focus: Focus.Select[A, Focused] => Focus.Select[A, C])(
        value: Int
    )(using frame: Frame): Schema[A] { type Focused = Schema.this.Focused } =
        val navigated = focus(rootSelect)
        Schema.fieldCheckWithConstraint[A, C](
            this,
            navigated.getter,
            navigated.segments,
            _.size <= value,
            s"must have <= $value items",
            Schema.Constraint.MaxItems(navigated.segments, value)
        )
    end checkMaxItems

    /** Adds a uniqueItems constraint on a collection field.
      *
      * Both records a runtime check (all elements must be distinct) and stores a Constraint for JSON Schema enrichment.
      */
    inline def checkUniqueItems[C <: Iterable[?]](inline focus: Focus.Select[A, Focused] => Focus.Select[A, C])(
        using frame: Frame
    ): Schema[A] { type Focused = Schema.this.Focused } =
        val navigated = focus(rootSelect)
        Schema.fieldCheckWithConstraint[A, C](
            this,
            navigated.getter,
            navigated.segments,
            coll => Chunk.from(coll).distinct.size == coll.size,
            "items must be unique",
            Schema.Constraint.UniqueItems(navigated.segments)
        )
    end checkUniqueItems

    // --- Field ID methods (for documentation/introspection) ---

    /** Returns the stable field ID for a given field name.
      *
      * Field IDs are computed using MurmurHash3 of the field name, producing stable 21-bit positive integers. These IDs are used by binary
      * formats like Protocol Buffers and MessagePack for schema evolution compatibility.
      *
      * The ID remains stable across:
      *   - Different compilation runs
      *   - Adding/removing other fields
      *   - Reordering field declarations
      *
      * @param name
      *   The field name to look up
      * @return
      *   The stable field ID (1 to 2,097,152)
      */
    def fieldId(name: String): Int =
        fieldIdOverrides.getOrElse(Seq(name), kyo.internal.CodecMacro.fieldId(name))

    /** Returns all field IDs for this schema, including computed defaults.
      *
      * For each field in `_sourceFields`, returns a tuple of (fieldName, fieldId). Custom overrides from `_fieldIds` take precedence over
      * computed defaults.
      */
    def fieldIds: Dict[String, Int] =
        Dict.from(sourceFields.map(f => f.name -> fieldId(f.name)).toMap)

    /** Sets a custom field ID for a field identified by a Focus lambda.
      *
      * Use this when you need a specific field ID for interoperability with existing binary schemas (e.g., matching an existing `.proto`
      * definition). This is for documentation/introspection purposes - the actual codec uses hash-based IDs computed at compile time.
      *
      * {{{
      * Schema[User].fieldId(_.age)(42)  // Sets field ID 42 for the 'age' field
      * }}}
      *
      * @param focus
      *   Focus lambda navigating to the field
      * @param id
      *   The custom field ID (must be positive)
      */
    inline def fieldId[V](inline focus: Focus.Select[A, Focused] => Focus.Select[A, V])(
        id: Int
    )(using frame: Frame): Schema[A] { type Focused = Schema.this.Focused } =
        if id <= 0 then
            throw TransformFailedException(s"Field ID must be positive, got $id")(using frame)
        val navigated = focus(rootSelect)
        Schema.copyWith(this)(
            fieldIds = fieldIdOverrides.updated(navigated.segments, id)
        )
    end fieldId

    // --- Transform methods ---

    /** Drops a field from the structural type Focused.
      *
      * Returns `Schema[A] { type Focused = F' }` where F' is Focused without the named field. The field name must be a string literal.
      * Fails at compile time if the field does not exist in Focused.
      *
      * Note: the dropped field is omitted from all serialization output. Decoding from formats that still include the field is safe; the
      * extra data is ignored.
      */
    transparent inline def drop(inline fieldName: String): Any =
        ${ internal.SchemaTransformMacro.dropImpl[A, Focused]('this, 'fieldName) }

    /** Renames a field in the structural type Focused.
      *
      * Returns `Schema[A] { type Focused = F' }` where F' replaces the field named `from` with one named `to`, keeping the same value type.
      * Fails at compile time if `from` does not exist or `to` already exists.
      *
      * Note: the renamed field uses the new name in all serialization output. Decoding expects the new name; the old name is no longer
      * recognized unless the schema is reversed.
      */
    transparent inline def rename(inline from: String, inline to: String): Any =
        ${ internal.SchemaTransformMacro.renameImpl[A, Focused]('this, 'from, 'to) }

    /** Selects only the named fields, removing all others.
      *
      * Returns `Schema[A] { type Focused = S }` where S is the intersection of selected fields. Fails at compile time if any named field
      * does not exist in Focused.
      *
      * Note: unselected fields are dropped from serialization output.
      */
    transparent inline def select(inline fieldNames: String*): Any =
        ${ internal.SchemaTransformMacro.selectImpl[A, Focused]('this, 'fieldNames) }

    /** Flattens nested product fields into the parent.
      *
      * For each field whose value type is a case class, replaces the field with the case class's sub-fields. Primitive and non-case-class
      * fields pass through unchanged. Returns `Schema[A] { type Focused = FlattenedType }`.
      */
    transparent inline def flatten: Any =
        ${ internal.SchemaTransformMacro.flattenImpl[A, Focused]('this) }

    /** Adds a new computed field to the structural type Focused.
      *
      * Returns `Schema[A] { type Focused = Focused & ("name" ~ V) }`. The compute function is called on the root value at serialization
      * time. Fails at compile time if the field name already exists in Focused or is empty.
      */
    transparent inline def add[V](inline name: String)(f: A => V): Any =
        ${ internal.SchemaTransformMacro.addImpl[A, Focused, V]('this, 'name, 'f) }

    // --- Lambda-based transform overloads ---

    /** Drops a field identified by a Focus lambda from the structural type Focused.
      *
      * Lambda version of `drop(fieldName: String)`. The lambda navigates to the field, and the field name is extracted at compile time.
      * Provides IDE autocompletion and refactoring safety.
      *
      * {{{
      * Schema[User].drop(_.password)  // equivalent to Schema[User].drop("password")
      * }}}
      */
    transparent inline def drop[V](
        inline focus: Focus.Select[A, Focused] => Focus.Select[A, V]
    ): Any =
        ${ internal.SchemaTransformMacro.dropFocusImpl[A, Focused]('this, 'focus) }

    /** Renames a field identified by a Focus lambda in the structural type Focused.
      *
      * Lambda version of `rename(from: String, to: String)`. The source field is identified by the lambda, and the target name is a string
      * literal. Provides IDE autocompletion for the source field.
      *
      * {{{
      * Schema[User].rename(_.createdAt, "memberSince")  // equivalent to Schema[User].rename("createdAt", "memberSince")
      * }}}
      */
    transparent inline def rename[V](
        inline focus: Focus.Select[A, Focused] => Focus.Select[A, V],
        inline to: String
    ): Any =
        ${ internal.SchemaTransformMacro.renameFocusImpl[A, Focused]('this, 'focus, 'to) }

    /** Selects only the fields identified by Focus lambdas, removing all others.
      *
      * Lambda version of `select(fieldNames: String*)`. Each lambda navigates to a field, and the field names are extracted at compile
      * time. Provides IDE autocompletion for the fields.
      *
      * {{{
      * Schema[User].select(_.name, _.age)  // equivalent to Schema[User].select("name", "age")
      * }}}
      */
    @targetName("selectFocus")
    transparent inline def select(
        inline focuses: (Focus.Select[A, Focused] => Focus.Select[A, ?])*
    ): Any =
        ${ internal.SchemaTransformMacro.selectFocusImpl[A, Focused]('this, 'focuses) }

    /** Returns a Record containing default values for fields of the focus type that have defaults.
      *
      * Only includes fields with compile-time defaults. Fields without defaults are not present in the returned Record. For non-case-class
      * types or types with no defaults, returns an empty Record.
      */
    transparent inline def defaults: Any =
        ${ internal.FocusMacro.defaultsImpl[A, Focused] }

    // --- Structure integration ---

    /** Runtime structural projection for A. Sole source of truth for "what shape does this Schema produce
      * on the wire": consumed by Json.JsonSchema.from[A], Protobuf.ProtoSchema.from[A], and the
      * case-class derivation macro to build product/sum structures.
      *
      * Provided by every concrete Schema instance via Schema.init's anonymous-class override.
      * Omitting `structure` when calling Schema.init is a compile error.
      *
      * The returned Structure.Type is one of: Primitive, Product, Sum, Collection, Optional,
      * Mapping, or Open. Schema.transform preserves the structure of the source schema.
      */
    def structure: Structure.Type

    // --- Structural field operations ---

    /** Folds over the fields of A with fully typed field descriptors and values.
      *
      * Uses a polymorphic function to preserve the exact field name and value types at each step. The macro unrolls the fold at compile
      * time, calling `f` with the correct singleton name type `N` and value type `V` for each field.
      *
      * Iterates original case class fields in declaration order. Respects transforms: dropped fields are skipped, renamed fields use the
      * new name, computed fields are included.
      *
      * @param value
      *   The value to fold over
      * @param init
      *   The initial accumulator value
      * @param f
      *   A polymorphic fold function called with the correct types for each field
      */
    inline def fold[R](value: A)(init: R)(
        f: [N <: String, V] => (R, Field[N, V], V) => R
    ): R =
        ${ internal.SchemaTransformMacro.foldFieldsImpl[A, Focused, R]('this, 'value, 'init, 'f) }

    /** Returns a reusable function that converts a value of type A to a Record.
      *
      * Respects transforms: dropped fields are omitted, renamed fields use the new name, computed fields are included. The returned
      * function can be stored and applied to multiple values.
      */
    def toRecord(using A <:< Product): A => Record[Focused] =
        (a: A) => this.resultOf(a)

    // --- Focus lambda navigation (zero-conflict) ---

    /** Navigates via a lambda on Focus, auto-detecting product vs sum navigation.
      *
      * For product-only navigation (case class fields), returns `Focus[Root, Value, Focus.Id]` with direct
      * `get`/`set`/`update`. For navigation that crosses a sum type (sealed trait variant), returns `Focus[Root, Value, Maybe]` with
      * Maybe-wrapped `get`/`set`/`update`.
      *
      * Usage:
      * {{{
      * Schema[Person].focus(_.name)                    // Focus[Person, String, Focus.Id]
      * Schema[Drawing].focus(_.shape.Circle.radius)    // Focus[Drawing, Double, Maybe]
      * }}}
      */
    transparent inline def focus[V](inline f: Focus.Select[A, Focused] => Focus.Select[A, V]): Any =
        ${ internal.FocusMacro.focusImpl[A, Focused, V]('this, 'f) }

    // --- Collection traversal ---

    /** Navigates into a collection-typed field, providing bulk operations on all elements.
      *
      * Uses a Focus lambda to identify the collection field. Returns `Focus[Root, E, Chunk]` for get/set/update operations on all
      * elements. For element validation, use `Schema.check` with a predicate over the collection.
      *
      * {{{
      * val each = Schema[Order].foreach(_.items)
      * each.get(order)                         // Chunk[Item]
      * each.update(order)(item => item.copy(price = item.price * 1.1))
      * }}}
      */
    inline def foreach[C <: Seq[E], E](inline f: Focus.Select[A, Focused] => Focus.Select[A, C]): Focus[A, E, Chunk] =
        val navigated = f(rootSelect)
        Focus.createChunk[A, E](
            root => Chunk.from(navigated.getter(root).getOrElse(Chunk.empty).asInstanceOf[Seq[E]]),
            (root, elems) =>
                val original = navigated.getter(root).getOrElse(Chunk.empty).asInstanceOf[Seq[E]]
                navigated.setter(root, original.iterableFactory.from(elems).asInstanceOf[C])
            ,
            navigated.segments,
            this
        )
    end foreach

    // --- Instance convenience methods ---

    /** Returns a reusable Convert from A to B.
      *
      * Requires that B's fields are a subset of Focused's fields (after transforms), with matching types, or have defaults. Matches fields
      * from the transformed type Focused, not the original type A.
      */
    inline def convert[B]: Convert[A, B] =
        ${ internal.SchemaConvertMacro.toTransformedImpl[A, Focused, B]('this) }

    /** Transforms this Schema[A] into Schema[B] with delegated serialization.
      *
      * Encode uses `from(b)` to convert B to A then serializes. Decode deserializes to A then uses `to(a)` to convert to B.
      *
      * {{{
      * opaque type Email = String
      * object Email:
      *   given Schema[Email] = Schema.stringSchema.transform[Email](identity)(identity)
      * }}}
      *
      * @tparam B
      *   the target type
      * @param to
      *   total conversion from A to B
      * @param from
      *   total conversion from B to A
      * @return
      *   a new Schema[B] with delegated serialization
      */
    def transform[B](to: A => B)(from: B => A): Schema[B] =
        val self = this
        Schema.init[B](
            writeFn = (b: B, w: Writer) => self.serializeWrite(from(b), w),
            readFn = (r: Reader) => to(self.serializeRead(r)),
            structure = self.structure
        )
    end transform

    /** Overrides the focused field's encode and decode functions.
      *
      * The field is selected at compile time through the focus lambda and the schema's `Focused`
      * type is preserved. A later transform for the same source field replaces both directions.
      */
    transparent inline def transformField[V](inline focus: Focus.Select[A, Focused] => Focus.Select[A, V])(
        write: (V, Codec.Writer) => Unit
    )(read: Codec.Reader => V)(using Frame): Schema[A] { type Focused = Schema.this.Focused } =
        ${ internal.SchemaTransformMacro.transformFieldFocusImpl[A, Focused, V]('this, 'focus, 'write, 'read) }
    end transformField

    /** Overrides the focused field's encode function.
      *
      * If a read transform already exists for the same source field, it is preserved. This lets
      * write-side and read-side overrides be configured independently while sharing one field
      * transform slot.
      */
    transparent inline def transformFieldWrite[V](inline focus: Focus.Select[A, Focused] => Focus.Select[A, V])(
        write: (V, Codec.Writer) => Unit
    )(using Frame): Schema[A] { type Focused = Schema.this.Focused } =
        ${ internal.SchemaTransformMacro.transformFieldWriteFocusImpl[A, Focused, V]('this, 'focus, 'write) }
    end transformFieldWrite

    /** Overrides the focused field's decode function.
      *
      * If a write transform already exists for the same source field, it is preserved. This lets
      * read-side and write-side overrides be configured independently while sharing one field
      * transform slot.
      */
    transparent inline def transformFieldRead[V](inline focus: Focus.Select[A, Focused] => Focus.Select[A, V])(
        read: Codec.Reader => V
    )(using Frame): Schema[A] { type Focused = Schema.this.Focused } =
        ${ internal.SchemaTransformMacro.transformFieldReadFocusImpl[A, Focused, V]('this, 'focus, 'read) }
    end transformFieldRead

    // --- Ordering/CanEqual givens ---

    /** Derives an Ordering[A] for the source type by comparing fields in declaration order. */
    inline given order(using Mirror.ProductOf[A]): Ordering[A] = Schema.deriveOrdering[A]

    /** Returns a CanEqual[A, A] instance for the source type. */
    inline given canEqual: CanEqual[A, A] = CanEqual.derived

    // --- Internal ---

    /** Adds a computed field function. Used by addImpl. */
    private[kyo] def withComputedField[R](fieldName: String, f: A => Any): Schema[A] { type Focused = Schema.this.Focused } =
        Schema.copyWith(this)(
            computedFields = computedFields :+ (fieldName, f)
        )
    end withComputedField

    /** Converts a value of type A to a Record containing the fields of Focused.
      *
      * Internal implementation used by the `toRecord` function. Respects transforms: dropped fields are omitted, renamed fields use the new
      * name, computed fields are included.
      */
    private[kyo] def resultOf(value: A)(using ev: A <:< Product): Record[Focused] =
        val product = ev(value)

        // Resolve rename chains: name->userName, userName->displayName => name->displayName
        val forwardMap = renamedFields.toMap
        def resolveTarget(name: String): String =
            forwardMap.get(name) match
                case Some(next) => resolveTarget(next)
                case None       => name

        val resolvedRenames = sourceFields.flatMap { sf =>
            if forwardMap.contains(sf.name) then
                Some((sf.name, resolveTarget(sf.name)))
            else None
        }
        val renamedSourceNames = resolvedRenames.map(_._1).toSet

        // Iterate original case class fields
        val dictFromFields = sourceFields.zipWithIndex.foldLeft(Dict.empty[String, Any]) { case (acc, (sourceField, idx)) =>
            val fieldName = sourceField.name
            if !droppedFields.contains(fieldName) && !renamedSourceNames.contains(fieldName) then
                acc.update(fieldName, product.productElement(idx))
            else acc
            end if
        }

        // Handle renamed fields
        val dictWithRenames = resolvedRenames.foldLeft(dictFromFields) { case (acc, (sourceName, targetName)) =>
            val originalIdx = sourceFields.indexWhere(_.name == sourceName)
            if originalIdx >= 0 then
                acc.update(targetName, product.productElement(originalIdx))
            else acc
            end if
        }

        // Handle computed fields
        val dict = computedFields.foldLeft(dictWithRenames) { case (acc, (name, compute)) =>
            acc.update(name, compute(value))
        }

        new Record[Any](dict).asInstanceOf[Record[Focused]]
    end resultOf

    /** Writes a value to a Writer (low-level serialization).
      *
      * This is the direct Writer API used by tests and internal code. For high-level usage, prefer `Json.encode` or `Protobuf.encode`.
      */
    private[kyo] def writeTo(value: A, writer: Writer)(using Frame): Unit =
        internal.SchemaSerializer.writeTo(this, value, writer)

    /** Reads a value from a Reader (low-level deserialization).
      *
      * This is the direct Reader API used by tests and internal code. For high-level usage, prefer `Json.decode` or `Protobuf.decode`.
      */
    private[kyo] def readFrom(reader: Reader): A =
        internal.SchemaSerializer.readFrom(this, reader)

    /** Converts a value to its untyped Structure.Value representation.
      *
      * @param value
      *   the typed value to convert
      * @return
      *   the untyped value tree
      */
    private[kyo] def toStructureValue(value: A): Structure.Value =
        val w = StructureValueWriter()
        writeTo(value, w)(using Frame.internal)
        w.getResult
    end toStructureValue

    /** Converts an untyped Structure.Value back to a typed value.
      *
      * @param value
      *   the untyped value tree
      * @return
      *   the decoded value, or a DecodeException if the dynamic value does not match the schema
      */
    private[kyo] def fromStructureValue(value: Structure.Value)(using Frame): Result[DecodeException, A] =
        val r = StructureValueReader(value)
        Result.catching[DecodeException](serializeRead(r))
    end fromStructureValue

    /** Combines validation checks from another Schema into this one.
      *
      * Returns a new Schema[A] with checks from both this and other. The structural type Focused, transforms, and all other properties are
      * preserved from this Schema -- only the checks are merged.
      *
      * @param other
      *   Another Schema[A] whose checks should be combined with this Schema's checks
      */
    private[kyo] def mergeChecks(other: Schema[A]): Schema[A] { type Focused = Schema.this.Focused } =
        Schema.copyWith(this)(
            checks = checks ++ other.checks,
            constraints = constraints ++ other.constraints,
            fieldIds = fieldIdOverrides ++ other.fieldIdOverrides
        )
    end mergeChecks

end Schema

/** Lambda-based navigation through structural types.
  *
  * Focus extends Dynamic inside lambdas to provide zero-conflict field navigation. It has ONLY selectDynamic -- no other methods -- so it
  * cannot collide with any user-defined field names.
  *
  * Used inside lambda arguments to Schema operations: `meta.focus(_.name)`, `meta.focus(_.address.city)`,
  * `meta.focus(_.shape.Circle.radius)`.
  *
  * Navigation works uniformly for products and sums:
  *   - Product fields (AndType): `_.name` resolves via `"name" ~ String` pairs
  *   - Sum variants (OrType): `_.Circle` resolves via `"Circle" ~ Circle` pairs
  *
  * @tparam A
  *   The root type being navigated (stays fixed through navigation)
  * @tparam F
  *   The structural type at the current navigation point
  */

object Schema:

    /** Core factory: inlines the caller's four lambdas into the abstract method bodies of a fresh `new Schema[A] { ... }` subclass.
      * Because `writeFn`, `readFn`, `getterFn`, `setterFn` are `inline` parameters, Scala 3 substitutes the caller's expression directly
      * into the method body: no `Function` closure is allocated.
      *
      * The `@nowarn("msg=anonymous")` suppresses the anonymous-class-creation warning emitted for every inline expansion (pattern copied
      * from `SchemaOrdering.scala:19`).
      */
    @nowarn("msg=anonymous")
    inline def init[A](
        inline writeFn: (A, Writer) => Unit,
        inline readFn: Reader => A,
        inline getterFn: A => Maybe[Any] = (a: A) => Maybe(a).asInstanceOf[Maybe[Any]],
        inline setterFn: (A, Any) => A = (_: A, v: Any) => v.asInstanceOf[A],
        segments: Seq[String] = Seq.empty,
        examples: Chunk[A] = Chunk.empty,
        fieldDocs: Map[Seq[String], String] = Map.empty,
        fieldDeprecated: Map[Seq[String], String] = Map.empty,
        constraints: Seq[Schema.Constraint] = Seq.empty,
        droppedFields: Set[String] = Set.empty,
        renamedFields: Chunk[(String, String)] = Chunk.empty,
        computedFields: Chunk[(String, A => Any)] = Chunk.empty,
        sourceFields: Seq[Field[?, ?]] = Seq.empty,
        checks: Seq[A => Seq[ValidationFailedException]] = Seq.empty,
        documentation: Maybe[String] = Maybe.empty,
        fieldIdOverrides: Map[Seq[String], Int] = Map.empty,
        discriminatorField: Maybe[String] = Maybe.empty,
        variantNaming: Schema.VariantNaming = Schema.VariantNaming(),
        representation: Schema.UnionRepresentation = Schema.UnionRepresentation.External,
        representationChain: Maybe[Chunk[Schema.UnionRepresentation]] = Maybe.empty,
        omitPolicies: Chunk[(String, Schema.OmitPolicy)] = Chunk.empty,
        omitNoneAll: Boolean = false,
        omitEmptyCollectionsAll: Boolean = false,
        unionAmbiguityPolicy: Schema.UnionAmbiguity = Schema.UnionAmbiguity.Strict,
        variantDecoders: Chunk[Codec.Reader => Any] = Chunk.empty,
        denyUnknownFieldsEnabled: Boolean = false,
        fieldDefaults: Chunk[(String, Schema.FieldDefault)] = Chunk.empty,
        fieldTransforms: Chunk[(String, Schema.FieldTransform[A])] = Chunk.empty[(String, Schema.FieldTransform[A])],
        structure: => Structure.Type = Structure.Type.Open(Tag[Any])
    ): Schema[A] =
        // Lazy capture defers inner.structure access until structure is first queried.
        // Container givens pass `inner.structure` as the structure argument; lazy evaluation
        // prevents initialization cycles for recursive structure type graphs.
        lazy val _structure = structure
        new Schema[A](
            segments,
            examples,
            fieldDocs,
            fieldDeprecated,
            constraints,
            droppedFields,
            renamedFields,
            computedFields,
            sourceFields,
            checks,
            documentation,
            fieldIdOverrides,
            discriminatorField,
            variantNaming,
            representation,
            representationChain,
            omitPolicies,
            omitNoneAll,
            omitEmptyCollectionsAll,
            unionAmbiguityPolicy,
            variantDecoders,
            denyUnknownFieldsEnabled,
            fieldDefaults,
            fieldTransforms,
            Chunk.empty
        ):
            @publicInBinary def serializeWrite(value: A, writer: Writer): Unit =
                if hasTransforms then transformedWrite(value, writer)
                else writeFn(value, writer)
            @publicInBinary def serializeRead(reader: Reader): A =
                if hasReadTransforms then transformedRead(reader)
                else readFn(reader)
            @publicInBinary override def rawSerializeWrite(value: A, writer: Writer): Unit = writeFn(value, writer)
            @publicInBinary override def rawSerializeRead(reader: Reader): A               = readFn(reader)
            @publicInBinary def getter(value: A): Maybe[Any]                               = getterFn(value)
            @publicInBinary def setter(value: A, next: Any): A                             = setterFn(value, next)
            override def structure: Structure.Type                                         = _structure
        end new
    end init

    /** Constructs a schema with no optional read/write policy slots configured. */
    @nowarn("msg=anonymous")
    inline def init[A](
        inline writeFn: (A, Writer) => Unit,
        inline readFn: Reader => A,
        inline getterFn: A => Maybe[Any],
        inline setterFn: (A, Any) => A,
        segments: Seq[String],
        examples: Chunk[A],
        fieldDocs: Map[Seq[String], String],
        fieldDeprecated: Map[Seq[String], String],
        constraints: Seq[Schema.Constraint],
        droppedFields: Set[String],
        renamedFields: Chunk[(String, String)],
        computedFields: Chunk[(String, A => Any)],
        sourceFields: Seq[Field[?, ?]],
        checks: Seq[A => Seq[ValidationFailedException]],
        documentation: Maybe[String],
        fieldIdOverrides: Map[Seq[String], Int],
        discriminatorField: Maybe[String],
        variantNaming: Schema.VariantNaming,
        representation: Schema.UnionRepresentation,
        representationChain: Maybe[Chunk[Schema.UnionRepresentation]],
        omitPolicies: Chunk[(String, Schema.OmitPolicy)],
        omitNoneAll: Boolean,
        omitEmptyCollectionsAll: Boolean,
        unionAmbiguityPolicy: Schema.UnionAmbiguity,
        variantDecoders: Chunk[Codec.Reader => Any],
        structure: => Structure.Type
    ): Schema[A] =
        init[A](
            writeFn = writeFn,
            readFn = readFn,
            getterFn = getterFn,
            setterFn = setterFn,
            segments = segments,
            examples = examples,
            fieldDocs = fieldDocs,
            fieldDeprecated = fieldDeprecated,
            constraints = constraints,
            droppedFields = droppedFields,
            renamedFields = renamedFields,
            computedFields = computedFields,
            sourceFields = sourceFields,
            checks = checks,
            documentation = documentation,
            fieldIdOverrides = fieldIdOverrides,
            discriminatorField = discriminatorField,
            variantNaming = variantNaming,
            representation = representation,
            representationChain = representationChain,
            omitPolicies = omitPolicies,
            omitNoneAll = omitNoneAll,
            omitEmptyCollectionsAll = omitEmptyCollectionsAll,
            unionAmbiguityPolicy = unionAmbiguityPolicy,
            variantDecoders = variantDecoders,
            denyUnknownFieldsEnabled = false,
            fieldDefaults = Chunk.empty,
            fieldTransforms = Chunk.empty[(String, Schema.FieldTransform[A])],
            structure = structure
        )
    end init

    /** Typed-focus variant of `Schema.init`. Produces `Schema[A] { type Focused = F }`. Internally, the user's `getterFn` and `setterFn`
      * are stored as `A => Maybe[Any]` / `(A, Any) => A` via erased Function-type casts. The cast has no runtime effect and keeps JVM
      * bytecode parameter types erased to `Object`, avoiding a `checkcast` on F (F is commonly a structural `Record.~` type with no runtime
      * class).
      */
    @nowarn("msg=anonymous")
    inline def initFocused[A, F](
        inline writeFn: (A, Writer) => Unit,
        inline readFn: Reader => A,
        inline getterFn: A => Maybe[F],
        inline setterFn: (A, F) => A,
        segments: Seq[String] = Seq.empty,
        sourceFields: Seq[Field[?, ?]] = Seq.empty,
        examples: Chunk[A] = Chunk.empty,
        fieldDocs: Map[Seq[String], String] = Map.empty,
        fieldDeprecated: Map[Seq[String], String] = Map.empty,
        constraints: Seq[Schema.Constraint] = Seq.empty,
        droppedFields: Set[String] = Set.empty,
        renamedFields: Chunk[(String, String)] = Chunk.empty,
        computedFields: Chunk[(String, A => Any)] = Chunk.empty,
        checks: Seq[A => Seq[ValidationFailedException]] = Seq.empty,
        documentation: Maybe[String] = Maybe.empty,
        fieldIdOverrides: Map[Seq[String], Int] = Map.empty,
        discriminatorField: Maybe[String] = Maybe.empty,
        variantNaming: Schema.VariantNaming = Schema.VariantNaming(),
        representation: Schema.UnionRepresentation = Schema.UnionRepresentation.External,
        representationChain: Maybe[Chunk[Schema.UnionRepresentation]] = Maybe.empty,
        omitPolicies: Chunk[(String, Schema.OmitPolicy)] = Chunk.empty,
        omitNoneAll: Boolean = false,
        omitEmptyCollectionsAll: Boolean = false,
        unionAmbiguityPolicy: Schema.UnionAmbiguity = Schema.UnionAmbiguity.Strict,
        variantDecoders: Chunk[Codec.Reader => Any] = Chunk.empty,
        denyUnknownFieldsEnabled: Boolean = false,
        fieldDefaults: Chunk[(String, Schema.FieldDefault)] = Chunk.empty,
        fieldTransforms: Chunk[(String, Schema.FieldTransform[A])] = Chunk.empty[(String, Schema.FieldTransform[A])],
        structure: => Structure.Type = Structure.Type.Open(Tag[Any])
    ): Schema[A] { type Focused = F } =
        // Erase the F-typed Function signatures to (A, Any) via asInstanceOf on the Function
        // value itself (no runtime effect, purely a Function-type cast). This keeps the JVM
        // bytecode parameter types erased to `Object` and avoids a `checkcast` on F
        // (F is often a structural `Record.~` with no runtime class).
        Schema.init[A](
            writeFn = writeFn,
            readFn = readFn,
            getterFn = getterFn.asInstanceOf[A => Maybe[Any]],
            setterFn = setterFn.asInstanceOf[(A, Any) => A],
            segments = segments,
            examples = examples,
            fieldDocs = fieldDocs,
            fieldDeprecated = fieldDeprecated,
            constraints = constraints,
            droppedFields = droppedFields,
            renamedFields = renamedFields,
            computedFields = computedFields,
            sourceFields = sourceFields,
            checks = checks,
            documentation = documentation,
            fieldIdOverrides = fieldIdOverrides,
            discriminatorField = discriminatorField,
            variantNaming = variantNaming,
            representation = representation,
            representationChain = representationChain,
            omitPolicies = omitPolicies,
            omitNoneAll = omitNoneAll,
            omitEmptyCollectionsAll = omitEmptyCollectionsAll,
            unionAmbiguityPolicy = unionAmbiguityPolicy,
            variantDecoders = variantDecoders,
            denyUnknownFieldsEnabled = denyUnknownFieldsEnabled,
            fieldDefaults = fieldDefaults,
            fieldTransforms = fieldTransforms,
            structure = structure
        ).asInstanceOf[Schema[A] { type Focused = F }]
    end initFocused

    // --- Factory methods ---

    /** Creates a Schema[A] where Focused is the structural expansion of A.
      *
      * For case classes, Focused is the intersection of field bindings: `"name" ~ String & "age" ~ Int`. For sealed traits, Focused is the
      * union of variant bindings: `"Circle" ~ Circle | "Rectangle" ~ Rectangle`. For primitives, Focused is the type itself.
      */
    transparent inline def apply[A]: Any =
        ${ internal.FocusMacro.metaApplyImpl[A] }

    /** Typeclass derivation entry point for Schema[A].
      *
      * Automatically generates a full Schema[A] for case classes and sealed traits/enums, including serialization, structural type, source
      * field descriptors, and default values. Summoned implicitly wherever `using Schema[A]` appears, or explicitly via `Schema.derived[A]`
      * to define a given in a companion object.
      *
      * @tparam A
      *   the type to derive a Schema for; must be a case class, sealed trait, enum, or primitive
      */
    inline given derived[A]: Schema[A] = ${ internal.SchemaDerivedMacro.derivedImpl[A] }

    // --- Public nested types ---

    /** Typed constraint for a field in a Schema.
      *
      * Constraints carry the field path (segments) so they can be matched to properties during JsonSchema enrichment. They also drive
      * runtime validation checks (baked into _checks at add time, except Format which is advisory only).
      */
    sealed abstract class Constraint derives CanEqual, Schema:
        def segments: Seq[String]

    object Constraint:
        final case class Min(segments: Seq[String], value: Double, exclusive: Boolean) extends Constraint
        final case class Max(segments: Seq[String], value: Double, exclusive: Boolean) extends Constraint
        final case class MinLength(segments: Seq[String], value: Int)                  extends Constraint
        final case class MaxLength(segments: Seq[String], value: Int)                  extends Constraint
        final case class Pattern(segments: Seq[String], regex: String)                 extends Constraint
        final case class Format(segments: Seq[String], format: String)                 extends Constraint
        final case class MinItems(segments: Seq[String], value: Int)                   extends Constraint
        final case class MaxItems(segments: Seq[String], value: Int)                   extends Constraint
        final case class UniqueItems(segments: Seq[String])                            extends Constraint

    end Constraint

    /** The wire representation a sum type (sealed trait / enum) uses for serialization.
      *
      * Each case selects one wire shape. `External` (the default) is the single-field wrapper object
      * `{"Circle":{...}}`. `Internal(tagKey)` is the flat discriminator `{"type":"Circle",...}`,
      * selected by `discriminator`. `Adjacent(tagKey, contentKey)` is the two-field object
      * `{"type":"Circle","content":{...}}`. `Tuple` is the nested positional array
      * `["Circle",{...}]`. `TupleFlat` is the flattened positional array `["Triangle",10,10,10]`,
      * coexisting with `Tuple`. `Untagged` is the bare payload `{"radius":10.0}`. The carrier is the
      * internal `representation` slot, set by the matching builder.
      */
    enum UnionRepresentation derives CanEqual:
        case External
        case Internal(tagKey: String)
        case Adjacent(tagKey: String, contentKey: String)
        case Tuple
        case TupleFlat
        case Untagged

        /** True for every representation other than the inert `External` default. ORed into the
          * transform flags so a configured sum takes the transform-aware engine path.
          */
        private[kyo] def nonDefault: Boolean = this match
            case External => false
            case _        => true
    end UnionRepresentation

    /** Governs how untagged union decode handles a wire value matched by more than one member schema.
      *
      * `Strict` (the default) raises `AmbiguousVariantMatchException` listing the matched members when
      * more than one member decoder succeeds on the same wire input. This makes overlapping wire shapes
      * (e.g. `Int | Long` on a JSON number) an explicit error rather than a silent arbitrary pick.
      *
      * `FirstMatch` returns the first-declared member that decodes successfully, matching how a
      * nominal untagged sum resolves overlapping members by declaration order. Choose this when a
      * known-ambiguous union should resolve by declaration order rather than fail.
      *
      * Configure via `schema.unionAmbiguity(policy)`. The policy is decode-only: it does not affect
      * encode.
      */
    enum UnionAmbiguity derives CanEqual:
        case Strict
        case FirstMatch
    end UnionAmbiguity

    /** The per-field omit trigger. Internal slot value; the user expresses it through the
      * `whenNone`, `whenEmpty`, `when`, and `whenDefault` builder method names, not by constructing
      * an `OmitPolicy`.
      */
    private[kyo] enum OmitPolicy derives CanEqual:
        case WhenNone
        case WhenEmpty
        case When(predicate: Structure.Value => Boolean)
        case WhenDefault
    end OmitPolicy

    /** Stored fallback for a field that may be absent during decode.
      *
      * `supplier` is evaluated when decode needs the missing field value. `writeDefault` writes that
      * value with the field's derived schema, giving later serializer steps the same codec path as
      * an ordinary field write.
      */
    final private[kyo] case class FieldDefault(
        supplier: () => Any,
        writeDefault: (Any, Codec.Writer) => Unit
    )

    /** Stored field-level codec override for a source field.
      *
      * A transform keeps the source getter, optional write override, optional read override, and the
      * derived write path for the same field. Builders merge by source field name so write-only and
      * read-only configuration can be applied in either order without duplicating the slot.
      *
      * The carrier is parameterized by the root type because the getter receives the complete root
      * value. Field value types are erased inside the carrier and restored by the macro-generated
      * functions at the call site.
      */
    final private[kyo] case class FieldTransform[A](
        get: A => Any,
        write: Maybe[(Any, Codec.Writer) => Unit],
        read: Maybe[Codec.Reader => Any],
        writeDerived: (Any, Codec.Writer) => Unit
    )

    private[kyo] def mergeFieldTransform[A](
        existing: Chunk[(String, Schema.FieldTransform[A])],
        fieldName: String,
        next: Schema.FieldTransform[A],
        replaceWrite: Boolean,
        replaceRead: Boolean
    ): Chunk[(String, Schema.FieldTransform[A])] =
        val merged =
            existing.filter(_._1 == fieldName).lastMaybe match
                case Maybe.Present((_, current)) =>
                    Schema.FieldTransform[A](
                        get = next.get,
                        write = if replaceWrite then next.write else current.write,
                        read = if replaceRead then next.read else current.read,
                        writeDerived = next.writeDerived
                    )
                case Maybe.Absent =>
                    next
        existing.filterNot(_._1 == fieldName) :+ (fieldName -> merged)
    end mergeFieldTransform

    /** Intermediate carrier returned by `Schema.omit(_.field)`. Holds the captured schema and the
      * compile-time-extracted source field name; the user finishes with `whenEmpty` or `whenNone`
      * to install the per-field omit policy. `Focused` is preserved (an omit affects wire presence,
      * not the structural field set).
      */
    final class OmitWhen[A, F] @publicInBinary private[kyo] (
        schema: Schema[A] { type Focused = F },
        fieldName: String,
        private[kyo] val materializedDefault: Maybe[Structure.Value] = Maybe.empty
    ):
        /** Omit the focused field on encode when its value is an empty collection/map, and
          * decode-default a missing field to the empty collection/map. Field-level shadows schema-wide.
          */
        def whenEmpty: Schema[A] { type Focused = F } =
            Schema.copyWith(schema)(
                omitPolicies = schema.omitPolicies.filterNot(_._1 == fieldName) :+ (fieldName -> Schema.OmitPolicy.WhenEmpty)
            ).asInstanceOf[Schema[A] { type Focused = F }]

        /** Omit the focused field on encode when its value is absent (None / Maybe.empty).
          * Field-level shadows schema-wide.
          */
        def whenNone: Schema[A] { type Focused = F } =
            Schema.copyWith(schema)(
                omitPolicies = schema.omitPolicies.filterNot(_._1 == fieldName) :+ (fieldName -> Schema.OmitPolicy.WhenNone)
            ).asInstanceOf[Schema[A] { type Focused = F }]

        /** Omit the focused field on encode when the predicate returns true for its materialized value. */
        def when(predicate: Structure.Value => Boolean): Schema[A] { type Focused = F } =
            Schema.copyWith(schema)(
                omitPolicies = schema.omitPolicies.filterNot(_._1 == fieldName) :+ (fieldName -> Schema.OmitPolicy.When(predicate))
            ).asInstanceOf[Schema[A] { type Focused = F }]

        /** Omit the focused field on encode when its materialized value equals the field's compile-time
          * default, materialized through the field's schema writer. Works for any field type including
          * products, collections, and sums. If the field has no compile-time default, never omits.
          */
        def whenDefault: Schema[A] { type Focused = F } =
            val updatedDefaults = materializedDefault match
                case Maybe.Present(value) =>
                    schema.fieldMaterializedDefaults.filterNot(_._1 == fieldName) :+ (fieldName -> value)
                case Maybe.Absent =>
                    schema.fieldMaterializedDefaults.filterNot(_._1 == fieldName)
            Schema.copyWith(schema)(
                omitPolicies = schema.omitPolicies.filterNot(_._1 == fieldName) :+ (fieldName -> Schema.OmitPolicy.WhenDefault),
                fieldMaterializedDefaults = updatedDefaults
            ).asInstanceOf[Schema[A] { type Focused = F }]
        end whenDefault
    end OmitWhen

    /** Case conventions for `renameAllVariants` / `renameAllFields`.
      *
      * A closed enumeration of the five serde-parity name styles. Tokenization is
      * acronym-aware: an uppercase run is one word, so `HTTPServer` under `SnakeCase`
      * becomes `http_server` and `DList` under `CamelCase` becomes `dList`. An acronym
      * whose original casing must survive a camel or Pascal target (a trailing `userID`)
      * is served by an explicit `variantNames` / `alias` override.
      */
    sealed abstract class NameCase derives CanEqual
    object NameCase:
        case object CamelCase          extends NameCase // firstName
        case object SnakeCase          extends NameCase // first_name
        case object KebabCase          extends NameCase // first-name
        case object PascalCase         extends NameCase // FirstName
        case object ScreamingSnakeCase extends NameCase // FIRST_NAME
    end NameCase

    /** Inspectable storage for the serde-parity naming configuration of a schema.
      *
      * Carries the four naming axes plus the two decode-alias maps as ordered `Chunk`
      * pairs (insertion order preserved for deterministic collision messages), mirroring
      * the existing `renamedFields: Chunk[(String, String)]` transform slot. A separate
      * slot from `renamedFields` so variant/field wire-casing never corrupts a field
      * `rename` chain. Empty by default, contributing nothing to the serialization hot
      * path.
      */
    private[kyo] case class VariantNaming(
        variantPairs: Chunk[(String, String)] = Chunk.empty,
        variantCase: Maybe[NameCase] = Maybe.empty,
        fieldCase: Maybe[NameCase] = Maybe.empty,
        variantAliases: Chunk[(String, String)] = Chunk.empty,
        fieldAliases: Chunk[(String, String)] = Chunk.empty
    ) derives CanEqual:
        def isEmpty: Boolean =
            variantPairs.isEmpty && variantCase.isEmpty && fieldCase.isEmpty &&
                variantAliases.isEmpty && fieldAliases.isEmpty
        def nonEmpty: Boolean = !isEmpty
    end VariantNaming

    /** Merges new explicit variant pairs over existing ones, last-write-wins per Scala
      * source name (a later `variantNames("X" -> ...)` re-targets X), preserving order.
      */
    private[kyo] def mergeVariantPairs(
        existing: Chunk[(String, String)],
        added: Chunk[(String, String)]
    ): Chunk[(String, String)] =
        val addedSources = added.map(_._1).toSet
        existing.filterNot((s, _) => addedSources.contains(s)) ++ added
    end mergeVariantPairs

    /** Raises `VariantNameCollisionException` if two distinct Scala variants target one
      * wire name. The sorted colliding source-name `Seq` rides the exception.
      */
    private[kyo] def checkVariantTargets(pairs: Chunk[(String, String)])(using Frame): Unit =
        val byWire = pairs.groupBy(_._2)
        byWire.foreach { (wire, group) =>
            val sources = group.map(_._1).distinct
            if sources.size > 1 then
                throw VariantNameCollisionException(wire, Chunk.from(sources.toSeq.sorted))
        }
    end checkVariantTargets

    /** Rejects a representation chain that contains a duplicate entry, at the builder call site. */
    private[kyo] def checkRepresentationChain(chain: Chunk[Schema.UnionRepresentation])(using Frame): Unit =
        if chain.size != chain.distinct.size then throw DuplicateRepresentationException(chain)
    end checkRepresentationChain

    /** True iff `rep` is expressible by a codec with the given capabilities. The three top-level
      * non-object shapes (Tuple/TupleFlat/Untagged) require `canWriteTopLevelNonObject`; the
      * object-shaped representations (External/Internal/Adjacent) are always expressible.
      */
    private[kyo] def representationExpressibleBy(
        rep: Schema.UnionRepresentation,
        capabilities: Codec.Capabilities
    ): Boolean =
        rep match
            case Schema.UnionRepresentation.Tuple | Schema.UnionRepresentation.TupleFlat |
                Schema.UnionRepresentation.Untagged =>
                capabilities.canWriteTopLevelNonObject
            case _ => true
    end representationExpressibleBy

    /** Raises `VariantNameCollisionException` if a variant alias duplicates an effective
      * primary wire name (from explicit pairs, convention, or raw Scala name) or another alias.
      * `effectivePrimaries` is the full set of effective wire names as computed by
      * `effectiveVariantWires`, so convention-derived primaries are included.
      */
    private[kyo] def checkVariantAliases(
        effectivePrimaries: Set[String],
        aliases: Chunk[(String, String)]
    )(using Frame): Unit =
        val byAlias = aliases.groupBy(_._1)
        byAlias.foreach { (alias, group) =>
            val targets = group.map(_._2).distinct
            if effectivePrimaries.contains(alias) || targets.size > 1 then
                throw VariantNameCollisionException(alias, Chunk.from((alias +: targets).distinct.sorted))
        }
    end checkVariantAliases

    /** Raises `FieldNameCollisionException` if a field alias duplicates another field's
      * wire name or another alias target.
      */
    private[kyo] def checkFieldAliases(
        existing: Chunk[(String, String)],
        merged: Chunk[(String, String)],
        primaries: Set[String]
    )(using Frame): Unit =
        val byAlias = merged.groupBy(_._1)
        byAlias.foreach { (alias, group) =>
            val targets = group.map(_._2).distinct
            if primaries.contains(alias) || targets.size > 1 then
                throw FieldNameCollisionException(alias, Chunk.from((alias +: targets).distinct.sorted))
        }
    end checkFieldAliases

    /** Computes the effective wire name for every source field, accounting for the
      * explicit rename chain and the field-case convention. Used by `alias` to build
      * the primaries set for collision detection.
      */
    private[kyo] def effectiveFieldWireNames(
        sourceFields: Seq[Field[?, ?]],
        renamedFields: Chunk[(String, String)],
        naming: VariantNaming
    ): Set[String] =
        val renameMap    = renamedFields.toMap
        val conventionFn = naming.fieldCase.map(nc => internal.NameCaseConversion.convert(nc))
        @scala.annotation.tailrec
        def resolveRename(name: String): String =
            renameMap.get(name) match
                case Some(next) => resolveRename(next)
                case None       => name
        sourceFields.map { sf =>
            val renamed = resolveRename(sf.name)
            if renamed != sf.name then renamed
            else conventionFn.map(fn => fn(sf.name)).getOrElse(sf.name)
        }.toSet
    end effectiveFieldWireNames

    /** The Scala variant names derived from a sum type's structure, or empty for a
      * non-sum (a product schema has no variants to name).
      */
    private[kyo] def variantScalaNames(structure: Structure.Type): Chunk[String] =
        structure match
            case Structure.Type.Sum(_, _, _, variants, _) => variants.map(_.name)
            case _                                        => Chunk.empty

    /** The effective wire name of each variant: the explicit pair if present, else the
      * convention-derived name if a case is set, else the raw Scala name. Mirrors the
      * encode-side resolution so `variantAlias` validates against the same vocabulary.
      */
    private[kyo] def effectiveVariantWires(structure: Structure.Type, naming: VariantNaming): Chunk[String] =
        val explicit   = naming.variantPairs.toMap
        val convention = naming.variantCase.map(nc => internal.NameCaseConversion.convert(nc))
        variantScalaNames(structure).map { scalaName =>
            explicit.get(scalaName)
                .orElse(convention.fold(None: Option[String])(fn => Some(fn(scalaName))))
                .getOrElse(scalaName)
        }
    end effectiveVariantWires

    /** Validates that each referenced Scala variant name exists in the derived set,
      * raising the existing `UnknownVariantException` on the first unknown name. Mirrors
      * field `rename`'s from-must-exist guarantee for the String-keyed variant methods.
      */
    private[kyo] def checkVariantNamesExist(known: Chunk[String], referenced: Chunk[String])(using Frame): Unit =
        val knownSet = known.toSet
        referenced.foreach { name =>
            if !knownSet.contains(name) then
                throw UnknownVariantException(Seq.empty, name)
        }
    end checkVariantNamesExist

    /** Validates that a variant wire name resolves to a known variant's effective wire
      * name, raising `UnknownVariantException` when it does not.
      */
    private[kyo] def checkVariantWireExists(effectiveWires: Chunk[String], wireName: String)(using Frame): Unit =
        if !effectiveWires.contains(wireName) then
            throw UnknownVariantException(Seq.empty, wireName)
    end checkVariantWireExists

    // --- Primitive Schema givens ---

    /** Schema for String values. */
    given stringSchema: Schema[String] = Schema.init[String](
        writeFn = (v, w) => w.string(v),
        readFn = _.string(),
        structure = Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[String].asInstanceOf[Tag[Any]])
    )

    /** Schema for Boolean values. */
    given booleanSchema: Schema[Boolean] = Schema.init[Boolean](
        writeFn = (v, w) => w.boolean(v),
        readFn = _.boolean(),
        structure = Structure.Type.Primitive(Structure.PrimitiveKind.Boolean, Tag[Boolean].asInstanceOf[Tag[Any]])
    )

    /** Schema for Int values. */
    given intSchema: Schema[Int] = Schema.init[Int](
        writeFn = (v, w) => w.int(v),
        readFn = _.int(),
        structure = Structure.Type.Primitive(Structure.PrimitiveKind.Int, Tag[Int].asInstanceOf[Tag[Any]])
    )

    /** Schema for Long values. */
    given longSchema: Schema[Long] = Schema.init[Long](
        writeFn = (v, w) => w.long(v),
        readFn = _.long(),
        structure = Structure.Type.Primitive(Structure.PrimitiveKind.Long, Tag[Long].asInstanceOf[Tag[Any]])
    )

    /** Schema for Float values. */
    given floatSchema: Schema[Float] = Schema.init[Float](
        writeFn = (v, w) => w.float(v),
        readFn = _.float(),
        structure = Structure.Type.Primitive(Structure.PrimitiveKind.Float, Tag[Float].asInstanceOf[Tag[Any]])
    )

    /** Schema for Double values. */
    given doubleSchema: Schema[Double] = Schema.init[Double](
        writeFn = (v, w) => w.double(v),
        readFn = _.double(),
        structure = Structure.Type.Primitive(Structure.PrimitiveKind.Double, Tag[Double].asInstanceOf[Tag[Any]])
    )

    /** Schema for Short values. */
    given shortSchema: Schema[Short] = Schema.init[Short](
        writeFn = (v, w) => w.short(v),
        readFn = _.short(),
        structure = Structure.Type.Primitive(Structure.PrimitiveKind.Short, Tag[Short].asInstanceOf[Tag[Any]])
    )

    /** Schema for Byte values. */
    given byteSchema: Schema[Byte] = Schema.init[Byte](
        writeFn = (v, w) => w.byte(v),
        readFn = _.byte(),
        structure = Structure.Type.Primitive(Structure.PrimitiveKind.Byte, Tag[Byte].asInstanceOf[Tag[Any]])
    )

    /** Schema for Char values. */
    given charSchema: Schema[Char] = Schema.init[Char](
        writeFn = (v, w) => w.char(v),
        readFn = _.char(),
        structure = Structure.Type.Primitive(Structure.PrimitiveKind.Char, Tag[Char].asInstanceOf[Tag[Any]])
    )

    /** Schema for BigDecimal values. */
    given bigDecimalSchema: Schema[BigDecimal] =
        Schema.init[BigDecimal](
            writeFn = (v, w) => w.bigDecimal(v),
            readFn = _.bigDecimal(),
            structure = Structure.Type.Primitive(Structure.PrimitiveKind.BigDecimal, Tag[BigDecimal].asInstanceOf[Tag[Any]])
        )

    /** Schema for BigInt values. */
    given bigIntSchema: Schema[BigInt] = Schema.init[BigInt](
        writeFn = (v, w) => w.bigInt(v),
        readFn = _.bigInt(),
        structure = Structure.Type.Primitive(Structure.PrimitiveKind.BigInt, Tag[BigInt].asInstanceOf[Tag[Any]])
    )

    /** Schema for java.time.Instant values. */
    given instantSchema: Schema[java.time.Instant] =
        Schema.init[java.time.Instant](
            writeFn = (v, w) => w.instant(v),
            readFn = _.instant(),
            structure = Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[java.time.Instant].asInstanceOf[Tag[Any]])
        )

    /** Schema for java.time.Duration values. */
    given durationSchema: Schema[java.time.Duration] =
        Schema.init[java.time.Duration](
            writeFn = (v, w) => w.duration(v),
            readFn = _.duration(),
            structure = Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[java.time.Duration].asInstanceOf[Tag[Any]])
        )

    /** Schema for kyo.Instant values. */
    given kyoInstantSchema: Schema[kyo.Instant] =
        instantSchema.transform[kyo.Instant](kyo.Instant.fromJava)(_.toJava)

    /** Schema for kyo.Duration values. */
    given kyoDurationSchema: Schema[kyo.Duration] =
        longSchema.transform[kyo.Duration](kyo.Duration.fromNanos)(_.toNanos)

    /** Schema for scala.concurrent.duration.FiniteDuration values.
      *
      * Encodes via the same wire primitive as `java.time.Duration` (so format codecs apply their own duration shape), round-tripping by
      * total nanoseconds. The decoded value is normalized to the coarsest exact unit, matching how other libraries (e.g. upickle) decode.
      */
    given finiteDurationSchema: Schema[scala.concurrent.duration.FiniteDuration] =
        durationSchema.transform[scala.concurrent.duration.FiniteDuration](jd => scala.concurrent.duration.Duration.fromNanos(jd.toNanos))(
            fd => java.time.Duration.ofNanos(fd.toNanos)
        )

    /** Schema for scala.concurrent.duration.Duration values (the possibly-infinite abstract type).
      *
      * Always encoded as a string: the total nanoseconds for a finite value, or `"inf"`/`"-inf"`/`"undef"` for the infinite and undefined
      * cases. This is the upickle/weePickle wire form, the only representation that can carry the infinite cases, so it is used regardless of
      * any format-specific duration setting.
      */
    given scalaDurationSchema: Schema[scala.concurrent.duration.Duration] =
        Schema.init[scala.concurrent.duration.Duration](
            writeFn = (v, w) =>
                w.string(
                    if v eq scala.concurrent.duration.Duration.Undefined then "undef"
                    else if v eq scala.concurrent.duration.Duration.Inf then "inf"
                    else if v eq scala.concurrent.duration.Duration.MinusInf then "-inf"
                    else v.toNanos.toString
                ),
            readFn = r =>
                r.string() match
                    case "inf"   => scala.concurrent.duration.Duration.Inf
                    case "-inf"  => scala.concurrent.duration.Duration.MinusInf
                    case "undef" => scala.concurrent.duration.Duration.Undefined
                    case s =>
                        try scala.concurrent.duration.Duration.fromNanos(s.toLong)
                        catch case _: NumberFormatException => throw TypeMismatchException(Seq.empty, "Duration", s)(using r.frame)
            ,
            structure = Structure.Type.Primitive(
                Structure.PrimitiveKind.String,
                Tag[scala.concurrent.duration.Duration].asInstanceOf[Tag[Any]]
            )
        )

    /** Schema for kyo.Schedule values. Walks the sealed hierarchy via the generic macro derivation. */
    given scheduleSchema: Schema[kyo.Schedule] = Schema.derived

    /** Schema for Span[Byte] values. */
    given spanByteSchema: Schema[Span[Byte]] =
        Schema.init[Span[Byte]](
            writeFn = (v, w) => w.bytes(v),
            readFn = _.bytes(),
            structure = Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[Span[Byte]].asInstanceOf[Tag[Any]])
        )

    /** Frame schema: serializes as the raw encoded string. Frame is an opaque type backed by String at runtime.
      */
    given frameSchema: Schema[Frame] = Schema.init[Frame](
        writeFn = (v, w) => w.string(v.toString),
        readFn = reader => reader.string().asInstanceOf[Frame],
        structure = Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[Frame].asInstanceOf[Tag[Any]])
    )

    /** Tag schema: serializes as the string representation. Tags are opaque types backed by String at runtime for static tags.
      */
    given tagSchema[A]: Schema[Tag[A]] = Schema.init[Tag[A]](
        writeFn = (v, w) =>
            v match
                case s: String => w.string(s)
                case _         => w.string(v.show),
        readFn = reader => reader.string().asInstanceOf[Tag[A]],
        structure = Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[Tag[A]].asInstanceOf[Tag[Any]])
    )

    /** Schema for java.time.LocalDate values. Serializes as ISO-8601 string. */
    given localDateSchema: Schema[java.time.LocalDate] =
        Schema.init[java.time.LocalDate](
            writeFn = (v, w) => w.string(v.toString),
            readFn = r => java.time.LocalDate.parse(r.string()),
            structure = Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[java.time.LocalDate].asInstanceOf[Tag[Any]])
        )

    /** Schema for java.time.LocalTime values. Serializes as ISO-8601 string. */
    given localTimeSchema: Schema[java.time.LocalTime] =
        Schema.init[java.time.LocalTime](
            writeFn = (v, w) => w.string(v.toString),
            readFn = r => java.time.LocalTime.parse(r.string()),
            structure = Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[java.time.LocalTime].asInstanceOf[Tag[Any]])
        )

    /** Schema for java.time.LocalDateTime values. Serializes as ISO-8601 string. The structure tag is
      * `Tag[Any]` (rather than `Tag[java.time.LocalDateTime]`) due to a Scala 3 limitation with Java
      * class tags in inline structural positions; the wire shape is unaffected.
      */
    given localDateTimeSchema: Schema[java.time.LocalDateTime] =
        Schema.init[java.time.LocalDateTime](
            writeFn = (v, w) => w.string(v.toString),
            readFn = r => java.time.LocalDateTime.parse(r.string()),
            structure = Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[Any])
        )

    /** Schema for java.util.UUID values. Serializes as string. */
    given uuidSchema: Schema[java.util.UUID] =
        Schema.init[java.util.UUID](
            writeFn = (v, w) => w.string(v.toString),
            readFn = r => java.util.UUID.fromString(r.string()),
            structure = Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[java.util.UUID].asInstanceOf[Tag[Any]])
        )

    /** Schema for Unit values.
      *
      * Unit serializes as an empty JSON object `{}`, not as `null`. The reasoning: Scala's `Unit` carries no
      * information, which in JSON wire vocabulary is the "empty object" rather than the "literal null value".
      * Using `null` for the canonical write form would conflate Unit with absent-Maybe / None-Option (both of
      * which DO mean null on the wire) and would break JSON Schema describers like [[Json.JsonSchema]] that
      * need a `type: "object"` shape for downstream consumers (MCP tool `inputSchema`, OpenAPI request bodies,
      * JSON Schema validators).
      *
      * On read the schema is tolerant of both wire shapes: a literal `null` (the legacy form, still emitted by
      * many JSON producers for void/Unit endpoints) is accepted as Unit, and the canonical empty object `{}` is
      * accepted as well. Only the empty-object form is ever written.
      */
    given unitSchema: Schema[Unit] = Schema.init[Unit](
        writeFn = (_, w) =>
            w.objectStart("", 0)
            w.objectEnd()
        ,
        readFn = r =>
            if r.isNil() then ()
            else
                discard(r.objectStart())
                r.objectEnd()
                ()
        ,
        structure = Structure.Type.Primitive(Structure.PrimitiveKind.Unit, Tag[Unit].asInstanceOf[Tag[Any]])
    )

    // --- Collection Schema givens ---

    /** Schema for List[A] values. */
    given listSchema[A](using inner0: => Schema[A]): Schema[List[A]] =
        lazy val inner = inner0
        Schema.init[List[A]](
            writeFn = (value, writer) =>
                writer.arrayStart(value.size)
                value.foreach(inner.serializeWrite(_, writer))
                writer.arrayEnd()
            ,
            readFn = reader =>
                discard(reader.arrayStart())
                val builder = List.newBuilder[A]
                @tailrec
                def loop(count: Int): Unit =
                    if reader.hasNextElement() then
                        reader.checkCollectionSize(count)
                        builder += inner.serializeRead(reader)
                        loop(count + 1)
                loop(1)
                reader.arrayEnd()
                builder.result()
            ,
            // Non-inline givens have no implicit Tag[A] in scope; fall back to Tag[Any].
            structure = Structure.Type.Collection(
                "List",
                Tag[Any],
                inner.structure
            )
        )
    end listSchema

    /** Schema for Vector[A] values. */
    given vectorSchema[A](using inner0: => Schema[A]): Schema[Vector[A]] =
        lazy val inner = inner0
        Schema.init[Vector[A]](
            writeFn = (value, writer) =>
                writer.arrayStart(value.size)
                value.foreach(inner.serializeWrite(_, writer))
                writer.arrayEnd()
            ,
            readFn = reader =>
                discard(reader.arrayStart())
                val builder = Vector.newBuilder[A]
                @tailrec
                def loop(count: Int): Unit =
                    if reader.hasNextElement() then
                        reader.checkCollectionSize(count)
                        builder += inner.serializeRead(reader)
                        loop(count + 1)
                loop(1)
                reader.arrayEnd()
                builder.result()
            ,
            // Non-inline givens have no implicit Tag[A] in scope; fall back to Tag[Any].
            structure = Structure.Type.Collection(
                "Vector",
                Tag[Any],
                inner.structure
            )
        )
    end vectorSchema

    /** Schema for Set[A] values. */
    given setSchema[A](using inner0: => Schema[A]): Schema[Set[A]] =
        lazy val inner = inner0
        Schema.init[Set[A]](
            writeFn = (value, writer) =>
                writer.arrayStart(value.size)
                value.foreach(inner.serializeWrite(_, writer))
                writer.arrayEnd()
            ,
            readFn = reader =>
                discard(reader.arrayStart())
                val builder = Set.newBuilder[A]
                @tailrec
                def loop(count: Int): Unit =
                    if reader.hasNextElement() then
                        reader.checkCollectionSize(count)
                        builder += inner.serializeRead(reader)
                        loop(count + 1)
                loop(1)
                reader.arrayEnd()
                builder.result()
            ,
            // Non-inline givens have no implicit Tag[A] in scope; fall back to Tag[Any].
            structure = Structure.Type.Collection(
                "Set",
                Tag[Any],
                inner.structure
            )
        )
    end setSchema

    /** Schema for Chunk[A] values. */
    given chunkSchema[A](using inner0: => Schema[A]): Schema[Chunk[A]] =
        lazy val inner = inner0
        Schema.init[Chunk[A]](
            writeFn = (value, writer) =>
                writer.arrayStart(value.size)
                value.foreach(inner.serializeWrite(_, writer))
                writer.arrayEnd()
            ,
            readFn = reader =>
                discard(reader.arrayStart())
                val builder = Chunk.newBuilder[A]
                @tailrec
                def loop(count: Int): Unit =
                    if reader.hasNextElement() then
                        reader.checkCollectionSize(count)
                        builder += inner.serializeRead(reader)
                        loop(count + 1)
                loop(1)
                reader.arrayEnd()
                builder.result()
            ,
            // Non-inline givens have no implicit Tag[A] in scope; fall back to Tag[Any].
            structure = Structure.Type.Collection(
                "Chunk",
                Tag[Any],
                inner.structure
            )
        )
    end chunkSchema

    /** Schema for Seq[A] values. */
    given seqSchema[A](using inner0: => Schema[A]): Schema[Seq[A]] =
        lazy val inner = inner0
        Schema.init[Seq[A]](
            writeFn = (value, writer) =>
                writer.arrayStart(value.size)
                value.foreach(inner.serializeWrite(_, writer))
                writer.arrayEnd()
            ,
            readFn = reader =>
                discard(reader.arrayStart())
                val builder = List.newBuilder[A]
                @tailrec
                def loop(count: Int): Unit =
                    if reader.hasNextElement() then
                        reader.checkCollectionSize(count)
                        builder += inner.serializeRead(reader)
                        loop(count + 1)
                loop(1)
                reader.arrayEnd()
                builder.result()
            ,
            // Non-inline givens have no implicit Tag[A] in scope; fall back to Tag[Any].
            structure = Structure.Type.Collection(
                "Seq",
                Tag[Any],
                inner.structure
            )
        )
    end seqSchema

    /** Schema for Span[A] values. */
    given spanSchema[A](using inner0: => Schema[A], ct: scala.reflect.ClassTag[A]): Schema[Span[A]] =
        lazy val inner = inner0
        Schema.init[Span[A]](
            writeFn = (value, writer) =>
                writer.arrayStart(value.size)
                value.foreach(inner.serializeWrite(_, writer))
                writer.arrayEnd()
            ,
            readFn = reader =>
                discard(reader.arrayStart())
                val builder = Chunk.newBuilder[A]
                @tailrec
                def loop(count: Int): Unit =
                    if reader.hasNextElement() then
                        reader.checkCollectionSize(count)
                        builder += inner.serializeRead(reader)
                        loop(count + 1)
                loop(1)
                reader.arrayEnd()
                Span.from(builder.result())
            ,
            structure = Structure.Type.Collection(
                "Span",
                Tag[Span[A]].asInstanceOf[Tag[Any]],
                inner.structure
            )
        )
    end spanSchema

    /** Schema for Maybe[A] values.
      *
      * Encodes as null when Absent, delegates to inner schema when Present.
      */
    given maybeSchema[A](using inner0: => Schema[A]): Schema[Maybe[A]] =
        lazy val inner = inner0
        Schema.init[Maybe[A]](
            writeFn = (value, writer) =>
                value match
                    case Maybe.Present(v) => inner.serializeWrite(v, writer)
                    case _ =>
                        writer.nil()
            ,
            readFn = reader =>
                if reader.isNil() then Maybe.empty
                else Maybe(inner.serializeRead(reader)),
            // Non-inline givens have no implicit Tag[A] in scope; fall back to Tag[Any].
            structure = Structure.Type.Optional(
                "Maybe",
                Tag[Any],
                inner.structure
            )
        )
    end maybeSchema

    /** Schema for Option[A] values. */
    given optionSchema[A](using inner0: => Schema[A]): Schema[Option[A]] =
        lazy val inner = inner0
        Schema.init[Option[A]](
            writeFn = (value, writer) =>
                value match
                    case Some(v) => inner.serializeWrite(v, writer)
                    case None =>
                        writer.nil()
            ,
            readFn = reader =>
                if reader.isNil() then None
                else Some(inner.serializeRead(reader)),
            // Non-inline givens have no implicit Tag[A] in scope; fall back to Tag[Any].
            structure = Structure.Type.Optional(
                "Option",
                Tag[Any],
                inner.structure
            )
        )
    end optionSchema

    /** Schema for Map[String, V] values (object encoding). */
    given stringMapSchema[V](using valueSchema0: => Schema[V]): Schema[Map[String, V]] =
        lazy val valueSchema = valueSchema0
        Schema.init[Map[String, V]](
            writeFn = (value, writer) =>
                writer.mapStart(value.size)
                value.iterator.zipWithIndex.foreach { case ((k, v), idx) =>
                    writer.field(k, idx)
                    valueSchema.serializeWrite(v, writer)
                }
                writer.mapEnd()
            ,
            readFn = reader =>
                discard(reader.mapStart())
                val builder = Map.newBuilder[String, V]
                @tailrec
                def loop(count: Int): Unit =
                    if reader.hasNextEntry() then
                        reader.checkCollectionSize(count)
                        val k = reader.field()
                        val v = valueSchema.serializeRead(reader)
                        builder += (k -> v)
                        loop(count + 1)
                loop(1)
                reader.mapEnd()
                builder.result()
            ,
            // Non-inline givens have no implicit Tag[V] in scope; fall back to Tag[Any].
            structure = Structure.Type.Mapping(
                "Map",
                Tag[Any],
                Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[String].asInstanceOf[Tag[Any]]),
                valueSchema.structure
            )
        )
    end stringMapSchema

    // --- Tuple Schemas ---

    given tuple2Schema[A: Schema, B: Schema]: Schema[(A, B)]                                           = Schema.derived
    given tuple3Schema[A: Schema, B: Schema, C: Schema]: Schema[(A, B, C)]                             = Schema.derived
    given tuple4Schema[A: Schema, B: Schema, C: Schema, D: Schema]: Schema[(A, B, C, D)]               = Schema.derived
    given tuple5Schema[A: Schema, B: Schema, C: Schema, D: Schema, E: Schema]: Schema[(A, B, C, D, E)] = Schema.derived

    // --- Kyo-data type Schemas ---

    /** Schema for Result[E, A] values - serialized as discriminated union. */
    given resultSchema[E, A](using eSchema0: => Schema[E], aSchema0: => Schema[A]): Schema[Result[E, A]] =
        lazy val eSchema = eSchema0
        lazy val aSchema = aSchema0
        Schema.init[Result[E, A]](
            writeFn = (value, writer) =>
                value match
                    case Result.Success(a) =>
                        writer.objectStart("Result", 2)
                        writer.field("$type", 0)
                        writer.string("success")
                        writer.field("value", 1)
                        aSchema.serializeWrite(a.asInstanceOf[A], writer)
                        writer.objectEnd()
                    case Result.Failure(e) =>
                        writer.objectStart("Result", 2)
                        writer.field("$type", 0)
                        writer.string("failure")
                        writer.field("value", 1)
                        eSchema.serializeWrite(e.asInstanceOf[E], writer)
                        writer.objectEnd()
                    case Result.Panic(ex) =>
                        writer.objectStart("Result", 2)
                        writer.field("$type", 0)
                        writer.string("panic")
                        writer.field("value", 1)
                        Maybe(ex.getMessage) match
                            case Maybe.Present(msg) => writer.string(msg)
                            case _                  => writer.nil()
                        writer.objectEnd()
            ,
            readFn = reader =>
                discard(reader.objectStart())
                @tailrec
                def loop(typeName: Maybe[String], captured: Maybe[Reader]): (Maybe[String], Maybe[Reader]) =
                    if reader.hasNextField() then
                        reader.field() match
                            case "$type" => loop(Maybe(reader.string()), captured)
                            case "value" => loop(typeName, Maybe(reader.captureValue()))
                            case _       => reader.skip(); loop(typeName, captured)
                    else (typeName, captured)
                val (typeName, captured) = loop(Maybe.empty, Maybe.empty)
                reader.objectEnd()
                if typeName.isEmpty then
                    throw MissingFieldException(Seq.empty, "$type")(using reader.frame)
                if captured.isEmpty then
                    throw MissingFieldException(Seq.empty, "value")(using reader.frame)
                val capturedReader = captured.get
                typeName.get match
                    case "success" => Result.succeed(aSchema.serializeRead(capturedReader))
                    case "failure" => Result.fail(eSchema.serializeRead(capturedReader))
                    case "panic" =>
                        val msg: Maybe[String] =
                            if capturedReader.isNil() then Maybe.empty
                            else Maybe(capturedReader.string())
                        Result.panic(new RuntimeException(msg.getOrElse(null: String))) // RuntimeException accepts null message
                    case other => throw UnknownVariantException(Seq.empty, other)(using reader.frame)
                end match
            ,
            // Non-inline givens have no implicit Tag[E] + Tag[A] in scope; fall back to Tag[Any].
            structure = Structure.Type.Sum(
                "Result",
                Tag[Any],
                typeParams = Chunk(eSchema.structure, aSchema.structure),
                variants = Chunk(
                    Structure.Variant("success", aSchema.structure),
                    Structure.Variant("failure", eSchema.structure),
                    Structure.Variant(
                        "panic",
                        Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[String].asInstanceOf[Tag[Any]])
                    )
                ),
                enumValues = Chunk.empty
            )
        )
    end resultSchema

    /** Schema for Either[A, B] values - serialized as discriminated union with Left/Right variants. */
    given eitherSchema[A, B](using aSchema0: => Schema[A], bSchema0: => Schema[B]): Schema[Either[A, B]] =
        lazy val aSchema = aSchema0
        lazy val bSchema = bSchema0
        Schema.init[Either[A, B]](
            writeFn = (value, writer) =>
                value match
                    case Left(a) =>
                        writer.objectStart("Either", 2)
                        writer.field("$type", 0)
                        writer.string("Left")
                        writer.field("value", 1)
                        aSchema.serializeWrite(a.asInstanceOf[A], writer)
                        writer.objectEnd()
                    case Right(b) =>
                        writer.objectStart("Either", 2)
                        writer.field("$type", 0)
                        writer.string("Right")
                        writer.field("value", 1)
                        bSchema.serializeWrite(b.asInstanceOf[B], writer)
                        writer.objectEnd()
            ,
            readFn = reader =>
                discard(reader.objectStart())
                @tailrec
                def loop(typeName: Maybe[String], captured: Maybe[Reader]): (Maybe[String], Maybe[Reader]) =
                    if reader.hasNextField() then
                        reader.field() match
                            case "$type" => loop(Maybe(reader.string()), captured)
                            case "value" => loop(typeName, Maybe(reader.captureValue()))
                            case _       => reader.skip(); loop(typeName, captured)
                    else (typeName, captured)
                val (typeName, captured) = loop(Maybe.empty, Maybe.empty)
                reader.objectEnd()
                if typeName.isEmpty then
                    throw MissingFieldException(Seq.empty, "$type")(using reader.frame)
                if captured.isEmpty then
                    throw MissingFieldException(Seq.empty, "value")(using reader.frame)
                val capturedReader = captured.get
                typeName.get match
                    case "Left"  => Left(aSchema.serializeRead(capturedReader))
                    case "Right" => Right(bSchema.serializeRead(capturedReader))
                    case other   => throw UnknownVariantException(Seq.empty, other)(using reader.frame)
                end match
            ,
            // Non-inline givens have no implicit Tag[A] + Tag[B] in scope; fall back to Tag[Any].
            structure = Structure.Type.Sum(
                "Either",
                Tag[Any],
                typeParams = Chunk(aSchema.structure, bSchema.structure),
                variants = Chunk(
                    Structure.Variant("Left", aSchema.structure),
                    Structure.Variant("Right", bSchema.structure)
                ),
                enumValues = Chunk.empty
            )
        )
    end eitherSchema

    /** Schema for Dict[String, V] - serializes as a JSON object. */
    given stringDictSchema[V](using vSchema0: => Schema[V]): Schema[Dict[String, V]] =
        lazy val vSchema = vSchema0
        Schema.init[Dict[String, V]](
            writeFn = (value, writer) =>
                writer.mapStart(value.size)
                discard(value.foldLeft(0) { (idx, k, v) =>
                    writer.field(k, idx)
                    vSchema.serializeWrite(v, writer)
                    idx + 1
                })
                writer.mapEnd()
            ,
            readFn = reader =>
                discard(reader.mapStart())
                @tailrec
                def loop(dict: Dict[String, V], count: Int): Dict[String, V] =
                    if reader.hasNextEntry() then
                        reader.checkCollectionSize(count)
                        val k = reader.field()
                        val v = vSchema.serializeRead(reader)
                        loop(dict.update(k, v), count + 1)
                    else dict
                val dict = loop(Dict.empty[String, V], 1)
                reader.mapEnd()
                dict
            ,
            // Non-inline givens have no implicit Tag[V] in scope; fall back to Tag[Any].
            structure = Structure.Type.Mapping(
                "Dict",
                Tag[Any],
                Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[String].asInstanceOf[Tag[Any]]),
                vSchema.structure
            )
        )
    end stringDictSchema

    /** Schema for Dict[K, V] with non-String keys - serializes as array of [k, v] pairs. */
    given dictSchema[K, V](using kSchema0: => Schema[K], vSchema0: => Schema[V]): Schema[Dict[K, V]] =
        lazy val kSchema = kSchema0
        lazy val vSchema = vSchema0
        Schema.init[Dict[K, V]](
            writeFn = (value, writer) =>
                writer.arrayStart(value.size)
                value.foreach { (k, v) =>
                    writer.arrayStart(2)
                    kSchema.serializeWrite(k, writer)
                    vSchema.serializeWrite(v, writer)
                    writer.arrayEnd()
                }
                writer.arrayEnd()
            ,
            readFn = reader =>
                discard(reader.arrayStart())
                @tailrec
                def loop(dict: Dict[K, V], count: Int): Dict[K, V] =
                    if reader.hasNextElement() then
                        reader.checkCollectionSize(count)
                        discard(reader.arrayStart())
                        val k = kSchema.serializeRead(reader)
                        val v = vSchema.serializeRead(reader)
                        reader.arrayEnd()
                        loop(dict.update(k, v), count + 1)
                    else dict
                val dict = loop(Dict.empty[K, V], 1)
                reader.arrayEnd()
                dict
            ,
            // Non-inline givens have no implicit Tag[K] + Tag[V] in scope; fall back to Tag[Any].
            structure = Structure.Type.Mapping(
                "Dict",
                Tag[Any],
                kSchema.structure,
                vSchema.structure
            )
        )
    end dictSchema

    // --- Internal helpers ---

    /** Derives an Ordering[A] for a case class by comparing fields in declaration order.
      *
      * Recursively derives Ordering for nested case class fields via summonOrdering.
      */
    private[kyo] inline def deriveOrdering[A](using mir: Mirror.ProductOf[A]): Ordering[A] =
        internal.SchemaOrdering.deriveOrdering[A]

    /** Converts an arbitrary Scala value to Structure.Value for transform-aware serialization. */
    private[kyo] def anyToStructureValue(value: Any): Structure.Value =
        internal.SchemaSerializer.anyToStructureValue(value)

    /** Writes a Structure.Value tree to a Writer. Reverse of StructureValueWriter. */
    private[kyo] def writeStructureValue(writer: Writer, value: Structure.Value): Unit =
        internal.SchemaSerializer.writeStructureValue(writer, value)

    /** Returns a zero/default value for a dropped field so required-field null checks pass during decode. */
    private[kyo] def zeroForField(field: Field[?, ?]): AnyRef =
        internal.SchemaSerializer.zeroForField(field)

    /** Internal factory for field-level check accumulation. Called from inline check method. */
    private[kyo] def fieldCheck[A, V](
        meta: Schema[A],
        getter: A => Maybe[V],
        segs: Seq[String],
        pred: V => Boolean,
        msg: String
    )(using frame: Frame): Schema[A] { type Focused = meta.Focused } =
        internal.SchemaValidation.fieldCheck(meta, getter, segs, pred, msg)

    /** Internal factory for constraint-based check accumulation. Like fieldCheck but also stores the Constraint for JsonSchema enrichment.
      */
    private[kyo] def fieldCheckWithConstraint[A, V](
        meta: Schema[A],
        getter: A => Maybe[V],
        segs: Seq[String],
        pred: V => Boolean,
        msg: String,
        constraint: Constraint
    )(using frame: Frame): Schema[A] { type Focused = meta.Focused } =
        internal.SchemaValidation.fieldCheckWithConstraint(meta, getter, segs, pred, msg, constraint)

    /** Internal factory for advisory-only constraints (no runtime predicate). Used by format. */
    private[kyo] def fieldConstraintOnly[A, V](
        meta: Schema[A],
        constraint: Constraint
    ): Schema[A] { type Focused = meta.Focused } =
        internal.SchemaValidation.fieldConstraintOnly(meta, constraint)

    /** Internal helper for field-level doc accumulation. Called from inline doc method. */
    private[kyo] def withFieldDoc[A](
        meta: Schema[A],
        fieldPath: Seq[String],
        description: String
    ): Schema[A] { type Focused = meta.Focused } =
        internal.SchemaValidation.withFieldDoc(meta, fieldPath, description)

    /** Internal helper for field-level deprecated accumulation. Called from inline deprecated method. */
    private[kyo] def withFieldDeprecated[A](
        meta: Schema[A],
        fieldPath: Seq[String],
        reason: String
    ): Schema[A] { type Focused = meta.Focused } =
        internal.SchemaValidation.withFieldDeprecated(meta, fieldPath, reason)

    /** Message used by the sentinel codec lambdas in `create`/`createFrom`/`createWithFocused` for schemas that cannot directly
      * (de)serialize (e.g. focused / record schemas). Thrown as a `SchemaNotSerializableException` at the user's call site, with the Frame
      * propagated from the inline factory's caller.
      */
    private[kyo] val notSerializableMessage: String =
        "This schema does not have serialization. " +
            "Focused schemas and Record schemas cannot serialize directly."

    /** Internal factory for macro-generated Schema instances. Not part of public API.
      *
      * The F type parameter becomes the Focused type member on the returned Schema.
      */
    @nowarn("msg=anonymous")
    inline def create[A, F](
        inline getterFn: A => Maybe[F],
        inline setterFn: (A, F) => A,
        segments: Seq[String],
        sourceFields: Seq[Field[?, ?]] = Seq.empty,
        structure: => Structure.Type
    )(using frame: Frame): Schema[A] { type Focused = F } =
        Schema.initFocused[A, F](
            writeFn = (_: A, _: Writer) => throw SchemaNotSerializableException(Schema.notSerializableMessage)(using frame),
            readFn = (_: Reader) => throw SchemaNotSerializableException(Schema.notSerializableMessage)(using frame),
            getterFn = getterFn,
            setterFn = setterFn,
            segments = segments,
            sourceFields = sourceFields,
            structure = structure
        )

    /** Internal factory for macro-generated Schema instances with serialization. */
    @nowarn("msg=anonymous")
    inline def create[A, F](
        inline getterFn: A => Maybe[F],
        inline setterFn: (A, F) => A,
        segments: Seq[String],
        sourceFields: Seq[Field[?, ?]],
        inline writeFn: (A, Writer) => Unit,
        inline readFn: Reader => A,
        structure: => Structure.Type
    ): Schema[A] { type Focused = F } =
        Schema.initFocused[A, F](
            writeFn = writeFn,
            readFn = readFn,
            getterFn = getterFn,
            setterFn = setterFn,
            segments = segments,
            sourceFields = sourceFields,
            structure = structure
        )

    /** Internal factory for transform macros. Copies internal state from a source Schema. Not part of public API.
      *
      * Casts are needed because the transform changes Focused but the underlying getter/setter still operates on A's real runtime
      * structure. This is the ONE place where casts are justified -- the transform only changes what the type system sees, not the runtime
      * representation.
      */
    private[kyo] def createFrom[A, F2](
        source: Schema[A],
        checks: Seq[A => Seq[ValidationFailedException]],
        computedFields: Chunk[(String, A => Any)],
        renamedFields: Chunk[(String, String)],
        droppedFields: Set[String] = Set.empty,
        flattenedReadFields: Chunk[(String, String)] = Chunk.empty
    ): Schema[A] { type Focused = F2 } =
        internal.SchemaFactory.createFrom[A, F2](source, checks, computedFields, renamedFields, droppedFields, flattenedReadFields)

    /** Internal factory for creating Schema with a specific Focused type, preserving all state. Used by methods that return
      * `Schema[A] { type Focused = E }` without changing E.
      */
    @nowarn("msg=anonymous")
    inline def createWithFocused[A, E](
        inline getterFn: A => Maybe[Any],
        inline setterFn: (A, Any) => A,
        inline writeFn: (A, Writer) => Unit,
        inline readFn: Reader => A,
        segments: Seq[String],
        checks: Seq[A => Seq[ValidationFailedException]],
        computedFields: Chunk[(String, A => Any)],
        renamedFields: Chunk[(String, String)],
        sourceFields: Seq[Field[?, ?]],
        droppedFields: Set[String],
        doc: Maybe[String] = Maybe.empty,
        fieldDocs: Map[Seq[String], String] = Map.empty,
        examples: Chunk[A] = Chunk.empty,
        fieldDeprecated: Map[Seq[String], String] = Map.empty,
        constraints: Seq[Constraint] = Seq.empty,
        fieldIds: Map[Seq[String], Int] = Map.empty,
        discriminatorField: Maybe[String] = Maybe.empty,
        variantNaming: Schema.VariantNaming = Schema.VariantNaming(),
        representation: Schema.UnionRepresentation = Schema.UnionRepresentation.External,
        representationChain: Maybe[Chunk[Schema.UnionRepresentation]] = Maybe.empty,
        omitPolicies: Chunk[(String, Schema.OmitPolicy)] = Chunk.empty,
        omitNoneAll: Boolean = false,
        omitEmptyCollectionsAll: Boolean = false,
        unionAmbiguityPolicy: Schema.UnionAmbiguity = Schema.UnionAmbiguity.Strict,
        variantDecoders: Chunk[Codec.Reader => Any] = Chunk.empty,
        denyUnknownFieldsEnabled: Boolean = false,
        fieldDefaults: Chunk[(String, Schema.FieldDefault)] = Chunk.empty,
        fieldTransforms: Chunk[(String, Schema.FieldTransform[A])] = Chunk.empty[(String, Schema.FieldTransform[A])],
        fieldMaterializedDefaults: Chunk[(String, Structure.Value)] = Chunk.empty,
        flattenedReadFields0: Chunk[(String, String)] = Chunk.empty,
        structure: => Structure.Type
    ): Schema[A] { type Focused = E } =
        lazy val _structure = structure
        new Schema[A](
            segments,
            examples,
            fieldDocs,
            fieldDeprecated,
            constraints,
            droppedFields,
            renamedFields,
            computedFields,
            sourceFields,
            checks,
            doc,
            fieldIds,
            discriminatorField,
            variantNaming,
            representation,
            representationChain,
            omitPolicies,
            omitNoneAll,
            omitEmptyCollectionsAll,
            unionAmbiguityPolicy,
            variantDecoders,
            denyUnknownFieldsEnabled,
            fieldDefaults,
            fieldTransforms,
            fieldMaterializedDefaults
        ):
            type Focused = E
            @publicInBinary override private[kyo] val flattenedReadFields: Chunk[(String, String)] = flattenedReadFields0
            @publicInBinary def serializeWrite(value: A, writer: Writer): Unit =
                if hasTransforms then transformedWrite(value, writer)
                else writeFn(value, writer)
            @publicInBinary def serializeRead(reader: Reader): A =
                if hasReadTransforms then transformedRead(reader)
                else readFn(reader)
            @publicInBinary override def rawSerializeWrite(value: A, writer: Writer): Unit = writeFn(value, writer)
            @publicInBinary override def rawSerializeRead(reader: Reader): A               = readFn(reader)
            @publicInBinary def getter(value: A): Maybe[Any]                               = getterFn(value)
            @publicInBinary def setter(value: A, next: Any): A                             = setterFn(value, next)
            override def structure: Structure.Type                                         = _structure
        end new
    end createWithFocused

    /** Copies `self` into a new `Schema[A]` carrying its codec, getter/setter and structure, with the
      * given metadata fields overridden (each defaults to `self`'s current value). Centralizes the
      * identity-passthrough `createWithFocused` call shared by every metadata builder (`check`, `doc`,
      * `deprecated`, `discriminator`, `example`, and the drop/rename/check helpers). `inline` with the
      * four codec closures spelled out here, so a call inlines exactly as the open-coded form did: the
      * closures fold into `Schema.init`, with no extra `Function` allocation.
      */
    private[kyo] inline def copyWith[A](self: Schema[A])(
        segments: Seq[String] = self.segments,
        checks: Seq[A => Seq[ValidationFailedException]] = self.checks,
        computedFields: Chunk[(String, A => Any)] = self.computedFields,
        renamedFields: Chunk[(String, String)] = self.renamedFields,
        sourceFields: Seq[Field[?, ?]] = self.sourceFields,
        droppedFields: Set[String] = self.droppedFields,
        doc: Maybe[String] = self.documentation,
        fieldDocs: Map[Seq[String], String] = self.fieldDocs,
        examples: Chunk[A] = self.examples,
        fieldDeprecated: Map[Seq[String], String] = self.fieldDeprecated,
        constraints: Seq[Constraint] = self.constraints,
        fieldIds: Map[Seq[String], Int] = self.fieldIdOverrides,
        discriminatorField: Maybe[String] = self.discriminatorField,
        variantNaming: Schema.VariantNaming = self.variantNaming,
        representation: Schema.UnionRepresentation = self.representation,
        representationChain: Maybe[Chunk[Schema.UnionRepresentation]] = self.representationChain,
        omitPolicies: Chunk[(String, Schema.OmitPolicy)] = self.omitPolicies,
        omitNoneAll: Boolean = self.omitNoneAll,
        omitEmptyCollectionsAll: Boolean = self.omitEmptyCollectionsAll,
        unionAmbiguityPolicy: Schema.UnionAmbiguity = self.unionAmbiguityPolicy,
        variantDecoders: Chunk[Codec.Reader => Any] = self.variantDecoders,
        denyUnknownFieldsEnabled: Boolean = self.denyUnknownFieldsEnabled,
        fieldDefaults: Chunk[(String, Schema.FieldDefault)] = self.fieldDefaults,
        fieldTransforms: Chunk[(String, Schema.FieldTransform[A])] = self.fieldTransforms,
        fieldMaterializedDefaults: Chunk[(String, Structure.Value)] = self.fieldMaterializedDefaults,
        flattenedReadFields: Chunk[(String, String)] = self.flattenedReadFields,
        structure: => Structure.Type = self.structure
    ): Schema[A] { type Focused = self.Focused } =
        createWithFocused[A, self.Focused](
            getterFn = (a: A) => self.getter(a),
            setterFn = (a: A, v: Any) => self.setter(a, v),
            writeFn = (a: A, w: Writer) => self.rawSerializeWrite(a, w),
            readFn = (r: Reader) => self.rawSerializeRead(r),
            segments = segments,
            checks = checks,
            computedFields = computedFields,
            renamedFields = renamedFields,
            sourceFields = sourceFields,
            droppedFields = droppedFields,
            doc = doc,
            fieldDocs = fieldDocs,
            examples = examples,
            fieldDeprecated = fieldDeprecated,
            constraints = constraints,
            fieldIds = fieldIds,
            discriminatorField = discriminatorField,
            variantNaming = variantNaming,
            representation = representation,
            representationChain = representationChain,
            omitPolicies = omitPolicies,
            omitNoneAll = omitNoneAll,
            omitEmptyCollectionsAll = omitEmptyCollectionsAll,
            unionAmbiguityPolicy = unionAmbiguityPolicy,
            variantDecoders = variantDecoders,
            denyUnknownFieldsEnabled = denyUnknownFieldsEnabled,
            fieldDefaults = fieldDefaults,
            fieldTransforms = fieldTransforms,
            fieldMaterializedDefaults = fieldMaterializedDefaults,
            flattenedReadFields0 = flattenedReadFields,
            structure = structure
        )

end Schema
