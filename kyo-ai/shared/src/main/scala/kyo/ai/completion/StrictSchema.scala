package kyo.ai.completion

import kyo.*
import kyo.Json
import kyo.Json.JsonSchema
import kyo.ai.*

/** Shared result-schema transforms for the completion backends.
  *
  * [[result]] rewrites an arbitrary [[JsonSchema]] into the strict structured-output form (every object marks
  * all properties `required` and sets `additionalProperties: false`, nullable fields as `anyOf`), or reports
  * the path at which it is unsupported; the Anthropic thinking-disabled branch (the `Config.disableReasoning`
  * opt-out) uses it for `strict = true` grammar-constrained decoding. [[requireAll]] marks every object
  * property required while leaving the rest of the shape untouched: the ADVISORY result schema every backend
  * advertises, so the advisory result contract is identical across the four backends.
  */
private[completion] object StrictSchema:

    /** The strict-transformed schema, or the path at which the schema cannot be made strict-compatible. */
    def result(schema: JsonSchema, allowMaps: Boolean = true)(using Frame): Result[String, Structure.Value] =
        strictSchema(schema, "$", allowMaps)

    /** The strict-transformed schema, failing the effect with [[AIDecodeException]] on an unsupported shape. */
    def orAbort(schema: JsonSchema, allowMaps: Boolean = true)(using Frame): Structure.Value < Abort[AIGenException] =
        result(schema, allowMaps) match
            case Result.Success(value) => value
            case Result.Failure(err)   => Abort.fail(AIDecodeException(err))
            case Result.Panic(ex)      => Abort.panic(ex)

    /** Marks every object property required, recursively, leaving the rest of the shape untouched: unions stay
      * `oneOf`, objects stay open, nullable fields stay nullable (a required nullable property must be present
      * and may be null). The advisory result-schema shape shared by ALL FOUR backends: requiring the
      * envelope's reasoning-bearing fields is the one schema pressure with a measured output effect. On Claude
      * Code it is additionally the strongest expressible form, since the MCP tool metadata carries this typed
      * AST, which has no closed-object or `anyOf` form.
      */
    def requireAll(schema: JsonSchema): JsonSchema =
        schema match
            case JsonSchema.Obj(properties, _, additionalProperties, description, deprecated, examples) =>
                JsonSchema.Obj(
                    properties.map((name, s) => (name, requireAll(s))),
                    properties.map(_._1),
                    additionalProperties.map(requireAll),
                    description,
                    deprecated,
                    examples
                )
            case arr: JsonSchema.Arr        => arr.copy(items = requireAll(arr.items))
            case JsonSchema.Nullable(inner) => JsonSchema.Nullable(requireAll(inner))
            case JsonSchema.OneOf(variants) => JsonSchema.OneOf(variants.map((name, s) => (name, requireAll(s))))
            case other                      => other
    end requireAll

    private def strictSchema(schema: JsonSchema, path: String, allowMaps: Boolean)(using Frame): Result[String, Structure.Value] =
        schema match
            case JsonSchema.Obj(properties, _, additionalProperties, description, deprecated, examples) =>
                transformProperties(properties, path, allowMaps).flatMap { props =>
                    additionalProperties match
                        // A typed additionalProperties is an open map: OpenAI strict supports it, Anthropic
                        // strict does not (it requires additionalProperties:false), so allowMaps=false marks the
                        // schema non-strict-compatible and the caller falls back to the advisory schema.
                        case Present(ap) if allowMaps =>
                            strictSchema(ap, s"$path.*", allowMaps).map { apSchema =>
                                strictObjectSchema(props, Present(apSchema), description, deprecated, examples)
                            }
                        case Present(_) =>
                            Result.Failure(s"open map (typed additionalProperties) is not supported in strict mode at $path")
                        case Absent =>
                            Result.Success(strictObjectSchema(props, Absent, description, deprecated, examples))
                    end match
                }
            case JsonSchema.Arr(items, minItems, maxItems, uniqueItems, description) =>
                strictSchema(items, s"$path[]", allowMaps).map { itemSchema =>
                    Structure.Value.Record(
                        Chunk(
                            "type"  -> Structure.Value.Str("array"),
                            "items" -> itemSchema
                        ) ++
                            minItems.map(n => Chunk("minItems" -> Structure.Value.Integer(n))).getOrElse(Chunk.empty) ++
                            maxItems.map(n => Chunk("maxItems" -> Structure.Value.Integer(n))).getOrElse(Chunk.empty) ++
                            uniqueItems.map(b => Chunk("uniqueItems" -> Structure.Value.Bool(b))).getOrElse(Chunk.empty) ++
                            description.map(d => Chunk("description" -> Structure.Value.Str(d))).getOrElse(Chunk.empty)
                    )
                }
            case JsonSchema.Str(minLength, maxLength, pattern, format, description) =>
                Result.Success(Structure.Value.Record(
                    Chunk("type" -> Structure.Value.Str("string")) ++
                        minLength.map(n => Chunk("minLength" -> Structure.Value.Integer(n))).getOrElse(Chunk.empty) ++
                        maxLength.map(n => Chunk("maxLength" -> Structure.Value.Integer(n))).getOrElse(Chunk.empty) ++
                        pattern.map(p => Chunk("pattern" -> Structure.Value.Str(p))).getOrElse(Chunk.empty) ++
                        format.map(f => Chunk("format" -> Structure.Value.Str(f))).getOrElse(Chunk.empty) ++
                        description.map(d => Chunk("description" -> Structure.Value.Str(d))).getOrElse(Chunk.empty)
                ))
            case JsonSchema.Num(minimum, exclusiveMinimum, maximum, exclusiveMaximum, description) =>
                Result.Success(Structure.Value.Record(
                    Chunk("type" -> Structure.Value.Str("number")) ++
                        minimum.map(n => Chunk("minimum" -> Structure.Value.Decimal(n))).getOrElse(Chunk.empty) ++
                        exclusiveMinimum.map(n => Chunk("exclusiveMinimum" -> Structure.Value.Decimal(n))).getOrElse(Chunk.empty) ++
                        maximum.map(n => Chunk("maximum" -> Structure.Value.Decimal(n))).getOrElse(Chunk.empty) ++
                        exclusiveMaximum.map(n => Chunk("exclusiveMaximum" -> Structure.Value.Decimal(n))).getOrElse(Chunk.empty) ++
                        description.map(d => Chunk("description" -> Structure.Value.Str(d))).getOrElse(Chunk.empty)
                ))
            case JsonSchema.Integer(minimum, exclusiveMinimum, maximum, exclusiveMaximum, description) =>
                Result.Success(Structure.Value.Record(
                    Chunk("type" -> Structure.Value.Str("integer")) ++
                        minimum.map(n => Chunk("minimum" -> Structure.Value.Integer(n))).getOrElse(Chunk.empty) ++
                        exclusiveMinimum.map(n => Chunk("exclusiveMinimum" -> Structure.Value.Integer(n))).getOrElse(Chunk.empty) ++
                        maximum.map(n => Chunk("maximum" -> Structure.Value.Integer(n))).getOrElse(Chunk.empty) ++
                        exclusiveMaximum.map(n => Chunk("exclusiveMaximum" -> Structure.Value.Integer(n))).getOrElse(Chunk.empty) ++
                        description.map(d => Chunk("description" -> Structure.Value.Str(d))).getOrElse(Chunk.empty)
                ))
            case JsonSchema.Bool(description) =>
                Result.Success(Structure.Value.Record(
                    Chunk("type" -> Structure.Value.Str("boolean")) ++
                        description.map(d => Chunk("description" -> Structure.Value.Str(d))).getOrElse(Chunk.empty)
                ))
            case JsonSchema.Null(description) =>
                Result.Success(Structure.Value.Record(
                    Chunk("type" -> Structure.Value.Str("null")) ++
                        description.map(d => Chunk("description" -> Structure.Value.Str(d))).getOrElse(Chunk.empty)
                ))
            case JsonSchema.Nullable(inner) =>
                strictSchema(inner, path, allowMaps).map { innerSchema =>
                    anyOfSchema(Chunk(innerSchema, Structure.Value.Record(Chunk("type" -> Structure.Value.Str("null")))))
                }
            case JsonSchema.OneOf(variants) =>
                transformVariants(variants, path, allowMaps).map(anyOfSchema)
        end match
    end strictSchema

    private def strictObjectSchema(
        props: Chunk[(String, Structure.Value)],
        additionalProperties: Maybe[Structure.Value],
        description: Maybe[String],
        deprecated: Maybe[Boolean],
        examples: Chunk[Structure.Value]
    ): Structure.Value =
        val required = props.map(_._1)
        val entries =
            Chunk(
                "type"                 -> Structure.Value.Str("object"),
                "properties"           -> Structure.Value.Record(props),
                "required"             -> Structure.Value.Sequence(required.map(Structure.Value.Str(_))),
                "additionalProperties" -> additionalProperties.getOrElse(Structure.Value.Bool(false))
            ) ++
                description.map(d => Chunk("description" -> Structure.Value.Str(d))).getOrElse(Chunk.empty) ++
                deprecated.map(d => Chunk("deprecated" -> Structure.Value.Bool(d))).getOrElse(Chunk.empty) ++
                (if examples.nonEmpty then Chunk("examples" -> Structure.Value.Sequence(examples)) else Chunk.empty)
        Structure.Value.Record(entries)
    end strictObjectSchema

    private def transformProperties(properties: List[(String, JsonSchema)], path: String, allowMaps: Boolean)(using
        Frame
    ): Result[String, Chunk[(String, Structure.Value)]] =
        properties.foldLeft(Result.Success(Chunk.empty[(String, Structure.Value)]): Result[String, Chunk[(String, Structure.Value)]]) {
            case (Result.Success(acc), (name, schema)) =>
                strictSchema(schema, s"$path.$name", allowMaps).map(value => acc.append(name -> value))
            case (failure, _) => failure
        }
    end transformProperties

    private def transformVariants(variants: List[(String, JsonSchema)], path: String, allowMaps: Boolean)(using
        Frame
    ): Result[String, Chunk[Structure.Value]] =
        variants.foldLeft(Result.Success(Chunk.empty[Structure.Value]): Result[String, Chunk[Structure.Value]]) {
            case (Result.Success(acc), (name, schema)) =>
                strictSchema(schema, s"$path.$name", allowMaps).map(value =>
                    acc.append(objectSchema(Chunk(name -> value), Chunk(name)))
                )
            case (failure, _) => failure
        }
    end transformVariants

    def anyOfSchema(values: Chunk[Structure.Value]): Structure.Value =
        Structure.Value.Record(Chunk("anyOf" -> Structure.Value.Sequence(values)))

    def objectSchema(properties: Chunk[(String, Structure.Value)], required: Chunk[String]): Structure.Value =
        Structure.Value.Record(Chunk(
            "type"                 -> Structure.Value.Str("object"),
            "properties"           -> Structure.Value.Record(properties),
            "required"             -> Structure.Value.Sequence(required.map(Structure.Value.Str(_))),
            "additionalProperties" -> Structure.Value.Bool(false)
        ))
end StrictSchema
