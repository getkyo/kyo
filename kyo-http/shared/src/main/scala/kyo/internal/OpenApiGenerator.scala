package kyo.internal

import kyo.*
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
                    json = jsonToHttpOpenApi(mapping.json)
                )))
            )
        }

        Map(successStatus -> successResponse) ++ errorResponses
    end buildResponses

    private def contentToMediaType(content: HttpRoute.ContentType[?]): Option[(String, HttpOpenApi.SchemaObject)] =
        content match
            case HttpRoute.ContentType.Json(json)      => Some("application/json" -> jsonToHttpOpenApi(json))
            case HttpRoute.ContentType.Text            => Some("text/plain" -> HttpOpenApi.SchemaObject.string)
            case HttpRoute.ContentType.Binary          => Some("application/octet-stream" -> HttpOpenApi.SchemaObject.string)
            case HttpRoute.ContentType.ByteStream      => Some("application/octet-stream" -> HttpOpenApi.SchemaObject.string)
            case HttpRoute.ContentType.Multipart       => Some("multipart/form-data" -> HttpOpenApi.SchemaObject.obj)
            case HttpRoute.ContentType.MultipartStream => Some("multipart/form-data" -> HttpOpenApi.SchemaObject.obj)
            case HttpRoute.ContentType.Ndjson(json, _) => Some("application/x-ndjson" -> jsonToHttpOpenApi(json))
            case HttpRoute.ContentType.Sse(json, _)    => Some("text/event-stream" -> jsonToHttpOpenApi(json))
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

    // ==================== Json introspection ====================

    private def jsonToHttpOpenApi(json: kyo.Json[?]): HttpOpenApi.SchemaObject =
        zioSchemaToHttpOpenApi(json.zioSchema)

    private def zioSchemaToHttpOpenApi(json: zio.schema.Schema[?]): HttpOpenApi.SchemaObject =
        import zio.schema.Schema as ZSchema
        json match
            case p @ ZSchema.Primitive(_, _) =>
                primitiveToHttpOpenApi(p.standardType)
            case ZSchema.Optional(innerJson, _) =>
                zioSchemaToHttpOpenApi(innerJson)
            case ZSchema.Sequence(elementJson, _, _, _, _) =>
                HttpOpenApi.SchemaObject.array(zioSchemaToHttpOpenApi(elementJson))
            case ZSchema.Map(_, valueJson, _) =>
                HttpOpenApi.SchemaObject(
                    `type` = Some("object"),
                    format = None,
                    items = None,
                    properties = None,
                    required = None,
                    additionalProperties = Some(zioSchemaToHttpOpenApi(valueJson)),
                    oneOf = None,
                    `enum` = None,
                    `$ref` = None
                )
            case ZSchema.Either(leftJson, rightJson, _) =>
                HttpOpenApi.SchemaObject(
                    `type` = None,
                    format = None,
                    items = None,
                    properties = None,
                    required = None,
                    additionalProperties = None,
                    oneOf = Some(List(zioSchemaToHttpOpenApi(leftJson), zioSchemaToHttpOpenApi(rightJson))),
                    `enum` = None,
                    `$ref` = None
                )
            case r: ZSchema.Record[?] =>
                buildRecordJson(r)
            case e: ZSchema.Enum[?] =>
                buildEnumJson(e)
            case ZSchema.Transform(json, _, _, _, _) =>
                zioSchemaToHttpOpenApi(json)
            case ZSchema.Lazy(json0) =>
                zioSchemaToHttpOpenApi(json0())
            case _ =>
                HttpOpenApi.SchemaObject.obj
        end match
    end zioSchemaToHttpOpenApi

    private def primitiveToHttpOpenApi(standardType: zio.schema.StandardType[?]): HttpOpenApi.SchemaObject =
        import zio.schema.StandardType
        if standardType eq StandardType.StringType then HttpOpenApi.SchemaObject.string
        else if standardType eq StandardType.IntType then HttpOpenApi.SchemaObject.integer
        else if standardType eq StandardType.LongType then HttpOpenApi.SchemaObject.long
        else if standardType eq StandardType.DoubleType then HttpOpenApi.SchemaObject.number
        else if standardType eq StandardType.FloatType then HttpOpenApi.SchemaObject.number
        else if standardType eq StandardType.BoolType then HttpOpenApi.SchemaObject.boolean
        else if standardType eq StandardType.ShortType then HttpOpenApi.SchemaObject.integer
        else if standardType eq StandardType.ByteType then HttpOpenApi.SchemaObject.integer
        else if standardType eq StandardType.CharType then HttpOpenApi.SchemaObject.string
        else if standardType eq StandardType.UUIDType then
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
        else HttpOpenApi.SchemaObject.string
        end if
    end primitiveToHttpOpenApi

    private def buildRecordJson(record: zio.schema.Schema.Record[?]): HttpOpenApi.SchemaObject =
        val fields = record.fields.toSeq
        if fields.isEmpty then HttpOpenApi.SchemaObject.obj
        else
            val props     = mutable.LinkedHashMap[String, HttpOpenApi.SchemaObject]()
            val reqFields = mutable.ArrayBuffer[String]()

            def isOptionalJson(s: zio.schema.Schema[?]): Boolean = s match
                case _: zio.schema.Schema.Optional[?]        => true
                case l: zio.schema.Schema.Lazy[?]            => isOptionalJson(l.schema)
                case t: zio.schema.Schema.Transform[?, ?, ?] => isOptionalJson(t.schema)
                case _                                       => false

            fields.foreach { field =>
                props(field.name.toString) = zioSchemaToHttpOpenApi(field.schema)
                if !field.optional && !isOptionalJson(field.schema) then reqFields += field.name.toString
            }

            HttpOpenApi.SchemaObject(
                `type` = Some("object"),
                format = None,
                items = None,
                properties = Some(props.toMap),
                required = if reqFields.isEmpty then None else Some(reqFields.toList),
                additionalProperties = None,
                oneOf = None,
                `enum` = None,
                `$ref` = None
            )
        end if
    end buildRecordJson

    private def buildEnumJson(e: zio.schema.Schema.Enum[?]): HttpOpenApi.SchemaObject =
        val cases = e.cases.toSeq
        if cases.isEmpty then HttpOpenApi.SchemaObject.string
        else
            val allSimple = cases.forall { c =>
                c.schema match
                    case r: zio.schema.Schema.Record[?] => r.fields.isEmpty
                    case _                              => false
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
                    `enum` = Some(cases.map(_.id).toList),
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
                    oneOf = Some(cases.map(c => zioSchemaToHttpOpenApi(c.schema)).toList),
                    `enum` = None,
                    `$ref` = None
                )
            end if
        end if
    end buildEnumJson

end OpenApiGenerator
