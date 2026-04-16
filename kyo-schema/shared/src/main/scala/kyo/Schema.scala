package kyo

import kyo.Codec.Reader
import kyo.Codec.Writer
import kyo.Json.JsonSchema
import kyo.internal.JsonWriter
import kyo.internal.StructureValueReader
import kyo.internal.StructureValueWriter
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
  * field intersection of the original case class and narrows or changes as `drop`, `rename`, `map`, and `add` are applied. Although
  * transform methods return `Any` due to `transparent inline`, at runtime the result is always `Schema[A] { type Focused = F' }` where `F'`
  * reflects the new shape.
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
final class Schema[A] private[kyo] (
    private[kyo] val getter: A => Maybe[Any],
    private[kyo] val setter: (A, Any) => A,
    private[kyo] val segments: Seq[String],
    val examples: Chunk[A] = Chunk.empty,
    val fieldDocs: Map[Seq[String], String] = Map.empty,
    val fieldDeprecated: Map[Seq[String], String] = Map.empty,
    val constraints: Seq[Schema.Constraint] = Seq.empty,
    val droppedFields: Set[String] = Set.empty,
    val renamedFields: Chunk[(String, String)] = Chunk.empty,
    val computedFields: Chunk[(String, A => Any)] = Chunk.empty,
    val fieldTransforms: Chunk[(String, Any => Any)] = Chunk.empty,
    val sourceFields: Seq[Field[?, ?]] = Seq.empty,
    private[kyo] val checks: Seq[A => Seq[ValidationFailedException]] = Seq.empty,
    private[kyo] val documentation: Maybe[String] = Maybe.empty,
    private[kyo] val fieldIdOverrides: Map[Seq[String], Int] = Map.empty,
    private[kyo] val serializeWrite: Maybe[(A, Writer) => Unit] = Maybe.empty,
    private[kyo] val serializeRead: Maybe[Reader => A] = Maybe.empty,
    private[kyo] val discriminatorField: Maybe[String] = Maybe.empty
):

    /** The structural representation type. Set by factory/transforms. */
    type Focused

    /** Pre-built root navigator for focus lambda resolution.
      *
      * Casts getter/setter from Any to Focused once at construction time so that all focus-lambda methods can use it directly without
      * per-call casts.
      */
    private[kyo] val rootSelect: Focus.Select[A, Focused] =
        Focus.Select.create[A, Focused](
            getter.asInstanceOf[A => Maybe[Focused]],
            setter.asInstanceOf[(A, Focused) => A],
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
        new String(encode[C](value).toArray, java.nio.charset.StandardCharsets.UTF_8)

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
        decode[C](Span.from(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)))

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
        Schema.createWithFocused[A, Focused](
            getter,
            setter,
            segments,
            checks :+ rootCheck,
            fieldTransforms,
            computedFields,
            renamedFields,
            sourceFields,
            droppedFields,
            documentation,
            fieldDocs,
            examples,
            fieldDeprecated,
            constraints,
            fieldIdOverrides,
            serializeWrite,
            serializeRead,
            discriminatorField
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
        Schema.createWithFocused[A, Focused](
            getter,
            setter,
            segments,
            checks,
            fieldTransforms,
            computedFields,
            renamedFields,
            sourceFields,
            droppedFields,
            Maybe(description),
            fieldDocs,
            examples,
            fieldDeprecated,
            constraints,
            fieldIdOverrides,
            serializeWrite,
            serializeRead,
            discriminatorField
        )

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
        Schema.createWithFocused[A, Focused](
            getter,
            setter,
            segments,
            checks,
            fieldTransforms,
            computedFields,
            renamedFields,
            sourceFields,
            droppedFields,
            documentation,
            fieldDocs,
            examples,
            fieldDeprecated,
            constraints,
            fieldIdOverrides,
            serializeWrite,
            serializeRead,
            Maybe(fieldName)
        )

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
        Schema.createWithFocused[A, Focused](
            getter,
            setter,
            segments,
            checks,
            fieldTransforms,
            computedFields,
            renamedFields,
            sourceFields,
            droppedFields,
            documentation,
            fieldDocs,
            examples :+ value,
            fieldDeprecated,
            constraints,
            fieldIdOverrides,
            serializeWrite,
            serializeRead,
            discriminatorField
        )

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
      * Format is advisory in JSON Schema — no runtime validation check is produced. Only stores a Constraint for JSON Schema enrichment.
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
        Schema.createWithFocused[A, Focused](
            getter,
            setter,
            segments,
            checks,
            fieldTransforms,
            computedFields,
            renamedFields,
            sourceFields,
            droppedFields,
            documentation,
            fieldDocs,
            examples,
            fieldDeprecated,
            constraints,
            fieldIdOverrides.updated(navigated.segments, id),
            serializeWrite,
            serializeRead,
            discriminatorField
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

    /** Maps a field's value using the given function, optionally changing its type.
      *
      * When the function returns the same type, returns Schema[A] (type unchanged). When the function returns a different type V2, returns
      * `Schema[A] { type Focused = F' }` where F' replaces the named field's type with V2. The transform function is stored internally for
      * use by structural operations (result/to/fold). Fails at compile time if the field does not exist.
      *
      * Note: schemas with map transforms cannot be decoded (the transform function is not reversible); serialization (encoding) still
      * works.
      */
    transparent inline def map[N <: String & Singleton, V2](inline fieldName: N)(using
        h: Fields.Have[Focused, N]
    )(
        f: h.Value => V2
    ): Any =
        ${ internal.SchemaTransformMacro.mapImpl[A, Focused]('this, 'fieldName, 'f) }

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

    /** Maps a field identified by a Focus lambda using the given function, optionally changing its type.
      *
      * Lambda version of `map(fieldName)(f)`. The field is identified by the lambda, and the transform function is applied to the field's
      * value. Provides IDE autocompletion for the field.
      *
      * {{{
      * Schema[User].map(_.age)(_.toString)  // equivalent to Schema[User].map("age")(_.toString)
      * }}}
      */
    transparent inline def map[V, V2](
        inline focus: Focus.Select[A, Focused] => Focus.Select[A, V]
    )(f: V => V2): Any =
        ${ internal.SchemaTransformMacro.mapFocusImpl[A, Focused]('this, 'focus, 'f) }

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

    /** Returns the runtime type introspection tree for type A.
      *
      * Derives a Structure.Type at compile time via macro that describes the structure of A: fields for case classes, variants for sealed
      * traits, element types for collections, etc.
      */
    inline def structure: Structure.Type = Structure.of[A]

    // --- Structural field operations ---

    /** Folds over the fields of A with fully typed field descriptors and values.
      *
      * Uses a polymorphic function to preserve the exact field name and value types at each step. The macro unrolls the fold at compile
      * time, calling `f` with the correct singleton name type `N` and value type `V` for each field.
      *
      * Iterates original case class fields in declaration order. Respects transforms: dropped fields are skipped, renamed fields use the
      * new name, map transforms are applied, computed fields are included.
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
      * Respects transforms: dropped fields are omitted, renamed fields use the new name, map transforms are applied, computed fields are
      * included. The returned function can be stored and applied to multiple values.
      */
    def toRecord(using A <:< Product): A => Record[Focused] =
        (a: A) => this.resultOf(a)

    // --- Focus lambda navigation (zero-conflict) ---

    /** Navigates via a lambda on Focus, auto-detecting product vs sum navigation.
      *
      * For product-only navigation (case class fields), returns `Focus[Root, Value, Focus.Id]` with direct `get`/`set`/`modify`. For
      * navigation that crosses a sum type (sealed trait variant), returns `Focus[Root, Value, Maybe]` with Maybe-wrapped
      * `get`/`set`/`modify`.
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
      * Uses a Focus lambda to identify the collection field. Returns `Focus[Root, E, Chunk]` for get/set/modify operations on all elements.
      * For element validation, use `Schema.check` with a predicate over the collection.
      *
      * {{{
      * val each = Schema[Order].foreach(_.items)
      * each.get(order)                         // Chunk[Item]
      * each.modify(order)(item => item.copy(price = item.price * 1.1))
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
        Schema.primitive[B](
            (b, writer) => self.serializeWrite.get(from(b), writer),
            reader => to(self.serializeRead.get(reader))
        )
    end transform

    // --- Ordering/CanEqual givens ---

    /** Derives an Ordering[A] for the source type by comparing fields in declaration order. */
    inline given order(using Mirror.ProductOf[A]): Ordering[A] = Schema.deriveOrdering[A]

    /** Returns a CanEqual[A, A] instance for the source type. */
    inline given canEqual: CanEqual[A, A] = CanEqual.derived

    // --- Internal ---

    /** Adds a field transform function. Used by mapImpl. */
    private[kyo] def withFieldTransform(fieldName: String, f: Any => Any): Schema[A] { type Focused = Schema.this.Focused } =
        Schema.createWithFocused[A, Focused](
            getter,
            setter,
            segments,
            checks,
            fieldTransforms :+ (fieldName, f),
            computedFields,
            renamedFields,
            sourceFields,
            droppedFields,
            documentation,
            fieldDocs,
            examples,
            fieldDeprecated,
            constraints,
            fieldIdOverrides,
            serializeWrite,
            serializeRead,
            discriminatorField
        )

    /** Adds a computed field function. Used by addImpl. */
    private[kyo] def withComputedField[R](fieldName: String, f: A => Any): Schema[A] { type Focused = Schema.this.Focused } =
        Schema.createWithFocused[A, Focused](
            getter,
            setter,
            segments,
            checks,
            fieldTransforms,
            computedFields :+ (fieldName, f),
            renamedFields,
            sourceFields,
            droppedFields,
            documentation,
            fieldDocs,
            examples,
            fieldDeprecated,
            constraints,
            fieldIdOverrides,
            serializeWrite,
            serializeRead,
            discriminatorField
        )

    /** Converts a value of type A to a Record containing the fields of Focused.
      *
      * Internal implementation used by the `toRecord` function. Respects transforms: dropped fields are omitted, renamed fields use the new
      * name, map transforms are applied, computed fields are included.
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
                val rawValue = product.productElement(idx)
                val transformedValue = fieldTransforms.foldLeft(rawValue) { (v, tf) =>
                    if tf._1 == fieldName then tf._2(v) else v
                }
                acc.update(fieldName, transformedValue)
            else acc
            end if
        }

        // Handle renamed fields
        val dictWithRenames = resolvedRenames.foldLeft(dictFromFields) { case (acc, (sourceName, targetName)) =>
            val originalIdx = sourceFields.indexWhere(_.name == sourceName)
            if originalIdx >= 0 then
                val rawValue = product.productElement(originalIdx)
                val transformedValue = fieldTransforms.foldLeft(rawValue) { (v, tf) =>
                    if tf._1 == targetName then tf._2(v) else v
                }
                acc.update(targetName, transformedValue)
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
    private[kyo] def writeTo(value: A, writer: Writer)(using frame: Frame): Unit =
        internal.SchemaSerializer.writeTo(this, value, writer)

    /** Reads a value from a Reader (low-level deserialization).
      *
      * This is the direct Reader API used by tests and internal code. For high-level usage, prefer `Json.decode` or `Protobuf.decode`.
      */
    private[kyo] def readFrom(reader: Reader)(using frame: Frame): A =
        internal.SchemaSerializer.readFrom(this, reader)

    /** Converts a value to its untyped Structure.Value representation.
      *
      * @param value
      *   the typed value to convert
      * @return
      *   the untyped value tree
      */
    private[kyo] def toStructureValue(value: A)(using Frame): Structure.Value =
        val w = StructureValueWriter()
        writeTo(value, w)
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
        Result.catching[DecodeException](serializeRead.get(r))
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
        Schema.createWithFocused[A, Focused](
            getter,
            setter,
            segments,
            checks ++ other.checks,
            fieldTransforms,
            computedFields,
            renamedFields,
            sourceFields,
            droppedFields,
            documentation,
            fieldDocs,
            examples,
            fieldDeprecated,
            constraints ++ other.constraints,
            fieldIdOverrides ++ other.fieldIdOverrides,
            serializeWrite,
            serializeRead,
            discriminatorField
        )

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
    sealed trait Constraint derives CanEqual, Schema:
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

    // --- Primitive Schema givens ---

    /** Schema for String values. */
    given stringSchema: Schema[String] = primitive[String]((v, w) => w.string(v), _.string())

    /** Schema for Boolean values. */
    given booleanSchema: Schema[Boolean] = primitive[Boolean]((v, w) => w.boolean(v), _.boolean())

    /** Schema for Int values. */
    given intSchema: Schema[Int] = primitive[Int]((v, w) => w.int(v), _.int())

    /** Schema for Long values. */
    given longSchema: Schema[Long] = primitive[Long]((v, w) => w.long(v), _.long())

    /** Schema for Float values. */
    given floatSchema: Schema[Float] = primitive[Float]((v, w) => w.float(v), _.float())

    /** Schema for Double values. */
    given doubleSchema: Schema[Double] = primitive[Double]((v, w) => w.double(v), _.double())

    /** Schema for Short values. */
    given shortSchema: Schema[Short] = primitive[Short]((v, w) => w.short(v), _.short())

    /** Schema for Byte values. */
    given byteSchema: Schema[Byte] = primitive[Byte]((v, w) => w.byte(v), _.byte())

    /** Schema for Char values. */
    given charSchema: Schema[Char] = primitive[Char]((v, w) => w.char(v), _.char())

    /** Schema for BigDecimal values. */
    given bigDecimalSchema: Schema[BigDecimal] = primitive[BigDecimal]((v, w) => w.bigDecimal(v), _.bigDecimal())

    /** Schema for BigInt values. */
    given bigIntSchema: Schema[BigInt] = primitive[BigInt]((v, w) => w.bigInt(v), _.bigInt())

    /** Schema for java.time.Instant values. */
    given instantSchema: Schema[java.time.Instant] = primitive[java.time.Instant]((v, w) => w.instant(v), _.instant())

    /** Schema for java.time.Duration values. */
    given durationSchema: Schema[java.time.Duration] = primitive[java.time.Duration]((v, w) => w.duration(v), _.duration())

    /** Schema for Span[Byte] values. */
    given spanByteSchema: Schema[Span[Byte]] = primitive[Span[Byte]]((v, w) => w.bytes(v), _.bytes())

    /** Frame schema — serializes as the raw encoded string. Frame is an opaque type backed by String at runtime.
      */
    given frameSchema: Schema[Frame] = primitive[Frame](
        (v, w) => w.string(v.toString),
        reader => reader.string().asInstanceOf[Frame]
    )

    /** Tag schema — serializes as the string representation. Tags are opaque types backed by String at runtime for static tags.
      */
    given tagSchema[A]: Schema[Tag[A]] = primitive[Tag[A]](
        (v, w) =>
            v match
                case s: String => w.string(s)
                case _         => w.string(v.show),
        reader => reader.string().asInstanceOf[Tag[A]]
    )

    /** Schema for java.time.LocalDate values. Serializes as ISO-8601 string. */
    given localDateSchema: Schema[java.time.LocalDate] =
        primitive[java.time.LocalDate]((v, w) => w.string(v.toString), r => java.time.LocalDate.parse(r.string()))

    /** Schema for java.time.LocalTime values. Serializes as ISO-8601 string. */
    given localTimeSchema: Schema[java.time.LocalTime] =
        primitive[java.time.LocalTime]((v, w) => w.string(v.toString), r => java.time.LocalTime.parse(r.string()))

    /** Schema for java.time.LocalDateTime values. Serializes as ISO-8601 string. */
    given localDateTimeSchema: Schema[java.time.LocalDateTime] =
        primitive[java.time.LocalDateTime]((v, w) => w.string(v.toString), r => java.time.LocalDateTime.parse(r.string()))

    /** Schema for java.util.UUID values. Serializes as string. */
    given uuidSchema: Schema[java.util.UUID] =
        primitive[java.util.UUID]((v, w) => w.string(v.toString), r => java.util.UUID.fromString(r.string()))

    /** Schema for Unit values. */
    given unitSchema: Schema[Unit] = primitive[Unit](
        (_, w) => w.nil(),
        r =>
            r.skip(); ()
    )

    // --- Collection Schema givens ---

    /** Schema for List[A] values. */
    given listSchema[A](using inner: Schema[A]): Schema[List[A]] =
        primitive[List[A]](
            (value, writer) =>
                writer.arrayStart(value.size)
                value.foreach(inner.serializeWrite.get(_, writer))
                writer.arrayEnd()
            ,
            reader =>
                discard(reader.arrayStart())
                val builder = List.newBuilder[A]
                @tailrec
                def loop(count: Int): Unit =
                    if reader.hasNextElement() then
                        reader.checkCollectionSize(count)
                        builder += inner.serializeRead.get(reader)
                        loop(count + 1)
                loop(1)
                reader.arrayEnd()
                builder.result()
        )

    /** Schema for Vector[A] values. */
    given vectorSchema[A](using inner: Schema[A]): Schema[Vector[A]] =
        primitive[Vector[A]](
            (value, writer) =>
                writer.arrayStart(value.size)
                value.foreach(inner.serializeWrite.get(_, writer))
                writer.arrayEnd()
            ,
            reader =>
                discard(reader.arrayStart())
                val builder = Vector.newBuilder[A]
                @tailrec
                def loop(count: Int): Unit =
                    if reader.hasNextElement() then
                        reader.checkCollectionSize(count)
                        builder += inner.serializeRead.get(reader)
                        loop(count + 1)
                loop(1)
                reader.arrayEnd()
                builder.result()
        )

    /** Schema for Set[A] values. */
    given setSchema[A](using inner: Schema[A]): Schema[Set[A]] =
        primitive[Set[A]](
            (value, writer) =>
                writer.arrayStart(value.size)
                value.foreach(inner.serializeWrite.get(_, writer))
                writer.arrayEnd()
            ,
            reader =>
                discard(reader.arrayStart())
                val builder = Set.newBuilder[A]
                @tailrec
                def loop(count: Int): Unit =
                    if reader.hasNextElement() then
                        reader.checkCollectionSize(count)
                        builder += inner.serializeRead.get(reader)
                        loop(count + 1)
                loop(1)
                reader.arrayEnd()
                builder.result()
        )

    /** Schema for Chunk[A] values. */
    given chunkSchema[A](using inner: Schema[A]): Schema[Chunk[A]] =
        primitive[Chunk[A]](
            (value, writer) =>
                writer.arrayStart(value.size)
                value.foreach(inner.serializeWrite.get(_, writer))
                writer.arrayEnd()
            ,
            reader =>
                discard(reader.arrayStart())
                val builder = Chunk.newBuilder[A]
                @tailrec
                def loop(count: Int): Unit =
                    if reader.hasNextElement() then
                        reader.checkCollectionSize(count)
                        builder += inner.serializeRead.get(reader)
                        loop(count + 1)
                loop(1)
                reader.arrayEnd()
                builder.result()
        )

    /** Schema for Seq[A] values. */
    given seqSchema[A](using inner: Schema[A]): Schema[Seq[A]] =
        primitive[Seq[A]](
            (value, writer) =>
                writer.arrayStart(value.size)
                value.foreach(inner.serializeWrite.get(_, writer))
                writer.arrayEnd()
            ,
            reader =>
                discard(reader.arrayStart())
                val builder = List.newBuilder[A]
                @tailrec
                def loop(): Unit =
                    if reader.hasNextElement() then
                        builder += inner.serializeRead.get(reader)
                        loop()
                loop()
                reader.arrayEnd()
                builder.result()
        )

    /** Schema for Span[A] values. */
    given spanSchema[A](using inner: Schema[A], ct: scala.reflect.ClassTag[A]): Schema[Span[A]] =
        primitive[Span[A]](
            (value, writer) =>
                writer.arrayStart(value.size)
                value.foreach(inner.serializeWrite.get(_, writer))
                writer.arrayEnd()
            ,
            reader =>
                discard(reader.arrayStart())
                val builder = Chunk.newBuilder[A]
                @tailrec
                def loop(): Unit =
                    if reader.hasNextElement() then
                        builder += inner.serializeRead.get(reader)
                        loop()
                loop()
                reader.arrayEnd()
                Span.from(builder.result())
        )

    /** Schema for Maybe[A] values.
      *
      * Encodes as null when Absent, delegates to inner schema when Present.
      */
    given maybeSchema[A](using inner: Schema[A]): Schema[Maybe[A]] =
        primitive[Maybe[A]](
            (value, writer) =>
                value match
                    case Maybe.Present(v) => inner.serializeWrite.get(v, writer)
                    case _ =>
                        writer.nil()
            ,
            reader =>
                if reader.isNil() then Maybe.empty
                else Maybe(inner.serializeRead.get(reader))
        )

    /** Schema for Option[A] values. */
    given optionSchema[A](using inner: Schema[A]): Schema[Option[A]] =
        primitive[Option[A]](
            (value, writer) =>
                value match
                    case Some(v) => inner.serializeWrite.get(v, writer)
                    case None =>
                        writer.nil()
            ,
            reader =>
                if reader.isNil() then None
                else Some(inner.serializeRead.get(reader))
        )

    /** Schema for Map[String, V] values (object encoding). */
    given stringMapSchema[V](using valueSchema: Schema[V]): Schema[Map[String, V]] =
        primitive[Map[String, V]](
            (value, writer) =>
                writer.mapStart(value.size)
                value.iterator.zipWithIndex.foreach { case ((k, v), idx) =>
                    writer.field(k, idx)
                    valueSchema.serializeWrite.get(v, writer)
                }
                writer.mapEnd()
            ,
            reader =>
                discard(reader.mapStart())
                val builder = Map.newBuilder[String, V]
                @tailrec
                def loop(count: Int): Unit =
                    if reader.hasNextEntry() then
                        reader.checkCollectionSize(count)
                        val k = reader.field()
                        val v = valueSchema.serializeRead.get(reader)
                        builder += (k -> v)
                        loop(count + 1)
                loop(1)
                reader.mapEnd()
                builder.result()
        )

    // --- Tuple Schemas ---

    given tuple2Schema[A: Schema, B: Schema]: Schema[(A, B)]                                           = Schema.derived
    given tuple3Schema[A: Schema, B: Schema, C: Schema]: Schema[(A, B, C)]                             = Schema.derived
    given tuple4Schema[A: Schema, B: Schema, C: Schema, D: Schema]: Schema[(A, B, C, D)]               = Schema.derived
    given tuple5Schema[A: Schema, B: Schema, C: Schema, D: Schema, E: Schema]: Schema[(A, B, C, D, E)] = Schema.derived

    // --- Kyo-data type Schemas ---

    /** Schema for Result[E, A] values - serialized as discriminated union. */
    given resultSchema[E, A](using eSchema: Schema[E], aSchema: Schema[A]): Schema[Result[E, A]] =
        primitive[Result[E, A]](
            (value, writer) =>
                value match
                    case Result.Success(a) =>
                        writer.objectStart("Result", 2)
                        writer.field("$type", 0)
                        writer.string("success")
                        writer.field("value", 1)
                        aSchema.serializeWrite.get(a.asInstanceOf[A], writer)
                        writer.objectEnd()
                    case Result.Failure(e) =>
                        writer.objectStart("Result", 2)
                        writer.field("$type", 0)
                        writer.string("failure")
                        writer.field("value", 1)
                        eSchema.serializeWrite.get(e.asInstanceOf[E], writer)
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
            reader =>
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
                    throw kyo.MissingFieldException(Seq.empty, "$type")(using reader.frame)
                if captured.isEmpty then
                    throw kyo.MissingFieldException(Seq.empty, "value")(using reader.frame)
                val capturedReader = captured.get
                typeName.get match
                    case "success" => Result.succeed(aSchema.serializeRead.get(capturedReader))
                    case "failure" => Result.fail(eSchema.serializeRead.get(capturedReader))
                    case "panic" =>
                        val msg: Maybe[String] =
                            if capturedReader.isNil() then Maybe.empty
                            else Maybe(capturedReader.string())
                        Result.panic(new RuntimeException(msg.getOrElse(null: String))) // RuntimeException accepts null message
                    case other => throw kyo.UnknownVariantException(Seq.empty, other)(using reader.frame)
                end match
        )

    /** Schema for Dict[String, V] - serializes as a JSON object. */
    given stringDictSchema[V](using vSchema: Schema[V]): Schema[Dict[String, V]] =
        primitive[Dict[String, V]](
            (value, writer) =>
                writer.mapStart(value.size)
                discard(value.foldLeft(0) { (idx, k, v) =>
                    writer.field(k, idx)
                    vSchema.serializeWrite.get(v, writer)
                    idx + 1
                })
                writer.mapEnd()
            ,
            reader =>
                discard(reader.mapStart())
                @tailrec
                def loop(dict: Dict[String, V], count: Int): Dict[String, V] =
                    if reader.hasNextEntry() then
                        reader.checkCollectionSize(count)
                        val k = reader.field()
                        val v = vSchema.serializeRead.get(reader)
                        loop(dict.update(k, v), count + 1)
                    else dict
                val dict = loop(Dict.empty[String, V], 1)
                reader.mapEnd()
                dict
        )

    /** Schema for Dict[K, V] with non-String keys - serializes as array of [k, v] pairs. */
    given dictSchema[K, V](using kSchema: Schema[K], vSchema: Schema[V]): Schema[Dict[K, V]] =
        primitive[Dict[K, V]](
            (value, writer) =>
                writer.arrayStart(value.size)
                value.foreach { (k, v) =>
                    writer.arrayStart(2)
                    kSchema.serializeWrite.get(k, writer)
                    vSchema.serializeWrite.get(v, writer)
                    writer.arrayEnd()
                }
                writer.arrayEnd()
            ,
            reader =>
                discard(reader.arrayStart())
                @tailrec
                def loop(dict: Dict[K, V], count: Int): Dict[K, V] =
                    if reader.hasNextElement() then
                        reader.checkCollectionSize(count)
                        discard(reader.arrayStart())
                        val k = kSchema.serializeRead.get(reader)
                        val v = vSchema.serializeRead.get(reader)
                        reader.arrayEnd()
                        loop(dict.update(k, v), count + 1)
                    else dict
                val dict = loop(Dict.empty[K, V], 1)
                reader.arrayEnd()
                dict
        )

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

    /** Enriches a JsonSchema.Obj with runtime metadata: doc, field docs, field deprecations, examples, and constraints.
      *
      * Separated from the inline enrichJsonSchema method so that all operations on the Obj are typed at the concrete JsonSchema.Obj level.
      */
    private[kyo] def enrichObj(
        obj: Json.JsonSchema.Obj,
        doc: Maybe[String],
        fieldDocs: Map[Seq[String], String],
        fieldDeprecated: Map[Seq[String], String],
        examples: Chunk[Structure.Value],
        constraints: Seq[Constraint] = Seq.empty,
        droppedFields: Set[String] = Set.empty,
        renamedFields: Map[String, String] = Map.empty
    ): Json.JsonSchema.Obj =
        internal.JsonSchemaEnricher.enrichObj(obj, doc, fieldDocs, fieldDeprecated, examples, constraints, droppedFields, renamedFields)

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

    /** Internal factory for macro-generated Schema instances. Not part of public API.
      *
      * The F type parameter becomes the Focused type member on the returned Schema.
      */
    private[kyo] def create[A, F](
        getter: A => Maybe[F],
        setter: (A, F) => A,
        segments: Seq[String],
        sourceFields: Seq[Field[?, ?]] = Seq.empty
    ): Schema[A] { type Focused = F } =
        internal.SchemaFactory.create(getter, setter, segments, sourceFields)

    /** Internal factory for macro-generated Schema instances with serialization. */
    private[kyo] def create[A, F](
        getter: A => Maybe[F],
        setter: (A, F) => A,
        segments: Seq[String],
        sourceFields: Seq[Field[?, ?]],
        writeFn: (A, Writer) => Unit,
        readFn: Reader => A
    ): Schema[A] { type Focused = F } =
        internal.SchemaFactory.create(getter, setter, segments, sourceFields, writeFn, readFn)

    /** Creates a primitive Schema with hand-written serialization functions. */
    private[kyo] def primitive[A](
        writeFn: (A, Writer) => Unit,
        readFn: Reader => A
    ): Schema[A] =
        internal.SchemaFactory.primitive(writeFn, readFn)

    /** Internal factory for transform macros. Copies internal state from a source Schema. Not part of public API.
      *
      * Casts are needed because the transform changes Focused but the underlying getter/setter still operates on A's real runtime
      * structure. This is the ONE place where casts are justified -- the transform only changes what the type system sees, not the runtime
      * representation.
      */
    private[kyo] def createFrom[A, F2](
        source: Schema[A],
        checks: Seq[A => Seq[ValidationFailedException]],
        fieldTransforms: Chunk[(String, Any => Any)],
        computedFields: Chunk[(String, A => Any)],
        renamedFields: Chunk[(String, String)],
        droppedFields: Set[String] = Set.empty
    ): Schema[A] { type Focused = F2 } =
        internal.SchemaFactory.createFrom(source, checks, fieldTransforms, computedFields, renamedFields, droppedFields)

    /** Internal factory for creating Schema with a specific Focused type, preserving all state. Used by methods that return
      * `Schema[A] { type Focused = E }` without changing E.
      */
    private[kyo] def createWithFocused[A, E](
        getter: A => Maybe[Any],
        setter: (A, Any) => A,
        segments: Seq[String],
        checks: Seq[A => Seq[ValidationFailedException]],
        fieldTransforms: Chunk[(String, Any => Any)],
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
        serializeWrite: Maybe[(A, Codec.Writer) => Unit] = Maybe.empty,
        serializeRead: Maybe[Codec.Reader => A] = Maybe.empty,
        discriminatorField: Maybe[String] = Maybe.empty
    ): Schema[A] { type Focused = E } =
        internal.SchemaFactory.createWithFocused(
            getter,
            setter,
            segments,
            checks,
            fieldTransforms,
            computedFields,
            renamedFields,
            sourceFields,
            droppedFields,
            doc,
            fieldDocs,
            examples,
            fieldDeprecated,
            constraints,
            fieldIds,
            serializeWrite,
            serializeRead,
            discriminatorField
        )

end Schema
