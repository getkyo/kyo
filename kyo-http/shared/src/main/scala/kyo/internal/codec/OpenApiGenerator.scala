package kyo.internal.codec

import kyo.*
import kyo.Json.JsonSchema
import scala.collection.mutable

/** Generates an OpenAPI 3.1 specification from HttpHandler definitions.
  *
  * The version is 3.1 because the schemas carry JSON Schema 2020-12 keywords that 3.0 rejects: numeric `exclusiveMinimum` and
  * `exclusiveMaximum`, and `type: "null"`.
  */
private[kyo] object OpenApiGenerator:

    case class Config(
        title: String = "API",
        version: String = "1.0.0",
        description: Option[String] = None,
        securitySchemes: Map[String, HttpOpenApi.SecurityScheme] = Map.empty
    )

    /** The specification, or the [[kyo.HttpRouteException]] naming a route whose security requirement names a scheme `config` does not
      * declare.
      */
    def generate(handlers: Seq[HttpHandler[?, ?, ?]], config: Config = Config())(using Frame): Result[HttpRouteException, HttpOpenApi] =
        val undeclared = handlers.view.flatMap { h =>
            h.route.metadata.security.toOption.filterNot(config.securitySchemes.contains).map((h, _))
        }.headOption
        Maybe.fromOption(undeclared) match
            case Present((h, scheme)) =>
                Result.fail(HttpRouteException(
                    s"${h.route.method.name} ${h.route.request.path.show}",
                    s"its OpenAPI security scheme '$scheme' is not declared in the securitySchemes of HttpServerConfig.openApi"
                ))
            case Absent =>
                Result.succeed(HttpOpenApi(
                    openapi = "3.1.0",
                    info = HttpOpenApi.Info(
                        title = config.title,
                        version = config.version,
                        description = config.description
                    ),
                    paths = buildPaths(handlers),
                    components =
                        if config.securitySchemes.isEmpty then None
                        else Some(HttpOpenApi.Components(schemas = None, securitySchemes = Some(config.securitySchemes)))
                ))
        end match
    end generate

    private def buildPaths(handlers: Seq[HttpHandler[?, ?, ?]]): Map[String, HttpOpenApi.PathItem] =
        handlers.groupBy(h => pathToHttpOpenApi(h.route.request.path)).map { case (path, handlers) =>
            path -> buildPathItem(handlers)
        }

    private def buildPathItem(handlers: Seq[HttpHandler[?, ?, ?]]): HttpOpenApi.PathItem =
        val ops = handlers.map(h => h.route.method -> buildOperation(h.route)).toMap
        HttpOpenApi.PathItem(
            get = ops.get(HttpMethod.GET),
            post = ops.get(HttpMethod.POST),
            put = ops.get(HttpMethod.PUT),
            delete = ops.get(HttpMethod.DELETE),
            patch = ops.get(HttpMethod.PATCH),
            head = ops.get(HttpMethod.HEAD),
            options = ops.get(HttpMethod.OPTIONS)
        )
    end buildPathItem

    private def buildOperation(route: HttpRoute[?, ?, ?]): HttpOpenApi.Operation =
        val meta        = route.metadata
        val parameters  = buildParameters(route)
        val requestBody = buildRequestBody(route)
        val responses   = buildResponses(route)

        HttpOpenApi.Operation(
            tags = if meta.tags.isEmpty then None else Some(meta.tags.toList),
            summary = meta.summary.toOption,
            description = meta.description.toOption,
            operationId = meta.operationId.toOption,
            deprecated = if meta.deprecated then Some(true) else None,
            parameters = if parameters.isEmpty then None else Some(parameters.toList),
            requestBody = requestBody,
            responses = responses,
            security = meta.security.toOption.map(scheme => List(Map(scheme -> List.empty[String])))
        )
    end buildOperation

    private def buildParameters(route: HttpRoute[?, ?, ?]): Seq[HttpOpenApi.Parameter] =
        val pathParams = extractPathParams(route.request.path)

        val fieldParams = route.request.fields.toSeq.collect {
            case p: HttpRoute.Field.Param[?, ?, ?] if p.kind == HttpRoute.Field.Param.Location.Query =>
                HttpOpenApi.Parameter(
                    name = p.fieldName,
                    in = "query",
                    required = if !p.optional && p.default.isEmpty then Some(true) else None,
                    schema = inferCodecSchema(p.codec),
                    description = if p.description.nonEmpty then Some(p.description) else None
                )
            case p: HttpRoute.Field.Param[?, ?, ?] if p.kind == HttpRoute.Field.Param.Location.Header =>
                HttpOpenApi.Parameter(
                    name = p.fieldName,
                    in = "header",
                    required = if !p.optional && p.default.isEmpty then Some(true) else None,
                    schema = inferCodecSchema(p.codec),
                    description = if p.description.nonEmpty then Some(p.description) else None
                )
            case p: HttpRoute.Field.Param[?, ?, ?] if p.kind == HttpRoute.Field.Param.Location.Cookie =>
                HttpOpenApi.Parameter(
                    name = p.fieldName,
                    in = "cookie",
                    required = if !p.optional && p.default.isEmpty then Some(true) else None,
                    schema = inferCodecSchema(p.codec),
                    description = if p.description.nonEmpty then Some(p.description) else None
                )
        }

        pathParams ++ fieldParams
    end buildParameters

    private def extractPathParams(path: HttpPath[?]): Seq[HttpOpenApi.Parameter] =
        path match
            case HttpPath.Literal(_)                          => Seq.empty
            case HttpPath.Capture(fieldName, wireName, codec) =>
                val name   = if wireName.nonEmpty then wireName else fieldName
                val schema = inferCodecSchema(codec)
                Seq(HttpOpenApi.Parameter(
                    name = name,
                    in = "path",
                    required = Some(true),
                    schema = schema,
                    // A path capture has no route-level description the way a query
                    // param does, so the codec's is the only one available.
                    description = schema.description
                ))
            case HttpPath.Concat(left, right) =>
                extractPathParams(left) ++ extractPathParams(right)
            case HttpPath.Rest(fieldName) =>
                Seq(HttpOpenApi.Parameter(
                    name = fieldName,
                    in = "path",
                    required = Some(true),
                    schema = HttpOpenApi.SchemaObject.string,
                    description = None
                ))
    end extractPathParams

    /** The codec's own schema when it declares one, otherwise inferred.
      *
      * Probing can only recover a JSON *type*, which is all there is to learn
      * from a `HttpCodec[String]`. A codec that accepts a constrained subset
      * (built with `HttpCodec.pattern`, say) knows its `pattern` and description,
      * and that is strictly better than a guess.
      */
    private def inferCodecSchema(codec: HttpCodec[?]): HttpOpenApi.SchemaObject =
        codec.schema.getOrElse(inferredCodecSchema(codec))

    /** Infer OpenAPI schema type from HttpCodec by probing with sample values. */
    private def inferredCodecSchema(codec: HttpCodec[?]): HttpOpenApi.SchemaObject =
        def tryProbe(input: String)(pf: PartialFunction[Any, HttpOpenApi.SchemaObject]): Maybe[HttpOpenApi.SchemaObject] =
            codec.decode(input).toMaybe.flatMap(result => if pf.isDefinedAt(result) then Present(pf(result)) else kyo.Absent)

        tryProbe("1") {
            case _: Int     => HttpOpenApi.SchemaObject.integer
            case _: Long    => HttpOpenApi.SchemaObject.long
            case _: Boolean => HttpOpenApi.SchemaObject.boolean
        }.orElse(tryProbe("true") {
            case _: Boolean => HttpOpenApi.SchemaObject.boolean
        }).orElse(tryProbe("1.0") {
            case _: Double => HttpOpenApi.SchemaObject.number
            case _: Float  => HttpOpenApi.SchemaObject.number
        }).orElse(tryProbe("00000000-0000-0000-0000-000000000000") {
            case _: java.util.UUID =>
                HttpOpenApi.SchemaObject(
                    `type` = Some("string"),
                    format = Some("uuid"),
                    items = None,
                    properties = None,
                    required = None,
                    additionalProperties = None,
                    oneOf = None,
                    `enum` = None,
                    `$ref` = None
                )
        }).getOrElse(HttpOpenApi.SchemaObject.string)
    end inferredCodecSchema

    private def buildRequestBody(route: HttpRoute[?, ?, ?]): Option[HttpOpenApi.RequestBody] =
        route.request.fields.toSeq.collectFirst {
            case b: HttpRoute.Field.Body[?, ?] =>
                contentToMediaType(b.contentType).map { case (ct, schema) =>
                    HttpOpenApi.RequestBody(
                        required = Some(true),
                        content = Map(ct -> HttpOpenApi.MediaType(schema = schema)),
                        description = if b.description.nonEmpty then Some(b.description) else None
                    )
                }
        }.flatten
    end buildRequestBody

    private def buildResponses(route: HttpRoute[?, ?, ?]): Map[String, HttpOpenApi.Response] =
        val successStatus = route.response.status.code.toString

        val successResponse = route.response.fields.toSeq.collectFirst {
            case b: HttpRoute.Field.Body[?, ?] =>
                contentToMediaType(b.contentType).map { case (ct, schema) =>
                    HttpOpenApi.Response(
                        description = "Success",
                        content = Some(Map(ct -> HttpOpenApi.MediaType(schema = schema)))
                    )
                }
        }.flatten.getOrElse(
            HttpOpenApi.Response(description = "Success", content = None)
        )

        val errorResponses = route.response.errors.toSeq.map { mapping =>
            mapping.status.code.toString -> HttpOpenApi.Response(
                description = "Error",
                content = Some(Map("application/json" -> HttpOpenApi.MediaType(
                    schema = jsonSchemaToHttpOpenApi(mapping.jsonSchema)
                )))
            )
        }

        Map(successStatus -> successResponse) ++ errorResponses
    end buildResponses

    private def contentToMediaType(content: HttpRoute.ContentType[?]): Option[(String, HttpOpenApi.SchemaObject)] =
        content match
            case j: HttpRoute.ContentType.Json[?]      => Some("application/json" -> jsonSchemaToHttpOpenApi(j.jsonSchema))
            case HttpRoute.ContentType.Text            => Some("text/plain" -> HttpOpenApi.SchemaObject.string)
            case HttpRoute.ContentType.Binary          => Some("application/octet-stream" -> HttpOpenApi.SchemaObject.string)
            case HttpRoute.ContentType.ByteStream      => Some("application/octet-stream" -> HttpOpenApi.SchemaObject.string)
            case HttpRoute.ContentType.Multipart       => Some("multipart/form-data" -> HttpOpenApi.SchemaObject.obj)
            case HttpRoute.ContentType.MultipartStream => Some("multipart/form-data" -> HttpOpenApi.SchemaObject.obj)
            case n: HttpRoute.ContentType.Ndjson[?]    => Some("application/x-ndjson" -> jsonSchemaToHttpOpenApi(n.jsonSchema))
            case s: HttpRoute.ContentType.Sse[?]       => Some("text/event-stream" -> jsonSchemaToHttpOpenApi(s.jsonSchema))
            case HttpRoute.ContentType.SseText(_)      => Some("text/event-stream" -> HttpOpenApi.SchemaObject.string)
            case HttpRoute.ContentType.Form(_)         => Some("application/x-www-form-urlencoded" -> HttpOpenApi.SchemaObject.obj)

    private def pathToHttpOpenApi(path: HttpPath[?]): String =
        path match
            case HttpPath.Literal(value) =>
                if value.startsWith("/") then value else "/" + value
            case HttpPath.Capture(fieldName, wireName, _) =>
                val name = if wireName.nonEmpty then wireName else fieldName
                s"/{$name}"
            case HttpPath.Concat(left, right) =>
                pathToHttpOpenApi(left) + pathToHttpOpenApi(right)
            case HttpPath.Rest(fieldName) => s"/{$fieldName}"

    // ==================== JsonSchema introspection ====================

    /** Translates a kyo-schema JsonSchema ADT node into an OpenAPI SchemaObject.
      *
      * Mechanical translation:
      *   - Obj(properties, required, additionalProperties) → type=object + properties + required + additionalProperties.
      *   - Arr(items) → SchemaObject.array(items).
      *   - Str/Num/Integer/Bool → primitive helpers.
      *   - Nullable(inner) → recurse on inner (optionality is captured in the parent's required list), keeping its description.
      *   - OneOf(variants) → if all variants map to an empty Obj, emit enum with variant names; otherwise emit oneOf; either with its
      *     description.
      */
    private[kyo] def jsonSchemaToHttpOpenApi(js: JsonSchema): HttpOpenApi.SchemaObject =
        js match
            case JsonSchema.Obj(properties, required, additionalProperties, description, _, _) =>
                val propsMap =
                    if properties.isEmpty then None
                    else
                        val buf = mutable.LinkedHashMap[String, HttpOpenApi.SchemaObject]()
                        properties.foreach { case (name, sub) => buf(name) = jsonSchemaToHttpOpenApi(sub) }
                        Some(buf.toMap)
                val addProps = additionalProperties match
                    case Present(inner) => Some(jsonSchemaToHttpOpenApi(inner))
                    case Absent         => None
                HttpOpenApi.SchemaObject(
                    `type` = Some("object"),
                    format = None,
                    items = None,
                    properties = propsMap,
                    required = if required.isEmpty then None else Some(required),
                    additionalProperties = addProps,
                    oneOf = None,
                    `enum` = None,
                    `$ref` = None,
                    description = description.toOption
                )

            case JsonSchema.Arr(items, minItems, maxItems, uniqueItems, description) =>
                HttpOpenApi.SchemaObject(
                    `type` = Some("array"),
                    format = None,
                    items = Some(jsonSchemaToHttpOpenApi(items)),
                    properties = None,
                    required = None,
                    additionalProperties = None,
                    oneOf = None,
                    `enum` = None,
                    `$ref` = None,
                    minItems = minItems.toOption,
                    maxItems = maxItems.toOption,
                    uniqueItems = uniqueItems.toOption,
                    description = description.toOption
                )

            case JsonSchema.Str(minLength, maxLength, pattern, format, description) =>
                HttpOpenApi.SchemaObject(
                    `type` = Some("string"),
                    format = format.toOption,
                    items = None,
                    properties = None,
                    required = None,
                    additionalProperties = None,
                    oneOf = None,
                    `enum` = None,
                    `$ref` = None,
                    minLength = minLength.toOption,
                    maxLength = maxLength.toOption,
                    pattern = pattern.toOption,
                    description = description.toOption
                )

            case JsonSchema.Num(minimum, exclusiveMinimum, maximum, exclusiveMaximum, description) =>
                HttpOpenApi.SchemaObject(
                    `type` = Some("number"),
                    format = Some("double"),
                    items = None,
                    properties = None,
                    required = None,
                    additionalProperties = None,
                    oneOf = None,
                    `enum` = None,
                    `$ref` = None,
                    minimum = minimum.toOption,
                    exclusiveMinimum = exclusiveMinimum.toOption,
                    maximum = maximum.toOption,
                    exclusiveMaximum = exclusiveMaximum.toOption,
                    description = description.toOption
                )

            case JsonSchema.Integer(minimum, exclusiveMinimum, maximum, exclusiveMaximum, description) =>
                HttpOpenApi.SchemaObject(
                    `type` = Some("integer"),
                    format = Some("int32"),
                    items = None,
                    properties = None,
                    required = None,
                    additionalProperties = None,
                    oneOf = None,
                    `enum` = None,
                    `$ref` = None,
                    minimum = minimum.toOption.map(_.toDouble),
                    exclusiveMinimum = exclusiveMinimum.toOption.map(_.toDouble),
                    maximum = maximum.toOption.map(_.toDouble),
                    exclusiveMaximum = exclusiveMaximum.toOption.map(_.toDouble),
                    description = description.toOption
                )

            case JsonSchema.Bool(description) =>
                HttpOpenApi.SchemaObject(
                    `type` = Some("boolean"),
                    format = None,
                    items = None,
                    properties = None,
                    required = None,
                    additionalProperties = None,
                    oneOf = None,
                    `enum` = None,
                    `$ref` = None,
                    description = description.toOption
                )

            case JsonSchema.Null(description) =>
                HttpOpenApi.SchemaObject(
                    `type` = Some("null"),
                    format = None,
                    items = None,
                    properties = None,
                    required = None,
                    additionalProperties = None,
                    oneOf = None,
                    `enum` = None,
                    `$ref` = None,
                    description = description.toOption
                )

            case JsonSchema.Nullable(inner, description) =>
                val schema = jsonSchemaToHttpOpenApi(inner)
                description.fold(schema)(d => schema.copy(description = Some(d)))

            case JsonSchema.OneOf(variants, description) =>
                if variants.isEmpty then HttpOpenApi.SchemaObject.string.copy(description = description.toOption)
                else
                    val allSimple = variants.forall {
                        case (_, obj: JsonSchema.Obj) => obj.properties.isEmpty && obj.additionalProperties.isEmpty
                        case _                        => false
                    }
                    if allSimple then
                        HttpOpenApi.SchemaObject(
                            `type` = Some("string"),
                            format = None,
                            items = None,
                            properties = None,
                            required = None,
                            additionalProperties = None,
                            oneOf = None,
                            `enum` = Some(variants.map(_._1)),
                            `$ref` = None,
                            description = description.toOption
                        )
                    else
                        HttpOpenApi.SchemaObject(
                            `type` = None,
                            format = None,
                            items = None,
                            properties = None,
                            required = None,
                            additionalProperties = None,
                            oneOf = Some(variants.map { case (_, sub) => jsonSchemaToHttpOpenApi(sub) }),
                            `enum` = None,
                            `$ref` = None,
                            description = description.toOption
                        )
                    end if
        end match
    end jsonSchemaToHttpOpenApi

end OpenApiGenerator
