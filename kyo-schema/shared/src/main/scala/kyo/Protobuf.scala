package kyo

import scala.annotation.tailrec

final class Protobuf(val config: Protobuf.Config) extends Codec:
    def this() = this(Protobuf.Config.Default)
    def newWriter(): Codec.Writer = new kyo.internal.ProtobufWriter()
    def newReader(input: Span[Byte])(using Frame): Codec.Reader =
        new kyo.internal.ProtobufReader(input.toArray)

    override private[kyo] def validate(structure: Structure.Type)(using Frame): Unit =
        if config.conformance == Protobuf.Conformance.Strict then Protobuf.validateCanonical(structure)
end Protobuf

/** Entry point for Protocol Buffers binary serialization and schema generation.
  *
  * All methods are inline and require a `Schema[A]` instance in scope. Encoding produces proto3 wire-format bytes. Field numbers are
  * derived from stable hashes of field names (XXH32 applied to the name's JLS string hash), enabling schema evolution without breaking
  * existing encoded data.
  *
  * @see
  *   [[kyo.Schema]] for the type-driven serialization model
  * @see
  *   [[kyo.Json]] for JSON serialization
  */
object Protobuf:
    /** Proto3 conformance mode for the Protobuf codec.
      *
      * Selects which wire forms `encode` is allowed to produce. The default is `Strict`, so the
      * zero-argument `given Protobuf` is canonical out of the box and interoperates with external
      * proto3 tooling for the shapes proto3 defines.
      *
      * @see
      *   [[Protobuf.Config]] for where this is configured
      */
    enum Conformance derives CanEqual:
        /** Rejects non-proto3-native map keys at encode time. A map whose key type is not proto3-native
          * (integral, bool, or string scalar, or an opaque/value-class type reducing to one of these)
          * raises [[SchemaNotSerializableException]] before any bytes are written. Canonical proto3
          * `map<K, V>` key types are accepted.
          *
          * This mode does not guarantee every emitted field number is accepted by external `protoc`.
          * Hash-derived field numbers in the reserved range 19000-19999 produce a WARNING from
          * `protoSchema` regardless of conformance mode. Pin numbers via `Schema.fieldId` and
          * verify with `Protobuf.fieldNumberAudit` to avoid the reserved range for external interop.
          */
        case Strict

        /** Round-trippable behavior: non-canonical extensions are permitted so every schema that
          * round-trips through this codec keeps encoding, at the cost of strict external proto3 interop.
          */
        case Permissive
    end Conformance

    /** Configuration for the Protobuf codec.
      *
      * @param conformance
      *   the proto3 conformance mode; defaults to [[Conformance.Strict]] so the codec is canonical
      *   by default. See [[Conformance]] for the Strict vs Permissive contract.
      * @param maxDepth
      *   maximum nesting depth honored by `decode` across every recursive container (message, list,
      *   string-keyed map, non-string map)
      * @param maxCollectionSize
      *   maximum number of entries honored by `decode` for maps, sets, arrays
      * @param protoSchemaProvenance
      *   when false, protoSchema omits the 'pin a stable number' comment on hash-derived field
      *   numbers; the reserved-range warning is always emitted
      */
    final case class Config(
        conformance: Conformance = Conformance.Strict,
        maxDepth: Int = Codec.DefaultMaxDepth,
        maxCollectionSize: Int = Codec.DefaultMaxCollectionSize,
        protoSchemaProvenance: Boolean = true
    ) derives CanEqual

    object Config:
        val Default: Config = Config()
    end Config

    given Protobuf = Protobuf()

    /** Encodes a value of type A to Protocol Buffers binary bytes.
      *
      * @param value
      *   the value to encode
      * @return
      *   the proto3 wire-format bytes
      */
    inline def encode[A](value: A)(using protobuf: Protobuf, schema: Schema[A], frame: Frame): Span[Byte] =
        protobuf.validate(schema.structure)
        val w         = protobuf.newWriter()
        val overrides = schema.fieldIdNameOverrides
        if overrides.nonEmpty then
            w match
                case pw: kyo.internal.ProtobufWriter => val _ = pw.withFieldIdOverrides(overrides)
                case _                               => ()
        end if
        schema.writeTo(value, w)
        w.result()
    end encode

    /** True iff `key` is a type proto3 admits as a `map<K, V>` key: an integral, bool, or string scalar
      * (BigInt / BigDecimal serialize as string, and Instant / Duration serialize as sint64, so they qualify). Bytes, float, double, and
      * any non-primitive (message, enum, collection) are not valid proto3 map keys.
      */
    private def isProto3MapKey(key: Structure.Type): Boolean =
        key match
            case p: Structure.Type.Primitive =>
                p.kind match
                    case Structure.PrimitiveKind.Double | Structure.PrimitiveKind.Float | Structure.PrimitiveKind.Bytes |
                        Structure.PrimitiveKind.Unit => false
                    case _ => true
            case _ => false
    end isProto3MapKey

    /** Walks a schema structure under [[Conformance.Strict]] and rejects any shape proto3 cannot
      * encode canonically. The only non-canonical shape this codec can produce is a map whose key is
      * not a proto3-native scalar (the entry-message key form), so that is the entire rejection set.
      * Product / Sum recursion is cycle-guarded by name; every container is reached so a map nested
      * anywhere is validated.
      */
    private def validateCanonical(structure: Structure.Type)(using Frame): Unit =
        // Explicit work-stack loop (tail-recursive): a Structure walk has multiple children per node
        // (a Product's fields, a Sum's variants, a Mapping's key+value), so a single self-recursive
        // tail call is impossible; the stack keeps the traversal O(1) on the call stack. `seen` is a
        // global name set (carried immutably): each distinct type need only be CHECKED once.
        @tailrec def loop(stack: List[Structure.Type], seen: Set[String]): Unit =
            stack match
                case Nil => ()
                case t :: rest =>
                    t match
                        case Structure.Type.Mapping(_, _, key, value) =>
                            if !isProto3MapKey(key) then
                                throw SchemaNotSerializableException(
                                    s"non-canonical proto3 map key: ${key.name}"
                                )
                            end if
                            loop(key :: value :: rest, seen)
                        case Structure.Type.Collection(_, _, elem) => loop(elem :: rest, seen)
                        case Structure.Type.Optional(_, _, inner)  => loop(inner :: rest, seen)
                        case p: Structure.Type.Product =>
                            if seen.contains(p.name) then loop(rest, seen)
                            else loop(p.fields.foldRight(rest)((f, a) => f.fieldType :: a), seen + p.name)
                        case s: Structure.Type.Sum =>
                            if seen.contains(s.name) then loop(rest, seen)
                            else loop(s.variants.foldRight(rest)((v, a) => v.variantType :: a), seen + s.name)
                        // A structureless leaf (primitive or open type) has no nested children to
                        // recurse, so nothing to validate.
                        case _ => loop(rest, seen)
        loop(structure :: Nil, Set.empty)
    end validateCanonical

    /** Decodes Protocol Buffers binary bytes using the codec's configured limits. */
    inline def decode[A](input: Span[Byte])(using protobuf: Protobuf, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        decode(input, protobuf.config.maxDepth, protobuf.config.maxCollectionSize)
    end decode

    /** Decodes Protocol Buffers binary bytes into a value of type A with explicit limits.
      *
      * @param input
      *   the proto3 wire-format bytes to decode
      * @param maxDepth
      *   maximum nesting depth for objects/arrays
      * @param maxCollectionSize
      *   maximum number of entries in maps, sets, or arrays
      * @return
      *   the decoded value, or a DecodeException if the input is malformed or does not match the schema
      */
    def decode[A](
        input: Span[Byte],
        maxDepth: Int,
        maxCollectionSize: Int
    )(using protobuf: Protobuf, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        val reader = protobuf.newReader(input)
        reader.resetLimits(maxDepth, maxCollectionSize)
        val overrides = schema.fieldIdNameOverrides
        if overrides.nonEmpty then
            reader match
                case pr: kyo.internal.ProtobufReader => val _ = pr.withFieldIdOverrides(overrides)
                case _                               => ()
        end if
        Result.catching[DecodeException](schema.readFrom(reader))
    end decode

    /** Generates a `.proto` file definition string for type A at compile time.
      *
      * The returned string uses proto3 syntax and can be written directly to a `.proto` file. Nested case classes are emitted as separate
      * message definitions.
      *
      * @tparam A
      *   the type to describe
      * @return
      *   a proto3 schema string ready for use as a `.proto` file
      */
    inline def protoSchema[A](using protobuf: Protobuf, s: Schema[A], frame: Frame): String =
        ProtoSchema.fromStructure(s.structure, s.fieldIdNameOverrides, protobuf.config.protoSchemaProvenance)

    /** Audits the wire field number of every message field in `A`, recursively.
      *
      * Pure and total: no encode, no decode, no throw. One [[FieldNumberInfo]] per user-facing
      * message field, with a dotted `path` for nested messages. `number` is the field number the
      * codec actually writes (the XXH32 scheme, or a leaf-name `Schema.fieldId` override),
      * `pinned` is true when the number came from an override, and `inReservedRange` is true when
      * the number falls in proto3's reserved band 19000-19999 (which external protoc rejects).
      *
      * @tparam A the type to audit
      * @return one row per message field, in declaration order, depth-first
      * @see [[FieldNumberInfo]]
      */
    inline def fieldNumberAudit[A](using schema: Schema[A], frame: Frame): Chunk[FieldNumberInfo] =
        auditStructure(schema.structure, schema.fieldIdNameOverrides, schema.pinnedFieldNames)

    /** Field-number provenance for one field of a message tree.
      *
      * One row per field, recursive over nested messages.
      *
      * @param path     the dotted path from the root message (e.g. "inner.id")
      * @param name     the leaf field name
      * @param number   the wire field number this codec uses
      * @param pinned   true when a single-segment (leaf field-name) `Schema` field-id override is
      *   set and is wire-functional, whether pinned programmatically via `Schema.fieldId` or
      *   declaratively via `@proto.fieldNumber(n)`; a nested-path (multi-segment) override is a
      *   consistent no-op (`pinned` stays false)
      * @param inReservedRange true if `number` is in proto3's reserved field-number band
      *   19000-19999, which external tooling rejects; pin a stable number to avoid it
      * @see [[Protobuf.fieldNumberAudit]]
      */
    final case class FieldNumberInfo(
        path: String,
        name: String,
        number: Int,
        pinned: Boolean,
        inReservedRange: Boolean
    ) derives CanEqual

    private[kyo] def auditStructure(rt: Structure.Type, overrides: Map[String, Int], pinnedNames: Set[String]): Chunk[FieldNumberInfo] =
        val buf = scala.collection.mutable.ArrayBuffer.empty[FieldNumberInfo]
        // `overrides`/`pinnedNames` are the AUDITED schema's OWN map, so they resolve every field of
        // that schema (top-level fields, and fields of a nested Product reached through a container).
        // A field reached through a Structure.Sum's variant belongs to that VARIANT's OWN Schema,
        // never the audited schema, so its own `@proto.fieldNumber` pin is invisible to `overrides`:
        // fall back to the field's own captured annotation, which derivation threads onto
        // Structure.Field regardless of nesting. A programmatic `.fieldId` override on the audited
        // schema still takes precedence when present, matching Schema.fieldId's documented precedence.
        def resolve(field: Structure.Field): (Int, Boolean) =
            overrides.get(field.name) match
                case Some(n) => (n, pinnedNames.contains(field.name))
                case None =>
                    field.annotations.collectFirst { case fn: kyo.schema.proto.fieldNumber => fn.number } match
                        case Some(n) => (n, true)
                        case None    => (kyo.internal.CodecMacro.fieldId(field.name), false)
        end resolve
        def isReservedRange(n: Int): Boolean = n >= 19000 && n <= 19999
        // A traversal frame: the dotted-path prefix, the type to visit, and the ancestry (the set of
        // type names on the path from the root to this node). Ancestry-based recursion guard: a
        // Product/Sum is entered (its fields emitted) when its name is NOT on its own path; recursion
        // stops only on a genuine cycle (name already on the path, e.g. TreeNode -> List[TreeNode]).
        // The ancestry is per-frame, so a type REUSED across two sibling fields is not on its sibling's
        // path and emits its field rows at EACH occurrence (both list-element and map-value inner
        // fields). A global `seen` would wrongly drop the second occurrence.
        final case class Frame(prefix: String, tpe: Structure.Type, ancestry: Set[String])
        // Explicit work-stack loop (tail-recursive): a node has multiple children (a Product's fields,
        // a Sum's variants), so a single self-recursive tail call is impossible; the stack keeps the
        // walk O(1) on the call stack. `children` is a tightly-scoped local accumulator per node.
        @tailrec def loop(stack: List[Frame]): Unit =
            stack match
                case Nil => ()
                case Frame(prefix, tpe, ancestry) :: rest =>
                    tpe match
                        case p: Structure.Type.Product if !ancestry.contains(p.name) =>
                            val nextAncestry = ancestry + p.name
                            val children     = scala.collection.mutable.ListBuffer.empty[Frame]
                            p.fields.foreach { field =>
                                val name               = field.name
                                val path               = if prefix.isEmpty then name else s"$prefix.$name"
                                val (number, isPinned) = resolve(field)
                                buf += FieldNumberInfo(path, name, number, isPinned, isReservedRange(number))
                                field.fieldType match
                                    case np: Structure.Type.Product             => children += Frame(path, np, nextAncestry)
                                    case ns: Structure.Type.Sum                 => children += Frame(path, ns, nextAncestry)
                                    case Structure.Type.Optional(_, _, inner)   => children += Frame(path, inner, nextAncestry)
                                    case Structure.Type.Collection(_, _, elem)  => children += Frame(path, elem, nextAncestry)
                                    case Structure.Type.Mapping(_, _, _, value) => children += Frame(s"$path.value", value, nextAncestry)
                                    case _                                      => ()
                                end match
                            }
                            loop(children.toList ::: rest)
                        case s: Structure.Type.Sum if !ancestry.contains(s.name) =>
                            val nextAncestry = ancestry + s.name
                            val children     = scala.collection.mutable.ListBuffer.empty[Frame]
                            s.variants.foreach { variant =>
                                variant.variantType match
                                    case vp: Structure.Type.Product => children += Frame(prefix, vp, nextAncestry)
                                    case vs: Structure.Type.Sum     => children += Frame(prefix, vs, nextAncestry)
                                    case _                          => ()
                            }
                            loop(children.toList ::: rest)
                        case _ => loop(rest)
        loop(Frame("", rt, Set.empty) :: Nil)
        Chunk.from(buf)
    end auditStructure

    /** Generates Protocol Buffers schema (.proto file content) from Scala types.
      *
      * Supports:
      *   - Case classes -> `message` with numbered fields
      *   - Sealed traits -> `message` with `oneof`
      *   - Primitives: Int -> sint32, Long -> sint64, Double -> double, Float -> float, String -> string, Boolean -> bool, Short -> sint32,
      *     Byte -> sint32, Char -> sint32
      *   - List[A] / Vector[A] / Seq[A] / Chunk[A] -> `repeated`
      *   - Option[A] -> `optional`
      *   - Nested case classes -> referenced by message name (definitions collected)
      */
    object ProtoSchema:
        /** Generates a `.proto` schema string for type `A`. */
        inline def from[A](using s: Schema[A], frame: Frame): String = fromStructure(s.structure, s.fieldIdNameOverrides)

        /** Derives a proto schema string from a Structure.Type at runtime.
          *
          * `fieldIdOverrides` is the leaf-name override map so the emitted field numbers match the wire:
          * hash-derived numbers carry a provenance comment, and a number in proto3's reserved
          * range 19000-19999 carries an escalated WARNING. The reserved-range WARNING is
          * UNCONDITIONAL on the range: it fires whether the number was hash-derived or pinned, because
          * the band is invalid for external protoc however the number was chosen.
          */
        private[kyo] def fromStructure(rt: Structure.Type, fieldIdOverrides: Map[String, Int], emitProvenance: Boolean = true)(using
            Frame
        ): String =

            def wireFieldNumber(name: String): Int =
                fieldIdOverrides.getOrElse(name, kyo.internal.CodecMacro.fieldId(name))

            def provenanceComment(name: String, number: Int): String =
                // The reserved-range WARNING is unconditional on the range: a number in 19000-19999 is
                // invalid for external protoc however it was chosen (hash-derived OR pinned), so the
                // warning fires before the pinned/hash-derived split. The ordinary "pin a stable
                // number" nudge stays gated on hash-derived numbers only, and is further suppressed
                // when emitProvenance is false.
                if number >= 19000 && number <= 19999 then
                    "  // WARNING: in proto3 reserved range 19000-19999; external protoc REJECTS this number. Choose a number outside the band via Schema.fieldId for external interop."
                else if fieldIdOverrides.contains(name) then ""
                else if emitProvenance then
                    "  // hash-derived field number; pin via Schema.fieldId for stable external interop"
                else ""

            /** Accumulated state threaded through collection. */
            case class State(seen: Set[String], messages: List[String])

            def collect(tpe: Structure.Type.Product | Structure.Type.Sum, state: State): State =
                val name = tpe.name
                if state.seen.contains(name) then state
                else
                    val visited = state.copy(seen = state.seen + name)
                    tpe match
                        case Structure.Type.Sum(name, _, _, variants, _, _) =>
                            val sb = new StringBuilder
                            sb.append(s"message $name {\n")
                            sb.append("  oneof value {\n")
                            val afterVariants = variants.toList.zipWithIndex.foldLeft(visited) { case (acc, (variant, idx)) =>
                                val nextAcc = variant.variantType match
                                    case ps: (Structure.Type.Product | Structure.Type.Sum) => collect(ps, acc)
                                    case _                                                 => acc
                                val childName = variant.name
                                val fieldName = childName.head.toLower +: childName.tail
                                sb.append(s"    $childName $fieldName = ${idx + 1};\n")
                                nextAcc
                            }
                            sb.append("  }\n")
                            sb.append("}\n")
                            afterVariants.copy(messages = sb.toString :: afterVariants.messages)

                        case Structure.Type.Product(name, _, _, fields, _) =>
                            val sb = new StringBuilder
                            sb.append(s"message $name {\n")
                            val afterFields = fields.toList.foldLeft(visited) { case (acc, field) =>
                                val number          = wireFieldNumber(field.name)
                                val (decl, nextAcc) = protoFieldDecl(field.name, field.fieldType, number, acc)
                                sb.append(s"  $decl${provenanceComment(field.name, number)}\n")
                                nextAcc
                            }
                            sb.append("}\n")
                            afterFields.copy(messages = sb.toString :: afterFields.messages)
                    end match
                end if
            end collect

            def protoFieldDecl(name: String, tpe: Structure.Type, fieldNumber: Int, state: State): (String, State) =
                tpe match
                    case p: Structure.Type.Primitive =>
                        val typeName = primitiveProtoName(p.kind)
                        (s"$typeName $name = $fieldNumber;", state)

                    case Structure.Type.Optional(_, _, inner) =>
                        inner match
                            case _: Structure.Type.Optional =>
                                throw new IllegalArgumentException(
                                    s"proto3 does not support nested Optional (Option[Option[_]]) in field '$name'"
                                )
                            case _: Structure.Type.Open =>
                                throw new IllegalArgumentException(
                                    s"proto3 does not support open-shape field type inside Optional in field '$name'"
                                )
                            case (_: Structure.Type.Primitive) | (_: Structure.Type.Collection) |
                                (_: Structure.Type.Mapping) | (_: Structure.Type.Product) | (_: Structure.Type.Sum) =>
                                val (innerType, nextState) = protoTypeName(inner, state)
                                (s"optional $innerType $name = $fieldNumber;", nextState)

                    case Structure.Type.Collection(_, _, elem) =>
                        elem match
                            case _: Structure.Type.Optional =>
                                throw new IllegalArgumentException(
                                    s"proto3 does not support List[Option[_]] (field '$name'): use a wrapper message instead"
                                )
                            case _: Structure.Type.Collection =>
                                throw new IllegalArgumentException(
                                    s"proto3 does not support nested repeated fields (List[List[_]]) in field '$name': use a wrapper message instead"
                                )
                            case _: Structure.Type.Mapping =>
                                throw new IllegalArgumentException(
                                    s"proto3 does not support List[Map[_, _]] (field '$name'): use a wrapper message instead"
                                )
                            case _: Structure.Type.Open =>
                                throw new IllegalArgumentException(
                                    s"proto3 does not support open-shape field type inside Collection in field '$name'"
                                )
                            case (_: Structure.Type.Primitive) | (_: Structure.Type.Product) | (_: Structure.Type.Sum) =>
                                val (innerType, nextState) = protoTypeName(elem, state)
                                (s"repeated $innerType $name = $fieldNumber;", nextState)

                    case Structure.Type.Mapping(_, _, key, value) =>
                        if isProto3MapKey(key) then
                            val (keyName, s1) = protoTypeName(key, state)
                            val (valName, s2) = protoTypeName(value, s1)
                            (s"map<$keyName, $valName> $name = $fieldNumber;", s2)
                        else
                            // proto3 forbids this key type in map<>. The codec encodes such a map as an
                            // array of [key, value] pairs, so describe it as a repeated entry message
                            // rather than emit an invalid map<...>.
                            val (keyName, s1) = protoTypeName(key, state)
                            val (valName, s2) = protoTypeName(value, s1)
                            val entryName     = s"${name.capitalize}Entry"
                            val entryMsg      = s"message $entryName {\n  $keyName key = 1;\n  $valName value = 2;\n}\n"
                            (s"repeated $entryName $name = $fieldNumber;", s2.copy(messages = entryMsg :: s2.messages))
                        end if

                    case p: Structure.Type.Product =>
                        val nextState = collect(p, state)
                        (s"${p.name} $name = $fieldNumber;", nextState)

                    case s: Structure.Type.Sum =>
                        val nextState = collect(s, state)
                        (s"${s.name} $name = $fieldNumber;", nextState)

                    case _: Structure.Type.Open =>
                        throw new IllegalArgumentException(
                            s"proto3 does not support open-shape field type in field '$name'"
                        )
                end match
            end protoFieldDecl

            def protoTypeName(tpe: Structure.Type, state: State): (String, State) =
                tpe match
                    case p: Structure.Type.Primitive =>
                        (primitiveProtoName(p.kind), state)

                    case Structure.Type.Optional(_, _, inner) =>
                        // Optional-as-type-name (inside a repeated/map context) is not valid in proto3
                        throw new IllegalArgumentException(
                            s"proto3 does not support Optional as an element type in a repeated or map field"
                        )

                    case Structure.Type.Collection(_, _, elem) =>
                        // Collection-as-type-name occurs when used inside another repeated/map: not valid in proto3
                        throw new IllegalArgumentException(
                            s"proto3 does not support nested repeated fields (List[List[_]] or map value List[_]): use a wrapper message instead"
                        )

                    case Structure.Type.Mapping(_, _, _, _) =>
                        // Mapping-as-type-name occurs when used as an element inside repeated/map: not valid in proto3
                        throw new IllegalArgumentException(
                            s"proto3 does not support nested Mapping as a type name (map value Map[_, _]): use a wrapper message instead"
                        )

                    case p: Structure.Type.Product =>
                        val nextState = collect(p, state)
                        (p.name, nextState)

                    case s: Structure.Type.Sum =>
                        val nextState = collect(s, state)
                        (s.name, nextState)

                    case _: Structure.Type.Open =>
                        throw new IllegalArgumentException(
                            s"proto3 does not support open-shape type in type name position"
                        )
                end match
            end protoTypeName

            /** Protobuf type name for a primitive kind.
              *
              * Note: proto3 does not define an arbitrary-precision numeric type. BigInt and BigDecimal are serialized as `string` to
              * preserve the exact value during round-trips. Bytes use proto3 `bytes`; Instant and Duration use signed 64-bit millisecond
              * counters, matching the codec writer.
              *
              * Note: proto3 has no Unit (void) scalar type. Unit-typed fields are rejected with an IllegalArgumentException, consistent
              * with the rejection of other incompatible shapes (nested Optional, nested Collection, etc.).
              */
            def primitiveProtoName(kind: Structure.PrimitiveKind): String =
                kind match
                    case Structure.PrimitiveKind.Int | Structure.PrimitiveKind.Short |
                        Structure.PrimitiveKind.Byte | Structure.PrimitiveKind.Char => "sint32"
                    case Structure.PrimitiveKind.Long                                        => "sint64"
                    case Structure.PrimitiveKind.Double                                      => "double"
                    case Structure.PrimitiveKind.Float                                       => "float"
                    case Structure.PrimitiveKind.String                                      => "string"
                    case Structure.PrimitiveKind.Boolean                                     => "bool"
                    case Structure.PrimitiveKind.BigInt | Structure.PrimitiveKind.BigDecimal => "string"
                    case Structure.PrimitiveKind.Bytes                                       => "bytes"
                    case Structure.PrimitiveKind.Instant | Structure.PrimitiveKind.Duration  => "sint64"
                    case Structure.PrimitiveKind.Unit =>
                        throw new IllegalArgumentException(
                            "Unit-typed fields are not supported in Protobuf; omit the field or wrap in Option[T]"
                        )
                end match
            end primitiveProtoName

            rt match
                case ps: (Structure.Type.Product | Structure.Type.Sum) =>
                    val finalState = collect(ps, State(Set.empty, Nil))
                    val sb         = new StringBuilder
                    sb.append("syntax = \"proto3\";\n\n")
                    finalState.messages.reverse.foreach { msg =>
                        sb.append(msg)
                        sb.append('\n')
                    }
                    sb.toString.trim
                case _: Structure.Type.Open =>
                    throw SchemaNotSerializableException(
                        "Protobuf.protoSchema does not support open-shape schemas (Schema[Structure.Value], " +
                            "Schema[Json.JsonSchema]); these accept arbitrary JSON which proto3 cannot encode " +
                            "without an escape hatch."
                    )
                case (_: Structure.Type.Primitive) | (_: Structure.Type.Collection) |
                    (_: Structure.Type.Optional) | (_: Structure.Type.Mapping) =>
                    throw new IllegalArgumentException(
                        s"Protobuf.protoSchema requires a case class or sealed trait as the top-level type, got: ${rt.name}"
                    )
            end match
        end fromStructure

    end ProtoSchema

end Protobuf
