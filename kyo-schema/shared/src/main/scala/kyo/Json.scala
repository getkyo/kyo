package kyo

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

    /** Generates a JSON Schema for type A, enriched with runtime Schema metadata.
      *
      * Requires a `Schema[A]` in scope. The returned schema incorporates documentation, field descriptions, deprecation markers, examples,
      * validation constraints, dropped fields, and renamed fields registered on the Schema.
      *
      * @param schema
      *   the Schema[A] providing runtime metadata and structure
      * @return
      *   a JsonSchema derived from the Schema's structure and enriched with all metadata
      */
    inline def jsonSchema[A](using schema: Schema[A]): JsonSchema =
        val base = JsonSchema.fromStructure(schema.structure)
        base match
            case obj: JsonSchema.Obj =>
                Json.enrichJsonSchemaObj(
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

    import scala.annotation.publicInBinary
    @publicInBinary private[kyo] def enrichJsonSchemaObj(
        obj: JsonSchema.Obj,
        doc: Maybe[String],
        fieldDocs: Map[Seq[String], String],
        fieldDeprecated: Map[Seq[String], String],
        examples: Chunk[Structure.Value],
        constraints: Seq[Schema.Constraint],
        droppedFields: Set[String],
        renamedFields: Map[String, String]
    ): JsonSchema.Obj =
        kyo.internal.JsonSchemaEnricher.enrichObj(
            obj,
            doc,
            fieldDocs,
            fieldDeprecated,
            examples,
            constraints,
            droppedFields,
            renamedFields
        )
    end enrichJsonSchemaObj

    /** JSON Schema representation generated from Scala types.
      *
      * Provides compile-time JSON Schema generation from case classes, sealed traits/enums, primitives, and container types. The schema
      * follows JSON Schema Draft 2020-12 conventions.
      */
    enum JsonSchema derives CanEqual:
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

        /** Schema for [[JsonSchema]] emitting standard JSON Schema Draft 2020-12 wire shape.
          *
          * The auto-derived sealed-trait Schema would emit kyo-schema's tagged-union form (`{"Obj":{...}}`,
          * `{"Str":{...}}`), which is not what the JSON Schema spec, MCP protocol, or any external consumer expects. This explicit
          * Schema emits `{"type":"object","properties":{...},"required":[...]}` and the symmetric primitive shapes, and on read
          * dispatches on the `type` field (object/array/string/number/integer/boolean/null) or recognises `oneOf` for unions.
          */
        given jsonSchemaSchema: Schema[JsonSchema] =
            import scala.annotation.publicInBinary
            new Schema[JsonSchema](Seq.empty):
                @publicInBinary private[kyo] def serializeWrite(value: JsonSchema, writer: Codec.Writer): Unit =
                    writeJsonSchema(value, writer)
                @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): JsonSchema =
                    readJsonSchema(reader)
                @publicInBinary private[kyo] def getter(value: JsonSchema): Maybe[Any] = Maybe(value)
                @publicInBinary private[kyo] def setter(value: JsonSchema, next: Any): JsonSchema =
                    next match
                        case sv: JsonSchema => sv
                        case _              => value
                private lazy val _structure: Structure.Type =
                    Structure.Type.Open(Tag[JsonSchema].asInstanceOf[Tag[Any]])
                override def structure: Structure.Type = _structure
            end new
        end jsonSchemaSchema

        private def writeJsonSchema(value: JsonSchema, writer: Codec.Writer): Unit =
            import scala.collection.mutable.ArrayBuffer
            // Build the (name, value) entries up-front so we can pass the exact size to objectStart and Maybe-elide
            // absent fields without re-walking the case class.
            val entries: ArrayBuffer[(String, () => Unit)] = ArrayBuffer.empty
            value match
                case obj: Obj =>
                    discard(entries.addOne("type" -> (() => writer.string("object"))))
                    // Always emit `properties` (even empty) so the wire shape matches the JSON-Schema
                    // record validators (e.g. MCP elicitation/sampling hosts) that expect the field to
                    // exist on every `type: object`. Omitting it on empty objects produces `{"type":"object"}`
                    // which strict Zod-style record schemas reject as `properties: undefined`.
                    discard(entries.addOne("properties" -> { () =>
                        writer.objectStart("", obj.properties.size)
                        obj.properties.foreach { (name, sub) =>
                            writer.fieldBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0)
                            writeJsonSchema(sub, writer)
                        }
                        writer.objectEnd()
                    }))
                    if obj.required.nonEmpty then
                        discard(entries.addOne("required" -> { () =>
                            writer.arrayStart(obj.required.size)
                            obj.required.foreach(writer.string)
                            writer.arrayEnd()
                        }))
                    end if
                    obj.additionalProperties match
                        case Maybe.Present(ap) =>
                            discard(entries.addOne("additionalProperties" -> (() => writeJsonSchema(ap, writer))))
                        case _ => ()
                    end match
                    obj.description match
                        case Maybe.Present(d) => discard(entries.addOne("description" -> (() => writer.string(d))))
                        case _                => ()
                    obj.deprecated match
                        case Maybe.Present(d) => discard(entries.addOne("deprecated" -> (() => writer.boolean(d))))
                        case _                => ()
                    if obj.examples.nonEmpty then
                        discard(entries.addOne("examples" -> { () =>
                            writer.arrayStart(obj.examples.size)
                            obj.examples.foreach(ex => Schema.writeStructureValue(writer, ex))
                            writer.arrayEnd()
                        }))
                    end if
                case arr: Arr =>
                    discard(entries.addOne("type" -> (() => writer.string("array"))))
                    discard(entries.addOne("items" -> (() => writeJsonSchema(arr.items, writer))))
                    arr.minItems match
                        case Maybe.Present(n) => discard(entries.addOne("minItems" -> (() => writer.int(n))))
                        case _                => ()
                    arr.maxItems match
                        case Maybe.Present(n) => discard(entries.addOne("maxItems" -> (() => writer.int(n))))
                        case _                => ()
                    arr.uniqueItems match
                        case Maybe.Present(b) => discard(entries.addOne("uniqueItems" -> (() => writer.boolean(b))))
                        case _                => ()
                    arr.description match
                        case Maybe.Present(d) => discard(entries.addOne("description" -> (() => writer.string(d))))
                        case _                => ()
                case str: Str =>
                    discard(entries.addOne("type" -> (() => writer.string("string"))))
                    str.minLength match
                        case Maybe.Present(n) => discard(entries.addOne("minLength" -> (() => writer.int(n))))
                        case _                => ()
                    str.maxLength match
                        case Maybe.Present(n) => discard(entries.addOne("maxLength" -> (() => writer.int(n))))
                        case _                => ()
                    str.pattern match
                        case Maybe.Present(p) => discard(entries.addOne("pattern" -> (() => writer.string(p))))
                        case _                => ()
                    str.format match
                        case Maybe.Present(f) => discard(entries.addOne("format" -> (() => writer.string(f))))
                        case _                => ()
                    str.description match
                        case Maybe.Present(d) => discard(entries.addOne("description" -> (() => writer.string(d))))
                        case _                => ()
                case num: Num =>
                    discard(entries.addOne("type" -> (() => writer.string("number"))))
                    num.minimum match
                        case Maybe.Present(n) => discard(entries.addOne("minimum" -> (() => writer.double(n))))
                        case _                => ()
                    num.exclusiveMinimum match
                        case Maybe.Present(n) => discard(entries.addOne("exclusiveMinimum" -> (() => writer.double(n))))
                        case _                => ()
                    num.maximum match
                        case Maybe.Present(n) => discard(entries.addOne("maximum" -> (() => writer.double(n))))
                        case _                => ()
                    num.exclusiveMaximum match
                        case Maybe.Present(n) => discard(entries.addOne("exclusiveMaximum" -> (() => writer.double(n))))
                        case _                => ()
                    num.description match
                        case Maybe.Present(d) => discard(entries.addOne("description" -> (() => writer.string(d))))
                        case _                => ()
                case i: Integer =>
                    discard(entries.addOne("type" -> (() => writer.string("integer"))))
                    i.minimum match
                        case Maybe.Present(n) => discard(entries.addOne("minimum" -> (() => writer.long(n))))
                        case _                => ()
                    i.exclusiveMinimum match
                        case Maybe.Present(n) => discard(entries.addOne("exclusiveMinimum" -> (() => writer.long(n))))
                        case _                => ()
                    i.maximum match
                        case Maybe.Present(n) => discard(entries.addOne("maximum" -> (() => writer.long(n))))
                        case _                => ()
                    i.exclusiveMaximum match
                        case Maybe.Present(n) => discard(entries.addOne("exclusiveMaximum" -> (() => writer.long(n))))
                        case _                => ()
                    i.description match
                        case Maybe.Present(d) => discard(entries.addOne("description" -> (() => writer.string(d))))
                        case _                => ()
                case Bool =>
                    discard(entries.addOne("type" -> (() => writer.string("boolean"))))
                case Null =>
                    discard(entries.addOne("type" -> (() => writer.string("null"))))
                case Nullable(inner) =>
                    // JSON Schema convention: nullable as oneOf with the inner schema and the null type.
                    discard(entries.addOne("oneOf" -> { () =>
                        writer.arrayStart(2)
                        writeJsonSchema(inner, writer)
                        writeJsonSchema(Null, writer)
                        writer.arrayEnd()
                    }))
                case OneOf(variants) =>
                    discard(entries.addOne("oneOf" -> { () =>
                        writer.arrayStart(variants.size)
                        variants.foreach { (name, sub) =>
                            // Variant schemas wrap each sub in a single-property object whose key is the variant name; this
                            // matches MCP's discriminated-union convention for tool / prompt argument schemas.
                            writer.objectStart("", 1)
                            writer.fieldBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0)
                            writeJsonSchema(sub, writer)
                            writer.objectEnd()
                        }
                        writer.arrayEnd()
                    }))
            end match
            writer.objectStart("", entries.size)
            entries.foreach { (name, emit) =>
                writer.fieldBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0)
                emit()
            }
            writer.objectEnd()
        end writeJsonSchema

        private def readJsonSchema(reader: Codec.Reader): JsonSchema =
            // Capture the raw value tree, then route by the `type` discriminator (or `oneOf`). Avoids juggling Reader cursor
            // state across the variant-specific branches.
            val sv = reader match
                case ir: Codec.IntrospectingReader => ir.readStructure()
                case other =>
                    throw SchemaNotSerializableException(
                        s"Schema[Json.JsonSchema] requires a self-describing reader (JSON or YAML); got ${other.getClass.getSimpleName}"
                    )(using reader.frame)
            fromStructureValue(sv)
        end readJsonSchema

        private def fromStructureValue(sv: Structure.Value): JsonSchema =
            sv match
                case Structure.Value.Record(fields) =>
                    val byName = fields.iterator.toMap
                    byName.get("type") match
                        case Some(Structure.Value.Str("object"))  => fromObj(byName)
                        case Some(Structure.Value.Str("array"))   => fromArr(byName)
                        case Some(Structure.Value.Str("string"))  => fromStr(byName)
                        case Some(Structure.Value.Str("number"))  => fromNum(byName)
                        case Some(Structure.Value.Str("integer")) => fromInteger(byName)
                        case Some(Structure.Value.Str("boolean")) => Bool
                        case Some(Structure.Value.Str("null"))    => Null
                        case _ =>
                            byName.get("oneOf") match
                                case Some(Structure.Value.Sequence(elems)) => fromOneOf(elems)
                                case _                                     =>
                                    // Treat untyped records as opaque Obj with no declared properties; downstream code may
                                    // populate properties via `additionalProperties` or treat as Any.
                                    Obj(properties = List.empty, required = List.empty)
                            end match
                    end match
                case other =>
                    throw TypeMismatchException(Seq.empty, "JsonSchema record", other.toString)(using Frame.internal)
        end fromStructureValue

        private def fromObj(byName: Map[String, Structure.Value]): Obj =
            val properties = byName.get("properties") match
                case Some(Structure.Value.Record(props)) =>
                    props.iterator.map { case (name, sub) =>
                        (name, fromStructureValue(sub))
                    }.toList
                case _ => List.empty[(String, JsonSchema)]
            val required = byName.get("required") match
                case Some(Structure.Value.Sequence(elems)) =>
                    elems.iterator.collect { case Structure.Value.Str(s) => s }.toList
                case _ => List.empty[String]
            val additionalProperties = byName.get("additionalProperties") match
                case Some(sv: Structure.Value) => Maybe(fromStructureValue(sv))
                case _                         => Maybe.empty
            val description = byName.get("description") match
                case Some(Structure.Value.Str(s)) => Maybe(s)
                case _                            => Maybe.empty
            val deprecated = byName.get("deprecated") match
                case Some(Structure.Value.Bool(b)) => Maybe(b)
                case _                             => Maybe.empty
            val examples = byName.get("examples") match
                case Some(Structure.Value.Sequence(elems)) => Chunk.from(elems.iterator.toSeq)
                case _                                     => Chunk.empty[Structure.Value]
            Obj(properties, required, additionalProperties, description, deprecated, examples)
        end fromObj

        private def fromArr(byName: Map[String, Structure.Value]): Arr =
            val items = byName.get("items") match
                case Some(sv: Structure.Value) => fromStructureValue(sv)
                case _                         => Obj(List.empty, List.empty)
            val minItems = byName.get("minItems") match
                case Some(Structure.Value.Integer(n)) => Maybe(n.toInt)
                case _                                => Maybe.empty
            val maxItems = byName.get("maxItems") match
                case Some(Structure.Value.Integer(n)) => Maybe(n.toInt)
                case _                                => Maybe.empty
            val uniqueItems = byName.get("uniqueItems") match
                case Some(Structure.Value.Bool(b)) => Maybe(b)
                case _                             => Maybe.empty
            val description = byName.get("description") match
                case Some(Structure.Value.Str(s)) => Maybe(s)
                case _                            => Maybe.empty
            Arr(items, minItems, maxItems, uniqueItems, description)
        end fromArr

        private def fromStr(byName: Map[String, Structure.Value]): Str =
            val minLength = byName.get("minLength") match
                case Some(Structure.Value.Integer(n)) => Maybe(n.toInt)
                case _                                => Maybe.empty
            val maxLength = byName.get("maxLength") match
                case Some(Structure.Value.Integer(n)) => Maybe(n.toInt)
                case _                                => Maybe.empty
            val pattern = byName.get("pattern") match
                case Some(Structure.Value.Str(s)) => Maybe(s)
                case _                            => Maybe.empty
            val format = byName.get("format") match
                case Some(Structure.Value.Str(s)) => Maybe(s)
                case _                            => Maybe.empty
            val description = byName.get("description") match
                case Some(Structure.Value.Str(s)) => Maybe(s)
                case _                            => Maybe.empty
            Str(minLength, maxLength, pattern, format, description)
        end fromStr

        private def numericMaybe(sv: Option[Structure.Value]): Maybe[Double] =
            sv match
                case Some(Structure.Value.Decimal(d)) => Maybe(d)
                case Some(Structure.Value.Integer(n)) => Maybe(n.toDouble)
                case _                                => Maybe.empty

        private def integerMaybe(sv: Option[Structure.Value]): Maybe[Long] =
            sv match
                case Some(Structure.Value.Integer(n)) => Maybe(n)
                case Some(Structure.Value.Decimal(d)) => Maybe(d.toLong)
                case _                                => Maybe.empty

        private def fromNum(byName: Map[String, Structure.Value]): Num =
            val description = byName.get("description") match
                case Some(Structure.Value.Str(s)) => Maybe(s)
                case _                            => Maybe.empty
            Num(
                numericMaybe(byName.get("minimum")),
                numericMaybe(byName.get("exclusiveMinimum")),
                numericMaybe(byName.get("maximum")),
                numericMaybe(byName.get("exclusiveMaximum")),
                description
            )
        end fromNum

        private def fromInteger(byName: Map[String, Structure.Value]): Integer =
            val description = byName.get("description") match
                case Some(Structure.Value.Str(s)) => Maybe(s)
                case _                            => Maybe.empty
            Integer(
                integerMaybe(byName.get("minimum")),
                integerMaybe(byName.get("exclusiveMinimum")),
                integerMaybe(byName.get("maximum")),
                integerMaybe(byName.get("exclusiveMaximum")),
                description
            )
        end fromInteger

        private def fromOneOf(elems: Chunk[Structure.Value]): JsonSchema =
            // Determine whether the elements are named-variant wrappers (MCP discriminated-union style) or direct
            // sub-schemas (Nullable style: `[{inner}, {"type":"null"}]`).
            // A named-variant wrapper has exactly one field whose value is itself a Record (another JSON Schema object).
            // A direct sub-schema is a Record that carries a "type" or "oneOf" key as its own descriptor.
            val isNamedVariantWrapper: Structure.Value => Boolean = {
                case Structure.Value.Record(fields) if fields.size == 1 =>
                    fields.head._2.isInstanceOf[Structure.Value.Record]
                case _ => false
            }
            val allNamedVariants = elems.iterator.forall(isNamedVariantWrapper)
            if allNamedVariants && elems.nonEmpty then
                val variants = elems.iterator.map {
                    case Structure.Value.Record(fields) =>
                        val (name, sub) = fields.head
                        name -> fromStructureValue(sub)
                    case other => throw TypeMismatchException(Seq.empty, "JsonSchema variant wrapper", other.toString)(using Frame.internal)
                }.toList
                OneOf(variants)
            else
                // Direct sub-schemas: detect the Nullable pattern (exactly 2 elements, one of which is the null type).
                val isNullSchema: Structure.Value => Boolean = {
                    case Structure.Value.Record(fields) =>
                        fields.iterator.exists { case ("type", Structure.Value.Str("null")) => true; case _ => false }
                    case _ => false
                }
                val nonNullElems = elems.iterator.filterNot(isNullSchema).toList
                val hasNull      = elems.iterator.exists(isNullSchema)
                if hasNull && nonNullElems.lengthCompare(1) == 0 then
                    Nullable(fromStructureValue(nonNullElems.head))
                else
                    // Fall back to numbering unnamed variants so callers see N variants of the right shape.
                    val variants = elems.iterator.zipWithIndex.map { (sv, i) =>
                        s"variant$i" -> fromStructureValue(sv)
                    }.toList
                    OneOf(variants)
                end if
            end if
        end fromOneOf

        /** Generates a `JsonSchema` for type `A` at compile time.
          *
          * Supports:
          *   - Primitives: Int, Long, Short, Byte -> Integer; Double, Float -> Num; String -> Str; Boolean -> Bool
          *   - Case classes -> Obj with properties for each field
          *   - Sealed traits/enums -> OneOf with variant schemas
          *   - List, Vector, Set, Seq, Chunk -> Arr
          *   - Option[X] -> Nullable
          */
        inline def from[A](using s: Schema[A]): JsonSchema = fromStructure(s.structure)

        /** Derives a JsonSchema from a Structure.Type at runtime. */
        private[kyo] def fromStructure(rt: Structure.Type): JsonSchema =
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
                        // Unit maps to an empty object on the wire (see `Schema.unitSchema`). Describing it as
                        // `{"type":"null"}` here would mismatch the actual wire shape AND violate consumers
                        // that require an object-typed schema (e.g. MCP tool `inputSchema`).
                        case Structure.PrimitiveKind.Unit => Obj(List.empty, List.empty)

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

                case _: Structure.Type.Open =>
                    // The carrying Schema accepts arbitrary JSON; describe it as the JSON Schema
                    // "any object" shape. This is byte-identical to the Unit arm above by design:
                    // both empty-properties Obj renderings are the Draft 2020-12 encoding of
                    // "no constraints". Downstream Scala consumers distinguish via the structure
                    // tree's variant, not via the wire bytes.
                    Obj(List.empty, List.empty)
        end fromStructure
    end JsonSchema

end Json
