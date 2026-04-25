package kyo.internal

import kyo.*
import kyo.Json.JsonSchema
import scala.collection.mutable

/** Generates OpenAPI 3.0 specification from HttpHandler definitions. */
private[kyo] object OpenApiGenerator:

    case class Config(
        title: String = "API",
        version: String = "1.0.0",
        description: Option[String] = None
    )

    def generate(handlers: Seq[HttpHandler[?, ?, ?]], config: Config = Config()): HttpOpenApi =
        val paths = buildPaths(handlers)
        HttpOpenApi(
            openapi = "3.0.0",
            info = HttpOpenApi.Info(
                title = config.title,
                version = config.version,
                description = config.description
            ),
            paths = paths,
            components = None
        )
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
                    json = inferCodecJson(p.codec),
                    description = if p.description.nonEmpty then Some(p.description) else None
                )
            case p: HttpRoute.Field.Param[?, ?, ?] if p.kind == HttpRoute.Field.Param.Location.Header =>
                HttpOpenApi.Parameter(
                    name = p.fieldName,
                    in = "header",
                    required = if !p.optional && p.default.isEmpty then Some(true) else None,
                    json = inferCodecJson(p.codec),
                    description = if p.description.nonEmpty then Some(p.description) else None
                )
            case p: HttpRoute.Field.Param[?, ?, ?] if p.kind == HttpRoute.Field.Param.Location.Cookie =>
                HttpOpenApi.Parameter(
                    name = p.fieldName,
                    in = "cookie",
                    required = if !p.optional && p.default.isEmpty then Some(true) else None,
                    json = inferCodecJson(p.codec),
                    description = if p.description.nonEmpty then Some(p.description) else None
                )
        }

        pathParams ++ fieldParams
    end buildParameters

    private def extractPathParams(path: HttpPath[?]): Seq[HttpOpenApi.Parameter] =
        path match
            case HttpPath.Literal(_) => Seq.empty
            case HttpPath.Capture(fieldName, wireName, codec) =>
                val name = if wireName.nonEmpty then wireName else fieldName
                Seq(HttpOpenApi.Parameter(
                    name = name,
                    in = "path",
                    required = Some(true),
                    json = inferCodecJson(codec),
                    description = None
                ))
            case HttpPath.Concat(left, right) =>
                extractPathParams(left) ++ extractPathParams(right)
            case HttpPath.Rest(fieldName) =>
                Seq(HttpOpenApi.Parameter(
                    name = fieldName,
                    in = "path",
                    required = Some(true),
                    json = HttpOpenApi.SchemaObject.string,
                    description = None
                ))
    end extractPathParams

    /** Infer OpenAPI json type from HttpCodec by probing with sample values. */
    private def inferCodecJson(codec: HttpCodec[?]): HttpOpenApi.SchemaObject =
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
    end inferCodecJson

    private def buildRequestBody(route: HttpRoute[?, ?, ?]): Option[HttpOpenApi.RequestBody] =
        route.request.fields.toSeq.collectFirst {
            case b: HttpRoute.Field.Body[?, ?] =>
                contentToMediaType(b.contentType).map { case (ct, json) =>
                    HttpOpenApi.RequestBody(
                        required = Some(true),
                        content = Map(ct -> HttpOpenApi.MediaType(json = json)),
                        description = if b.description.nonEmpty then Some(b.description) else None
                    )
                }
        }.flatten
    end buildRequestBody

    private def buildResponses(route: HttpRoute[?, ?, ?]): Map[String, HttpOpenApi.Response] =
        val successStatus = route.response.status.code.toString

        val successResponse = route.response.fields.toSeq.collectFirst {
            case b: HttpRoute.Field.Body[?, ?] =>
                contentToMediaType(b.contentType).map { case (ct, json) =>
                    HttpOpenApi.Response(
                        description = "Success",
                        content = Some(Map(ct -> HttpOpenApi.MediaType(json = json)))
                    )
                }
        }.flatten.getOrElse(
            HttpOpenApi.Response(description = "Success", content = None)
        )

        val errorResponses = route.response.errors.toSeq.map { mapping =>
            mapping.status.code.toString -> HttpOpenApi.Response(
                description = "Error",
                content = Some(Map("application/json" -> HttpOpenApi.MediaType(
                    json = jsonSchemaToHttpOpenApi(mapping.jsonSchema)
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
      *   - Nullable(inner) → recurse on inner (optionality is captured in the parent's required list).
      *   - OneOf(variants) → if all variants map to an empty Obj, emit enum with variant names; otherwise emit oneOf.
      */
    private def jsonSchemaToHttpOpenApi(js: JsonSchema): HttpOpenApi.SchemaObject =
        js match
            case JsonSchema.Obj(properties, required, additionalProperties, _, _, _) =>
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
                    `$ref` = None
                )

            case JsonSchema.Arr(items, _, _, _, _) =>
                HttpOpenApi.SchemaObject.array(jsonSchemaToHttpOpenApi(items))

            case JsonSchema.Str(_, _, _, format, _) =>
                format match
                    case Present(f) =>
                        HttpOpenApi.SchemaObject(
                            `type` = Some("string"),
                            format = Some(f),
                            items = None,
                            properties = None,
                            required = None,
                            additionalProperties = None,
                            oneOf = None,
                            `enum` = None,
                            `$ref` = None
                        )
                    case Absent => HttpOpenApi.SchemaObject.string

            case JsonSchema.Num(_, _, _, _, _) =>
                HttpOpenApi.SchemaObject.number

            case JsonSchema.Integer(_, _, _, _, _) =>
                HttpOpenApi.SchemaObject.integer

            case JsonSchema.Bool =>
                HttpOpenApi.SchemaObject.boolean

            case JsonSchema.Null =>
                HttpOpenApi.SchemaObject(
                    `type` = Some("null"),
                    format = None,
                    items = None,
                    properties = None,
                    required = None,
                    additionalProperties = None,
                    oneOf = None,
                    `enum` = None,
                    `$ref` = None
                )

            case JsonSchema.Nullable(inner) =>
                jsonSchemaToHttpOpenApi(inner)

            case JsonSchema.OneOf(variants) =>
                if variants.isEmpty then HttpOpenApi.SchemaObject.string
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
                            `$ref` = None
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
                            `$ref` = None
                        )
                    end if
        end match
    end jsonSchemaToHttpOpenApi

end OpenApiGenerator
