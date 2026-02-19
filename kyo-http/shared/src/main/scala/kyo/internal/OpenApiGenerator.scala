package kyo.internal

import kyo.*
import kyo.HttpOpenApi.*
import kyo.HttpPath
import kyo.HttpRequest.Method
import kyo.HttpRoute.*
import scala.collection.mutable

/** Generates OpenAPI 3.0 specification from HttpHandler definitions. */
private[kyo] object OpenApiGenerator:

    def generate(handlers: Seq[HttpHandler[?]], config: HttpOpenApi.Config = HttpOpenApi.Config()): HttpOpenApi =
        val paths           = buildPaths(handlers)
        val securitySchemes = collectSecuritySchemes(handlers)
        HttpOpenApi(
            openapi = "3.0.0",
            info = Info(
                title = config.title,
                version = config.version,
                description = config.description.toOption
            ),
            paths = paths,
            components = if securitySchemes.isEmpty then None
            else Some(Components(schemas = None, securitySchemes = Some(securitySchemes)))
        )
    end generate

    private def buildPaths(handlers: Seq[HttpHandler[?]]): Map[String, PathItem] =
        handlers.groupBy(h => pathToOpenApi(h.route.path)).map { case (path, handlers) =>
            path -> buildPathItem(handlers)
        }

    private def buildPathItem(handlers: Seq[HttpHandler[?]]): PathItem =
        val ops = handlers.map(h => h.route.method -> buildOperation(h.route)).toMap
        PathItem(
            get = ops.get(Method.GET),
            post = ops.get(Method.POST),
            put = ops.get(Method.PUT),
            delete = ops.get(Method.DELETE),
            patch = ops.get(Method.PATCH),
            head = ops.get(Method.HEAD),
            options = ops.get(Method.OPTIONS)
        )
    end buildPathItem

    private def buildOperation(route: HttpRoute[?, ?, ?, ?]): Operation =
        val meta        = route.metadata
        val parameters  = buildParameters(route)
        val requestBody = buildRequestBody(route)
        val responses   = buildResponses(route)

        Operation(
            tags = if meta.tags.isEmpty then None else Some(meta.tags.toList),
            summary = meta.summary.toOption,
            description = meta.description.toOption,
            operationId = meta.operationId.toOption,
            deprecated = if meta.deprecated then Some(true) else None,
            parameters = if parameters.isEmpty then None else Some(parameters.toList),
            requestBody = requestBody.toOption,
            responses = responses,
            security = meta.security.toOption.map(scheme => List(Map(scheme -> List.empty[String])))
        )
    end buildOperation

    private def buildParameters(route: HttpRoute[?, ?, ?, ?]): Seq[Parameter] =
        val pathParams = extractPathParams(route.path)

        val inputParams = route.request.inputFields.collect {
            case InputField.Query(name, _, default, optional, _, desc) =>
                Parameter(
                    name = name,
                    in = "query",
                    required = if !optional && default.isEmpty then Some(true) else None,
                    schema = SchemaObject.string,
                    description = if desc.nonEmpty then Some(desc) else None
                )
            case InputField.Header(name, _, default, optional, _, desc) =>
                Parameter(
                    name = name,
                    in = "header",
                    required = if !optional && default.isEmpty then Some(true) else None,
                    schema = SchemaObject.string,
                    description = if desc.nonEmpty then Some(desc) else None
                )
            case InputField.Cookie(name, _, default, optional, _, desc) =>
                Parameter(
                    name = name,
                    in = "cookie",
                    required = if !optional && default.isEmpty then Some(true) else None,
                    schema = SchemaObject.string,
                    description = if desc.nonEmpty then Some(desc) else None
                )
        }

        pathParams ++ inputParams
    end buildParameters

    private def extractPathParams(path: HttpPath[?]): Seq[Parameter] =
        path match
            case HttpPath.Literal(_) => Seq.empty
            case HttpPath.Capture(wireName, _, codec) =>
                Seq(Parameter(
                    name = wireName,
                    in = "path",
                    required = Some(true),
                    schema = inferCodecSchema(codec),
                    description = None
                ))
            case HttpPath.Concat(left, right) =>
                extractPathParams(left) ++ extractPathParams(right)
            case HttpPath.Rest(fieldName) =>
                Seq(Parameter(
                    name = fieldName,
                    in = "path",
                    required = Some(true),
                    schema = SchemaObject.string,
                    description = None
                ))
    end extractPathParams

    /** Infer OpenAPI schema type from HttpParamCodec by probing with sample values. */
    private def inferCodecSchema(codec: HttpParamCodec[?]): SchemaObject =
        val c = codec

        def tryProbe(input: String)(pf: PartialFunction[Any, SchemaObject]): Maybe[SchemaObject] =
            try
                val result = c.parse(input)
                if pf.isDefinedAt(result) then Present(pf(result))
                else Absent
            catch case _: Exception => Absent

        tryProbe("1") {
            case _: Int     => SchemaObject.integer
            case _: Long    => SchemaObject.long
            case _: Boolean => SchemaObject.boolean
        }.orElse(tryProbe("true") {
            case _: Boolean => SchemaObject.boolean
        }).orElse(tryProbe("00000000-0000-0000-0000-000000000000") {
            case _: java.util.UUID => SchemaObject(Some("string"), Some("uuid"), None, None, None, None, None, None, None)
        }).getOrElse(SchemaObject.string)
    end inferCodecSchema

    private def buildRequestBody(route: HttpRoute[?, ?, ?, ?]): Maybe[RequestBody] =
        val hasFormBody = route.request.inputFields.exists(_.isInstanceOf[InputField.FormBody])
        if hasFormBody then
            Present(RequestBody(
                required = Some(true),
                content = Map("application/x-www-form-urlencoded" -> MediaType(schema = SchemaObject.obj)),
                description = None
            ))
        else
            route.request.inputFields.collectFirst {
                case InputField.Body(content, desc) => (content, desc)
            } match
                case Some((content, desc)) =>
                    contentToMediaType(content) { (ct, schema) =>
                        Present(RequestBody(
                            required = Some(true),
                            content = Map(ct -> MediaType(schema = schema)),
                            description = if desc.nonEmpty then Some(desc) else None
                        ))
                    }
                case None => Absent
        end if
    end buildRequestBody

    private def buildResponses(route: HttpRoute[?, ?, ?, ?]): Map[String, Response] =
        val successStatus = route.response.status.code.toString

        val successResponse = route.response.outputFields.collectFirst {
            case OutputField.Body(content, desc) => (content, desc)
        } match
            case Some((content, desc)) =>
                contentToMediaType(content) { (ct, schema) =>
                    Response(
                        description = if desc.nonEmpty then desc else "Success",
                        content = Some(Map(ct -> MediaType(schema = schema)))
                    )
                }
            case None =>
                Response(description = "Success", content = None)

        val errorResponses = route.response.errorMappings.map { mapping =>
            mapping.status.code.toString -> Response(
                description = "Error",
                content = Some(Map("application/json" -> MediaType(schema = schemaToOpenApi(mapping.schema))))
            )
        }

        Map(successStatus -> successResponse) ++ errorResponses
    end buildResponses

    private inline def contentToMediaType[A](content: Content)(inline cont: (String, SchemaObject) => A): A =
        content match
            case Content.Json(schema) => cont("application/json", schemaToOpenApi(schema))
            case Content.Text         => cont("text/plain", SchemaObject.string)
            case Content.Binary       => cont("application/octet-stream", SchemaObject.string)
            case Content.ByteStream   => cont("application/octet-stream", SchemaObject.string)

            case Content.Multipart         => cont("multipart/form-data", SchemaObject.obj)
            case Content.MultipartStream   => cont("multipart/form-data", SchemaObject.obj)
            case Content.Ndjson(schema, _) => cont("application/x-ndjson", schemaToOpenApi(schema))
            case Content.Sse(schema, _)    => cont("text/event-stream", schemaToOpenApi(schema))

    private def pathToOpenApi(path: HttpPath[?]): String =
        path match
            case HttpPath.Literal(value) =>
                if value.startsWith("/") then value else "/" + value
            case HttpPath.Capture(wireName, _, _) => s"/{$wireName}"
            case HttpPath.Concat(left, right) =>
                pathToOpenApi(left) + pathToOpenApi(right)
            case HttpPath.Rest(fieldName) => s"/{$fieldName}"

    private def schemaToOpenApi(schema: kyo.Schema[?]): SchemaObject =
        zioSchemaToOpenApi(schema.zpiSchema)

    private def zioSchemaToOpenApi(schema: zio.schema.Schema[?]): SchemaObject =
        import zio.schema.Schema as ZSchema
        import zio.schema.StandardType
        schema match
            case p @ ZSchema.Primitive(_, _) =>
                primitiveToOpenApi(p.standardType)
            case ZSchema.Optional(innerSchema, _) =>
                zioSchemaToOpenApi(innerSchema)
            case ZSchema.Sequence(elementSchema, _, _, _, _) =>
                SchemaObject.array(zioSchemaToOpenApi(elementSchema))
            case ZSchema.Map(_, valueSchema, _) =>
                SchemaObject(Some("object"), None, None, None, None, Some(zioSchemaToOpenApi(valueSchema)), None, None, None)
            case ZSchema.Either(leftSchema, rightSchema, _) =>
                SchemaObject(
                    None,
                    None,
                    None,
                    None,
                    None,
                    None,
                    Some(List(zioSchemaToOpenApi(leftSchema), zioSchemaToOpenApi(rightSchema))),
                    None,
                    None
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
                SchemaObject.obj
        end match
    end zioSchemaToOpenApi

    private def primitiveToOpenApi(standardType: zio.schema.StandardType[?]): SchemaObject =
        import zio.schema.StandardType
        if standardType eq StandardType.StringType then SchemaObject.string
        else if standardType eq StandardType.IntType then SchemaObject.integer
        else if standardType eq StandardType.LongType then SchemaObject.long
        else if standardType eq StandardType.DoubleType then SchemaObject.number
        else if standardType eq StandardType.FloatType then SchemaObject.number
        else if standardType eq StandardType.BoolType then SchemaObject.boolean
        else if standardType eq StandardType.ShortType then SchemaObject.integer
        else if standardType eq StandardType.ByteType then SchemaObject.integer
        else if standardType eq StandardType.CharType then SchemaObject.string
        else if standardType eq StandardType.UUIDType then
            SchemaObject(Some("string"), Some("uuid"), None, None, None, None, None, None, None)
        else SchemaObject.string
        end if
    end primitiveToOpenApi

    private def buildRecordSchema(record: zio.schema.Schema.Record[?]): SchemaObject =
        val fields = record.fields.toSeq
        if fields.isEmpty then SchemaObject.obj
        else
            val props     = mutable.Map[String, SchemaObject]()
            val reqFields = mutable.ArrayBuffer[String]()

            fields.foreach { field =>
                props(field.name.toString) = zioSchemaToOpenApi(field.schema)
                if !field.optional then reqFields += field.name.toString
            }

            SchemaObject(
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

    private def buildEnumSchema(e: zio.schema.Schema.Enum[?]): SchemaObject =
        val cases = e.cases.toSeq
        if cases.isEmpty then SchemaObject.string
        else
            val allSimple = cases.forall { c =>
                c.schema match
                    case r: zio.schema.Schema.Record[?] => r.fields.isEmpty
                    case _                              => false
            }
            if allSimple then
                SchemaObject(Some("string"), None, None, None, None, None, None, Some(cases.map(_.id).toList), None)
            else
                SchemaObject(None, None, None, None, None, None, Some(cases.map(c => zioSchemaToOpenApi(c.schema)).toList), None, None)
            end if
        end if
    end buildEnumSchema

    /** Collects security schemes from auth input fields. */
    private def collectSecuritySchemes(handlers: Seq[HttpHandler[?]]): Map[String, SecurityScheme] =
        handlers.flatMap { handler =>
            val meta = handler.route.metadata
            meta.security.toOption.flatMap { schemeName =>
                handler.route.request.inputFields.collectFirst {
                    case InputField.Auth(scheme) =>
                        val secScheme = scheme match
                            case AuthScheme.Bearer =>
                                SecurityScheme(`type` = "http", scheme = Some("bearer"), bearerFormat = None, name = None, in = None)
                            case AuthScheme.BasicUsername =>
                                SecurityScheme(`type` = "http", scheme = Some("basic"), bearerFormat = None, name = None, in = None)
                            case AuthScheme.BasicPassword =>
                                // Skip — BasicUsername already handled this
                                SecurityScheme(`type` = "http", scheme = Some("basic"), bearerFormat = None, name = None, in = None)
                            case AuthScheme.ApiKey(name, location) =>
                                val loc = location match
                                    case AuthLocation.Header => "header"
                                    case AuthLocation.Query  => "query"
                                    case AuthLocation.Cookie => "cookie"
                                SecurityScheme(`type` = "apiKey", scheme = None, bearerFormat = None, name = Some(name), in = Some(loc))
                        schemeName -> secScheme
                }
            }
        }.toMap
    end collectSecuritySchemes

end OpenApiGenerator
