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

    def generate(handlers: Seq[HttpHandler[?, ?, ?]], config: Config = Config()): OpenApi =
        val paths = buildPaths(handlers)
        OpenApi(
            openapi = "3.0.0",
            info = OpenApi.Info(
                title = config.title,
                version = config.version,
                description = config.description
            ),
            paths = paths,
            components = None
        )
    end generate

    private def buildPaths(handlers: Seq[HttpHandler[?, ?, ?]]): Map[String, OpenApi.PathItem] =
        handlers.groupBy(h => pathToOpenApi(h.route.request.path)).map { case (path, handlers) =>
            path -> buildPathItem(handlers)
        }

    private def buildPathItem(handlers: Seq[HttpHandler[?, ?, ?]]): OpenApi.PathItem =
        val ops = handlers.map(h => h.route.method -> buildOperation(h.route)).toMap
        OpenApi.PathItem(
            get = ops.get(HttpMethod.GET),
            post = ops.get(HttpMethod.POST),
            put = ops.get(HttpMethod.PUT),
            delete = ops.get(HttpMethod.DELETE),
            patch = ops.get(HttpMethod.PATCH),
            head = ops.get(HttpMethod.HEAD),
            options = ops.get(HttpMethod.OPTIONS)
        )
    end buildPathItem

    private def buildOperation(route: HttpRoute[?, ?, ?]): OpenApi.Operation =
        val meta        = route.metadata
        val parameters  = buildParameters(route)
        val requestBody = buildRequestBody(route)
        val responses   = buildResponses(route)

        OpenApi.Operation(
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

    private def buildParameters(route: HttpRoute[?, ?, ?]): Seq[OpenApi.Parameter] =
        val pathParams = extractPathParams(route.request.path)

        val fieldParams = route.request.fields.toSeq.collect {
            case p: HttpRoute.Field.Param[?, ?, ?] if p.kind == HttpRoute.Field.Param.Location.Query =>
                OpenApi.Parameter(
                    name = p.fieldName,
                    in = "query",
                    required = if !p.optional && p.default.isEmpty then Some(true) else None,
                    schema = inferCodecSchema(p.codec),
                    description = if p.description.nonEmpty then Some(p.description) else None
                )
            case p: HttpRoute.Field.Param[?, ?, ?] if p.kind == HttpRoute.Field.Param.Location.Header =>
                OpenApi.Parameter(
                    name = p.fieldName,
                    in = "header",
                    required = if !p.optional && p.default.isEmpty then Some(true) else None,
                    schema = inferCodecSchema(p.codec),
                    description = if p.description.nonEmpty then Some(p.description) else None
                )
            case p: HttpRoute.Field.Param[?, ?, ?] if p.kind == HttpRoute.Field.Param.Location.Cookie =>
                OpenApi.Parameter(
                    name = p.fieldName,
                    in = "cookie",
                    required = if !p.optional && p.default.isEmpty then Some(true) else None,
                    schema = inferCodecSchema(p.codec),
                    description = if p.description.nonEmpty then Some(p.description) else None
                )
        }

        pathParams ++ fieldParams
    end buildParameters

    private def extractPathParams(path: HttpPath[?]): Seq[OpenApi.Parameter] =
        path match
            case HttpPath.Literal(_) => Seq.empty
            case HttpPath.Capture(fieldName, wireName, codec) =>
                val name = if wireName.nonEmpty then wireName else fieldName
                Seq(OpenApi.Parameter(
                    name = name,
                    in = "path",
                    required = Some(true),
                    schema = inferCodecSchema(codec),
                    description = None
                ))
            case HttpPath.Concat(left, right) =>
                extractPathParams(left) ++ extractPathParams(right)
            case HttpPath.Rest(fieldName) =>
                Seq(OpenApi.Parameter(
                    name = fieldName,
                    in = "path",
                    required = Some(true),
                    schema = OpenApi.SchemaObject.string,
                    description = None
                ))
    end extractPathParams

    /** Infer OpenAPI schema type from HttpCodec by probing with sample values. */
    private def inferCodecSchema(codec: HttpCodec[?]): OpenApi.SchemaObject =
        def tryProbe(input: String)(pf: PartialFunction[Any, OpenApi.SchemaObject]): Maybe[OpenApi.SchemaObject] =
            try
                val result = codec.decode(input)
                if pf.isDefinedAt(result) then Present(pf(result))
                else kyo.Absent
            catch case _: Exception => kyo.Absent

        tryProbe("1") {
            case _: Int     => OpenApi.SchemaObject.integer
            case _: Long    => OpenApi.SchemaObject.long
            case _: Boolean => OpenApi.SchemaObject.boolean
        }.orElse(tryProbe("true") {
            case _: Boolean => OpenApi.SchemaObject.boolean
        }).orElse(tryProbe("1.0") {
            case _: Double => OpenApi.SchemaObject.number
            case _: Float  => OpenApi.SchemaObject.number
        }).orElse(tryProbe("00000000-0000-0000-0000-000000000000") {
            case _: java.util.UUID =>
                OpenApi.SchemaObject(
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
        }).getOrElse(OpenApi.SchemaObject.string)
    end inferCodecSchema

    private def buildRequestBody(route: HttpRoute[?, ?, ?]): Option[OpenApi.RequestBody] =
        route.request.fields.toSeq.collectFirst {
            case b: HttpRoute.Field.Body[?, ?] =>
                contentToMediaType(b.contentType).map { case (ct, schema) =>
                    OpenApi.RequestBody(
                        required = Some(true),
                        content = Map(ct -> OpenApi.MediaType(schema = schema)),
                        description = if b.description.nonEmpty then Some(b.description) else None
                    )
                }
        }.flatten
    end buildRequestBody

    private def buildResponses(route: HttpRoute[?, ?, ?]): Map[String, OpenApi.Response] =
        val successStatus = route.response.status.code.toString

        val successResponse = route.response.fields.toSeq.collectFirst {
            case b: HttpRoute.Field.Body[?, ?] =>
                contentToMediaType(b.contentType).map { case (ct, schema) =>
                    OpenApi.Response(
                        description = "Success",
                        content = Some(Map(ct -> OpenApi.MediaType(schema = schema)))
                    )
                }
        }.flatten.getOrElse(
            OpenApi.Response(description = "Success", content = None)
        )

        val errorResponses = route.response.errors.toSeq.map { mapping =>
            mapping.status.code.toString -> OpenApi.Response(
                description = "Error",
                content = Some(Map("application/json" -> OpenApi.MediaType(
                    schema = schemaToOpenApi(mapping.schema)
                )))
            )
        }

        Map(successStatus -> successResponse) ++ errorResponses
    end buildResponses

    private def contentToMediaType(content: HttpRoute.ContentType[?]): Option[(String, OpenApi.SchemaObject)] =
        content match
            case HttpRoute.ContentType.Json(schema)      => Some("application/json" -> schemaToOpenApi(schema))
            case HttpRoute.ContentType.Text()            => Some("text/plain" -> OpenApi.SchemaObject.string)
            case HttpRoute.ContentType.Binary()          => Some("application/octet-stream" -> OpenApi.SchemaObject.string)
            case HttpRoute.ContentType.ByteStream()      => Some("application/octet-stream" -> OpenApi.SchemaObject.string)
            case HttpRoute.ContentType.Multipart()       => Some("multipart/form-data" -> OpenApi.SchemaObject.obj)
            case HttpRoute.ContentType.MultipartStream() => Some("multipart/form-data" -> OpenApi.SchemaObject.obj)
            case HttpRoute.ContentType.Ndjson(schema, _) => Some("application/x-ndjson" -> schemaToOpenApi(schema))
            case HttpRoute.ContentType.Sse(schema, _)    => Some("text/event-stream" -> schemaToOpenApi(schema))
            case HttpRoute.ContentType.SseText(_)        => Some("text/event-stream" -> OpenApi.SchemaObject.string)
            case HttpRoute.ContentType.Form(_)           => Some("application/x-www-form-urlencoded" -> OpenApi.SchemaObject.obj)

    private def pathToOpenApi(path: HttpPath[?]): String =
        path match
            case HttpPath.Literal(value) =>
                if value.startsWith("/") then value else "/" + value
            case HttpPath.Capture(fieldName, wireName, _) =>
                val name = if wireName.nonEmpty then wireName else fieldName
                s"/{$name}"
            case HttpPath.Concat(left, right) =>
                pathToOpenApi(left) + pathToOpenApi(right)
            case HttpPath.Rest(fieldName) => s"/{$fieldName}"

    // ==================== Schema introspection ====================

    private def schemaToOpenApi(schema: kyo.Schema[?]): OpenApi.SchemaObject =
        zioSchemaToOpenApi(schema.zpiSchema)

    private def zioSchemaToOpenApi(schema: zio.schema.Schema[?]): OpenApi.SchemaObject =
        import zio.schema.Schema as ZSchema
        schema match
            case p @ ZSchema.Primitive(_, _) =>
                primitiveToOpenApi(p.standardType)
            case ZSchema.Optional(innerSchema, _) =>
                zioSchemaToOpenApi(innerSchema)
            case ZSchema.Sequence(elementSchema, _, _, _, _) =>
                OpenApi.SchemaObject.array(zioSchemaToOpenApi(elementSchema))
            case ZSchema.Map(_, valueSchema, _) =>
                OpenApi.SchemaObject(
                    `type` = Some("object"),
                    format = None,
                    items = None,
                    properties = None,
                    required = None,
                    additionalProperties = Some(zioSchemaToOpenApi(valueSchema)),
                    oneOf = None,
                    `enum` = None,
                    `$ref` = None
                )
            case ZSchema.Either(leftSchema, rightSchema, _) =>
                OpenApi.SchemaObject(
                    `type` = None,
                    format = None,
                    items = None,
                    properties = None,
                    required = None,
                    additionalProperties = None,
                    oneOf = Some(List(zioSchemaToOpenApi(leftSchema), zioSchemaToOpenApi(rightSchema))),
                    `enum` = None,
                    `$ref` = None
                )
            case r: ZSchema.Record[?] =>
                buildRecordSchema(r)
            case e: ZSchema.Enum[?] =>
                buildEnumSchema(e)
            case ZSchema.Transform(schema, _, _, _, _) =>
                zioSchemaToOpenApi(schema)
            case ZSchema.Lazy(schema0) =>
                zioSchemaToOpenApi(schema0())
            case _ =>
                OpenApi.SchemaObject.obj
        end match
    end zioSchemaToOpenApi

    private def primitiveToOpenApi(standardType: zio.schema.StandardType[?]): OpenApi.SchemaObject =
        import zio.schema.StandardType
        if standardType eq StandardType.StringType then OpenApi.SchemaObject.string
        else if standardType eq StandardType.IntType then OpenApi.SchemaObject.integer
        else if standardType eq StandardType.LongType then OpenApi.SchemaObject.long
        else if standardType eq StandardType.DoubleType then OpenApi.SchemaObject.number
        else if standardType eq StandardType.FloatType then OpenApi.SchemaObject.number
        else if standardType eq StandardType.BoolType then OpenApi.SchemaObject.boolean
        else if standardType eq StandardType.ShortType then OpenApi.SchemaObject.integer
        else if standardType eq StandardType.ByteType then OpenApi.SchemaObject.integer
        else if standardType eq StandardType.CharType then OpenApi.SchemaObject.string
        else if standardType eq StandardType.UUIDType then
            OpenApi.SchemaObject(
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
        else OpenApi.SchemaObject.string
        end if
    end primitiveToOpenApi

    private def buildRecordSchema(record: zio.schema.Schema.Record[?]): OpenApi.SchemaObject =
        val fields = record.fields.toSeq
        if fields.isEmpty then OpenApi.SchemaObject.obj
        else
            val props     = mutable.LinkedHashMap[String, OpenApi.SchemaObject]()
            val reqFields = mutable.ArrayBuffer[String]()

            def isOptionalSchema(s: zio.schema.Schema[?]): Boolean = s match
                case _: zio.schema.Schema.Optional[?]        => true
                case l: zio.schema.Schema.Lazy[?]            => isOptionalSchema(l.schema)
                case t: zio.schema.Schema.Transform[?, ?, ?] => isOptionalSchema(t.schema)
                case _                                       => false

            fields.foreach { field =>
                props(field.name.toString) = zioSchemaToOpenApi(field.schema)
                if !field.optional && !isOptionalSchema(field.schema) then reqFields += field.name.toString
            }

            OpenApi.SchemaObject(
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
    end buildRecordSchema

    private def buildEnumSchema(e: zio.schema.Schema.Enum[?]): OpenApi.SchemaObject =
        val cases = e.cases.toSeq
        if cases.isEmpty then OpenApi.SchemaObject.string
        else
            val allSimple = cases.forall { c =>
                c.schema match
                    case r: zio.schema.Schema.Record[?] => r.fields.isEmpty
                    case _                              => false
            }
            if allSimple then
                OpenApi.SchemaObject(
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
                OpenApi.SchemaObject(
                    `type` = None,
                    format = None,
                    items = None,
                    properties = None,
                    required = None,
                    additionalProperties = None,
                    oneOf = Some(cases.map(c => zioSchemaToOpenApi(c.schema)).toList),
                    `enum` = None,
                    `$ref` = None
                )
            end if
        end if
    end buildEnumSchema

end OpenApiGenerator
