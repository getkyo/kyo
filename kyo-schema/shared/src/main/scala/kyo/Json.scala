package kyo

import kyo.DecodeException
import kyo.Frame
import kyo.Result
import kyo.Schema
import kyo.Span

final class Json extends Codec:
    def newWriter(): Codec.Writer = kyo.internal.JsonWriter()
    def newReader(input: Span[Byte])(using Frame): Codec.Reader =
        kyo.internal.JsonReader(input)
end Json

/** Primary entry point for JSON serialization and schema generation.
  *
  * All methods are inline and require a `Schema[A]` instance in scope (typically provided by `Schema.derived` or an explicit given). The
  * underlying encoding follows standard JSON conventions: case classes become objects, sealed traits/enums become discriminated `oneOf`,
  * collections become arrays, and options/maybes become nullable values.
  *
  * @see
  *   [[kyo.Schema]] for the type-driven serialization model
  * @see
  *   [[kyo.Protobuf]] for binary Protocol Buffers serialization
  */
object Json:
    /** Default maximum nesting depth for objects/arrays in JSON decoding (DoS limit). */
    val DefaultMaxDepth: Int = 512

    /** Default maximum number of entries in any single collection or object in JSON decoding (DoS limit). */
    val DefaultMaxCollectionSize: Int = 100000

    given Json = Json()

    /** Encodes a value of type A to a JSON string.
      *
      * @param value
      *   the value to encode
      * @return
      *   the JSON string representation
      */
    inline def encode[A](value: A)(using schema: Schema[A], frame: Frame): String =
        val w = summon[Json].newWriter()
        schema.writeTo(value, w)
        new String(w.result().toArray, java.nio.charset.StandardCharsets.UTF_8)
    end encode

    /** Encodes a value of type A to raw UTF-8 JSON bytes.
      *
      * @param value
      *   the value to encode
      * @return
      *   the JSON bytes
      */
    inline def encodeBytes[A](value: A)(using schema: Schema[A], frame: Frame): Span[Byte] =
        val w = summon[Json].newWriter()
        schema.writeTo(value, w)
        w.result()
    end encodeBytes

    /** Decodes a JSON string into a value of type A.
      *
      * @param input
      *   the JSON string to decode
      * @return
      *   the decoded value, or a DecodeException if the input is malformed or does not match the schema
      */
    def decode[A](
        input: String,
        maxDepth: Int = DefaultMaxDepth,
        maxCollectionSize: Int = DefaultMaxCollectionSize
    )(using json: Json, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        val reader = json.newReader(Span.from(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        reader.resetLimits(maxDepth, maxCollectionSize)
        Result.catching[DecodeException](schema.readFrom(reader))
    end decode

    /** Decodes raw UTF-8 JSON bytes into a value of type A.
      *
      * @param input
      *   the raw UTF-8 JSON bytes to decode
      * @param maxDepth
      *   maximum nesting depth for objects/arrays (default `DefaultMaxDepth`)
      * @param maxCollectionSize
      *   maximum number of entries in maps, sets, or arrays (default `DefaultMaxCollectionSize`)
      * @return
      *   the decoded value, or a DecodeException if the input is malformed or does not match the schema
      */
    def decodeBytes[A](
        input: Span[Byte],
        maxDepth: Int = DefaultMaxDepth,
        maxCollectionSize: Int = DefaultMaxCollectionSize
    )(using json: Json, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        val reader = json.newReader(input)
        reader.resetLimits(maxDepth, maxCollectionSize)
        Result.catching[DecodeException](schema.readFrom(reader))
    end decodeBytes

    /** Generates a JSON Schema for type A at compile time.
      *
      * This overload does not require a Schema[A] in scope and does not incorporate runtime metadata such as validation constraints,
      * descriptions, or examples. Use the `Schema[A]`-enriched overload when those are needed.
      *
      * @tparam A
      *   the type to describe
      * @return
      *   a JsonSchema derived purely from the compile-time structure of A
      */
    inline def jsonSchema[A]: JsonSchema = JsonSchema.from[A]

    /** Generates a JSON Schema for type A, enriched with runtime Schema metadata.
      *
      * When a `Schema[A]` is in scope, the returned schema incorporates documentation, field descriptions, deprecation markers, examples,
      * validation constraints, dropped fields, and renamed fields registered on the Schema.
      *
      * @param schema
      *   the Schema[A] providing runtime metadata
      * @return
      *   a JsonSchema enriched with all metadata from the Schema
      */
    inline def jsonSchema[A](using schema: Schema[A]): JsonSchema =
        val base = JsonSchema.from[A]
        base match
            case obj: JsonSchema.Obj =>
                Schema.enrichObj(
                    obj,
                    schema.documentation,
                    schema.fieldDocs,
                    schema.fieldDeprecated,
                    if schema.examples.isEmpty then Chunk.empty
                    else schema.examples.map(e => schema.toStructureValue(e)),
                    schema.constraints,
                    schema.droppedFields,
                    schema.renamedFields.toMap
                )
            case other => other
        end match
    end jsonSchema

    /** JSON Schema representation generated from Scala types.
      *
      * Provides compile-time JSON Schema generation from case classes, sealed traits/enums, primitives, and container types. The schema
      * follows JSON Schema Draft 2020-12 conventions.
      */
    enum JsonSchema derives CanEqual, Schema:
        /** Object schema with named properties and required field list. */
        case Obj(
            properties: List[(String, JsonSchema)],
            required: List[String],
            additionalProperties: Maybe[JsonSchema] = Maybe.empty,
            description: Maybe[String] = Maybe.empty,
            deprecated: Maybe[Boolean] = Maybe.empty,
            examples: Chunk[Structure.Value] = Chunk.empty
        )

        /** Array schema with element type. */
        case Arr(
            items: JsonSchema,
            minItems: Maybe[Int] = Maybe.empty,
            maxItems: Maybe[Int] = Maybe.empty,
            uniqueItems: Maybe[Boolean] = Maybe.empty,
            description: Maybe[String] = Maybe.empty
        )

        /** String type. */
        case Str(
            minLength: Maybe[Int] = Maybe.empty,
            maxLength: Maybe[Int] = Maybe.empty,
            pattern: Maybe[String] = Maybe.empty,
            format: Maybe[String] = Maybe.empty,
            description: Maybe[String] = Maybe.empty
        )

        /** Numeric type (floating-point). */
        case Num(
            minimum: Maybe[Double] = Maybe.empty,
            exclusiveMinimum: Maybe[Double] = Maybe.empty,
            maximum: Maybe[Double] = Maybe.empty,
            exclusiveMaximum: Maybe[Double] = Maybe.empty,
            description: Maybe[String] = Maybe.empty
        )

        /** Integer type. */
        case Integer(
            minimum: Maybe[Long] = Maybe.empty,
            exclusiveMinimum: Maybe[Long] = Maybe.empty,
            maximum: Maybe[Long] = Maybe.empty,
            exclusiveMaximum: Maybe[Long] = Maybe.empty,
            description: Maybe[String] = Maybe.empty
        )

        /** Boolean type. */
        case Bool

        /** Null type (represents the JSON null value; maps to Unit in Scala). */
        case Null

        /** Nullable wrapper (JSON Schema `oneOf` with null). */
        case Nullable(inner: JsonSchema)

        /** Sum type represented as `oneOf` with discriminated variants. */
        case OneOf(variants: List[(String, JsonSchema)])
    end JsonSchema

    object JsonSchema:

        /** Generates a `JsonSchema` for type `A` at compile time.
          *
          * Supports:
          *   - Primitives: Int, Long, Short, Byte -> Integer; Double, Float -> Num; String -> Str; Boolean -> Bool
          *   - Case classes -> Obj with properties for each field
          *   - Sealed traits/enums -> OneOf with variant schemas
          *   - List, Vector, Set, Seq, Chunk -> Arr
          *   - Option[X] -> Nullable
          */
        inline def from[A]: JsonSchema = fromStructure(Structure.of[A])

        /** Derives a JsonSchema from a Structure.Type at runtime. */
        def fromStructure(rt: Structure.Type): JsonSchema =
            fromStructure(rt, Set.empty)

        private def fromStructure(rt: Structure.Type, seen: Set[String]): JsonSchema =
            rt match
                case p: Structure.Type.Primitive =>
                    p.kind match
                        case Structure.PrimitiveKind.Int | Structure.PrimitiveKind.Long |
                            Structure.PrimitiveKind.Short | Structure.PrimitiveKind.Byte |
                            Structure.PrimitiveKind.BigInt => Integer()
                        case Structure.PrimitiveKind.Double | Structure.PrimitiveKind.Float |
                            Structure.PrimitiveKind.BigDecimal => Num()
                        case Structure.PrimitiveKind.String | Structure.PrimitiveKind.Char => Str()
                        case Structure.PrimitiveKind.Boolean                               => Bool
                        case Structure.PrimitiveKind.Unit                                  => Null

                case Structure.Type.Optional(_, _, inner) =>
                    Nullable(fromStructure(inner, seen))

                case Structure.Type.Collection(_, _, elem) =>
                    Arr(fromStructure(elem, seen))

                case Structure.Type.Product(name, _, _, fields) =>
                    if seen.contains(name) then Obj(List.empty, List.empty)
                    else
                        val newSeen = seen + name
                        val properties = fields.toList.map { f =>
                            (f.name, fromStructure(f.fieldType, newSeen))
                        }
                        val required = fields.toList.collect {
                            case f if f.default.isEmpty && !f.optional => f.name
                        }
                        Obj(properties, required)

                case Structure.Type.Sum(name, _, _, variants, _) =>
                    if seen.contains(name) then Obj(List.empty, List.empty)
                    else
                        val newSeen = seen + name
                        val variantList = variants.toList.map { v =>
                            (v.name, fromStructure(v.variantType, newSeen))
                        }
                        OneOf(variantList)

                case Structure.Type.Mapping(_, _, keyType, valueType) =>
                    // JSON Schema represents Map[String, V] as an object with `additionalProperties`.
                    // For non-String keys (encoded as array-of-pairs), emit a plain empty Obj.
                    keyType match
                        case p: Structure.Type.Primitive
                            if p.kind == Structure.PrimitiveKind.String || p.kind == Structure.PrimitiveKind.Char =>
                            Obj(
                                properties = List.empty,
                                required = List.empty,
                                additionalProperties = Maybe(fromStructure(valueType, seen))
                            )
                        case _ =>
                            Obj(List.empty, List.empty)
        end fromStructure
    end JsonSchema

end Json
