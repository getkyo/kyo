package kyo.schema

import kyo.Chunk

/** The always-captured marker base for schema-driving annotations.
  *
  * Any annotation that subtypes `SchemaAnnotation` is reified onto the structural model
  * unconditionally, regardless of the active `AnnotationPolicy`. A codec author reads captured
  * instances off `schema.structure` by type, for example
  * `field.annotations.collectFirst { case r: rename => r.wireName }`.
  *
  * Subtype this trait to opt a custom annotation into unconditional capture. The built-in
  * annotations additionally configure the derived `Schema` automatically; a custom subtype is
  * captured but requires the codec author to drive behavior from the captured instance directly.
  *
  * Adding a `SchemaAnnotation` subtype annotation to a type that carries no annotations
  * produces a byte-identical schema, so annotation-based configuration is opt-in.
  *
  * @see AnnotationPolicy for filtering non-marker (third-party) annotations
  */
trait SchemaAnnotation extends scala.annotation.StaticAnnotation

/** Policy controlling which NON-marker (third-party) annotations the derivation macro reifies onto
  * the structural model. A `SchemaAnnotation` subtype is always captured and never consults this
  * policy.
  *
  * A non-marker annotation's fully-qualified name is captured iff it matches some `include` glob AND
  * no `exclude` glob. The default admits every FQN (`include = Chunk("*")`) except the fixed noise
  * set (`defaultExclusions`). Place a custom `inline given AnnotationPolicy` in derivation scope to
  * widen or narrow capture; the macro summons it at expansion time, so the given must be `inline`
  * for its value to be readable, e.g. `inline given AnnotationPolicy = AnnotationPolicy.markersOnly`.
  *
  * Globs match on the annotation's FQN with `*` as a path-segment wildcard, so
  * `jakarta.validation.*` admits every annotation in that package. The convenience alias
  * `markersOnly` captures only `SchemaAnnotation` subtypes.
  *
  * @param include the FQN globs a non-marker annotation must match at least one of
  * @param exclude the FQN globs a non-marker annotation must match none of
  * @see SchemaAnnotation for the always-captured marker base
  */
final case class AnnotationPolicy(
    include: Chunk[String] = Chunk("*"),
    exclude: Chunk[String] = AnnotationPolicy.defaultExclusions
) derives CanEqual

object AnnotationPolicy:

    /** The fixed noise FQN-glob list excluded by default: compiler and platform annotations that
      * carry no schema meaning.
      */
    val defaultExclusions: Chunk[String] = Chunk(
        "scala.annotation.internal.*",
        "scala.annotation.nowarn",
        "scala.annotation.tailrec",
        "scala.annotation.targetName",
        "scala.annotation.unchecked.*",
        "scala.unchecked",
        "java.lang.Override",
        "scala.annotation.publicInBinary"
    )

    /** Captures only `SchemaAnnotation` markers: an empty `include` admits no non-marker FQN, while
      * marker subtypes are still captured unconditionally. A named shorthand, not a macro fast-path.
      */
    val markersOnly: AnnotationPolicy = AnnotationPolicy(include = Chunk.empty)

    /** The default policy the macro summons when no custom `given` is in derivation scope. */
    given default: AnnotationPolicy = AnnotationPolicy()
end AnnotationPolicy

/** Renames a field or variant to a custom wire key.
  *
  * On a case-class field, sets the JSON/Protobuf/MsgPack key used on encode and the primary
  * key accepted on decode. On a sealed-trait variant, sets the discriminator tag value. The
  * rename applies before alias registration: a field carrying both `@rename` and `@alias` uses
  * the renamed wire key as the primary name and registers the aliases against it.
  *
  * Decoded input carrying the original Scala name will fail with `MissingFieldException` once
  * a rename is active; add an `@alias` for the Scala name if backward compatibility is needed.
  *
  * @param wireName the wire key to use on encode and the primary key accepted on decode
  */
final class rename(val wireName: String) extends SchemaAnnotation

/** Provides additional accepted wire names for a field or variant on decode.
  *
  * On a case-class field, registers aliases against the field's effective wire name (the
  * `@rename`-resolved name, or the Scala field name if no `@rename` is present). On a
  * sealed-trait variant, registers variant-level decode aliases. Aliases are accepted on
  * decode only; encode always emits the primary wire name.
  *
  * A collision between a field alias and another field's primary wire name raises
  * `FieldNameCollisionException` at schema construction. When two sealed-trait variants
  * register the same alias, `VariantNameCollisionException` is raised at schema construction.
  *
  * @param names one or more additional wire names accepted on decode
  */
final class alias(val names: String*) extends SchemaAnnotation

/** Selects internally-tagged (discriminated) sum representation.
  *
  * Places a discriminator field with the given key inside each variant's JSON object.
  * The value of the discriminator field is the variant's wire name. On decode, the
  * discriminator field is read first to select the variant. An unknown discriminator
  * value raises `UnknownVariantException`.
  *
  * Placement: sealed traits only. Placing this annotation on a case class is a compile
  * error ("sum-representation annotation").
  *
  * @param tagKey the key used for the discriminator field inside each variant object
  */
final class discriminator(val tagKey: String) extends SchemaAnnotation

/** Selects adjacently-tagged sum representation.
  *
  * Encodes each variant as a two-field object: `tagKey` carries the variant wire name
  * and `contentKey` carries the variant payload. Decode reads `tagKey` first to identify
  * the variant, then reconstructs from `contentKey`. Supports non-object payloads.
  *
  * Placement: sealed traits only. Placing this annotation on a case class is a compile
  * error ("sum-representation annotation").
  *
  * @param tagKey     the key for the variant discriminator field
  * @param contentKey the key for the variant payload field
  */
final class adjacent(val tagKey: String, val contentKey: String) extends SchemaAnnotation

/** Selects untagged sum representation.
  *
  * Encodes each variant as its bare payload with no discriminator wrapper. Decode
  * attempts each variant's decoder in declaration order and returns the first that
  * succeeds; if no variant matches, `NoVariantMatchException` is raised. Requires a
  * self-describing codec (JSON, YAML, Ion, MsgPack); fails on Protobuf.
  *
  * Placement: sealed traits only. Placing this annotation on a case class is a compile
  * error ("sum-representation annotation").
  */
final class untagged() extends SchemaAnnotation

/** Drops a field from the wire on encode, reconstructing it from its Scala default on decode.
  *
  * The annotated field must have a Scala default value (`= <expr>`): the derived decoder
  * reconstructs the field from that default when the field is absent from the wire. If the
  * field has no Scala default, decode will fail with `MissingFieldException`. Distinct from
  * `@omit` which is decode-symmetric (the field is omitted conditionally and may or may not
  * be present on the wire).
  *
  * Placement: case-class fields only.
  */
final class transient() extends SchemaAnnotation

/** Attaches documentation metadata to a field or type.
  *
  * On a case-class field, the text is stored in the schema's `fieldDocs` map keyed by the
  * field's effective wire name. On a type, the text is stored in `documentation`. The
  * metadata is available at runtime via the schema object and is not emitted to the wire.
  *
  * @param text the documentation string
  */
final class doc(val text: String) extends SchemaAnnotation

/** Omits a field from the wire when a condition is met.
  *
  * The omission mode is controlled by the `when` argument:
  *   - `omit.WhenAbsent` (default, no-arg `@omit`): type-aware; omits when the field is
  *     a `Maybe[_]` or `Option[_]` that is absent, or when the field is a collection that
  *     is empty. On a plain scalar field, the annotation is captured (and `reason` is
  *     preserved) but no omission policy is applied.
  *   - `omit.WhenNone`: omit when the field value is `Maybe.empty` / `None`.
  *   - `omit.WhenEmpty`: omit when the field value is an empty collection or map.
  *   - `omit.WhenDefault`: omit when the field value equals the Scala default. The field
  *     must have a Scala default (`= <expr>`); otherwise the policy never triggers.
  *
  * On decode, the field is reconstructed from its Scala default (or `Maybe.empty` /
  * `None` for optional fields) when absent from the wire.
  *
  * Placement: case-class fields only.
  *
  * @param when   the omission mode; defaults to `omit.WhenAbsent` (type-aware)
  * @param reason optional documentation string describing why the field is omitted;
  *               has no effect on serialization behavior
  */
final class omit(val when: omit.Mode = omit.WhenAbsent, val reason: String = "") extends SchemaAnnotation

object omit:
    /** Controls when a field annotated with `@omit` is omitted from the wire.
      *
      * Select one of the named cases and pass it as the `when` argument to `@omit`:
      *   - `WhenAbsent` (default): type-aware; omits optional and collection fields when absent/empty.
      *   - `WhenNone`: omit when the field value is `Maybe.empty` or `None`.
      *   - `WhenEmpty`: omit when the field value is an empty collection or map.
      *   - `WhenDefault`: omit when the field value equals the field's Scala default.
      *   - `When(predicate)`: omit when the supplied `OmitPredicate.test` returns true.
      */
    enum Mode derives CanEqual:
        /** Type-aware omit: inferred from the field type at derivation time. */
        case WhenAbsent

        /** Omit when the field value is `Maybe.empty` or `None`. */
        case WhenNone

        /** Omit when the field value is an empty collection or map. */
        case WhenEmpty

        /** Omit when the field value equals the field's Scala default. */
        case WhenDefault

        /** Omit when the supplied predicate returns true for the field's materialized Structure.Value.
          *
          * The predicate object must extend `OmitPredicate`. Pass the object to `omit.When` and
          * annotate the field with `@omit(omit.When(myPredicate))`. The predicate's `test` method
          * is called at runtime for each encode to decide whether the field is omitted.
          */
        case When(predicate: OmitPredicate)
    end Mode

    /** Type-aware omit: inferred from the field type at derivation time (see `Mode.WhenAbsent`). */
    val WhenAbsent: Mode = Mode.WhenAbsent

    /** Omit when the field value is `Maybe.empty` or `None` (see `Mode.WhenNone`). */
    val WhenNone: Mode = Mode.WhenNone

    /** Omit when the field value is an empty collection or map (see `Mode.WhenEmpty`). */
    val WhenEmpty: Mode = Mode.WhenEmpty

    /** Omit when the field value equals the field's Scala default (see `Mode.WhenDefault`). */
    val WhenDefault: Mode = Mode.WhenDefault

    /** Omit when the supplied predicate returns true for the field's materialized value. */
    def When(predicate: OmitPredicate): Mode.When = Mode.When(predicate)
end omit

/** Applies a custom codec transform to a case-class field.
  *
  * The transformer object must extend one of the `Transformer` family subtypes: `Transformer.Full`
  * for custom read and write, `Transformer.WriteOnly` for custom write only, or
  * `Transformer.ReadOnly` for custom read only. The transformer's `write` method intercepts field
  * encoding; its `read` method intercepts field decoding. The object reference is reified at
  * derivation time, so the transformer's methods are invoked at runtime with zero overhead from
  * the annotation layer.
  *
  * Placement: case-class fields only.
  *
  * @param transformer the transform object; must extend `Transformer[FieldType]`
  */
final class transform(val transformer: Transformer[?]) extends SchemaAnnotation

/** Family of field-level codec transforms for `@transform`.
  *
  * A transformer is a named object that customizes how a single case-class field is encoded
  * and decoded. Extend the appropriate sub-trait and pass the object to `@transform` on the
  * field. The object reference is reified at derivation time, so the transformer's methods
  * are invoked at runtime for each encode and decode of that field.
  *
  * Choose the sub-trait that matches the direction of the transform:
  *   - `Full` for transforms that customize both encode and decode.
  *   - `WriteOnly` for transforms that customize encode only (decode uses the derived codec).
  *   - `ReadOnly` for transforms that customize decode only (encode uses the derived codec).
  */
sealed trait Transformer[A]

object Transformer:
    /** Customizes both encode and decode for a field.
      *
      * @tparam A the field type this transformer applies to
      */
    trait Full[A] extends Transformer[A]:
        def write(value: A, writer: kyo.Codec.Writer): Unit
        def read(reader: kyo.Codec.Reader): A

    /** Customizes encode only; decode uses the schema-derived codec for the field.
      *
      * @tparam A the field type this transformer applies to
      */
    trait WriteOnly[A] extends Transformer[A]:
        def write(value: A, writer: kyo.Codec.Writer): Unit

    /** Customizes decode only; encode uses the schema-derived codec for the field.
      *
      * @tparam A the field type this transformer applies to
      */
    trait ReadOnly[A] extends Transformer[A]:
        def read(reader: kyo.Codec.Reader): A
end Transformer

/** Namespace for Protobuf-specific schema annotations.
  *
  * Format-specific annotations are scoped under an object named for their wire format, so a
  * Protobuf directive reads `@proto.fieldNumber(3)` rather than a flat `@protoFieldNumber(3)`.
  * Schema-general annotations (`@rename`, `@doc`, `@discriminator`, ...) stay flat; only
  * format-specific ones take a scope.
  */
object proto:

    /** Pins the Protobuf field number for a case-class field.
      *
      * Sets the wire field number the Protobuf codec writes and matches on decode for this field,
      * overriding the default derived by hashing the field name. The pin desugars onto the same
      * leaf-name `Schema.fieldId` override the programmatic `Schema[A].fieldId(_.field)(n)` builder
      * produces, so encode, decode, `Protobuf.protoSchema`, and `Protobuf.fieldNumberAudit` all
      * honor it; the audit reports the field with `pinned = true`. A programmatic `.fieldId` applied
      * after derivation takes precedence over this annotation, consistent with the whole annotation
      * set.
      *
      * Pin a stable number for strict interoperability with an external `.proto` definition or to
      * move a field out of proto3's reserved band (19000-19999). Proto field numbers are positive
      * (`>= 1`); a non-positive number is a usage error.
      *
      * Placement: case-class fields only.
      *
      * @param number the Protobuf field number to pin; must be positive
      */
    final class fieldNumber(val number: Int) extends SchemaAnnotation
end proto

/** Predicate controlling field omission in `@omit(omit.When(predicate))`.
  *
  * Implement this trait as a named object and pass it to `@omit(omit.When(predicate))` on a
  * case-class field. At runtime, `test(fieldValue)` is called for each encode and controls whether
  * the field is included in or excluded from the wire output.
  *
  * @see omit.Mode for the full omission mode enum
  */
trait OmitPredicate:
    def test(value: kyo.Structure.Value): Boolean
