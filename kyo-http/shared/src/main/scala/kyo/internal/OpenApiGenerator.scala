package kyo.internal

import kyo.*
import kyo.HttpOpenApi.*
import kyo.HttpPath
import kyo.HttpRequest.Method
import kyo.HttpResponse.Status
import kyo.HttpRoute.BodyEncoding
import scala.annotation.tailrec
import scala.collection.mutable

/** Generates OpenAPI 3.0 specification from HttpHandler definitions. */
private[kyo] object OpenApiGenerator:

    def generate(handlers: Seq[HttpHandler[Any]], config: HttpOpenApi.Config = HttpOpenApi.Config()): HttpOpenApi =
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

    private def buildPaths(handlers: Seq[HttpHandler[Any]]): Map[String, PathItem] =
        handlers.groupBy(h => pathToOpenApi(h.route.path)).map { case (path, handlers) =>
            path -> buildPathItem(handlers)
        }

    private def buildPathItem(handlers: Seq[HttpHandler[Any]]): PathItem =
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

    private def buildOperation(route: HttpRoute[?, ?, ?]): Operation =
        val parameters  = buildParameters(route)
        val requestBody = buildRequestBody(route)
        val responses   = buildResponses(route)

        Operation(
            tags = route.tag.toOption.map(t => List(t)),
            summary = route.summary.toOption,
            description = route.description.toOption,
            operationId = route.operationId.toOption,
            deprecated = if route.isDeprecated then Some(true) else None,
            parameters = if parameters.isEmpty then None else Some(parameters.toList),
            requestBody = requestBody.toOption,
            responses = responses,
            security = route.securityScheme.toOption.map(scheme => List(Map(scheme -> List.empty[String])))
        )
    end buildOperation

    private def buildParameters(route: HttpRoute[?, ?, ?]): Seq[Parameter] =
        val pathParams = extractPathParamsWithType(route.path).map { case (name, schema) =>
            Parameter(
                name = name,
                in = "path",
                required = Some(true),
                schema = schema,
                description = None
            )
        }

        val queryParams = route.queryParams.map { qp =>
            Parameter(
                name = qp.name,
                in = "query",
                required = if qp.default.isEmpty then Some(true) else None,
                schema = schemaToOpenApi(qp.schema),
                description = None
            )
        }

        val headerParams = route.headerParams.filter(_.authScheme.isEmpty).map { hp =>
            Parameter(
                name = hp.name,
                in = "header",
                required = if hp.default.isEmpty then Some(true) else None,
                schema = SchemaObject.string,
                description = None
            )
        }

        val cookieParams = route.cookieParams.map { cp =>
            Parameter(
                name = cp.name,
                in = "cookie",
                required = if cp.default.isEmpty then Some(true) else None,
                schema = SchemaObject.string,
                description = None
            )
        }

        pathParams ++ queryParams ++ headerParams ++ cookieParams
    end buildParameters

    private def extractPathParamsWithType(path: HttpPath[Any]): Seq[(String, SchemaObject)] =
        path match
            case s: String => Seq.empty
            case segment: HttpPath.Segment[?] =>
                extractPathParamsFromSegmentWithType(segment)

    private def extractPathParamsFromSegmentWithType(segment: HttpPath.Segment[?]): Seq[(String, SchemaObject)] =
        segment match
            case HttpPath.Segment.Literal(_) => Seq.empty
            case HttpPath.Segment.Capture(name, parse) =>
                val schema = inferPathParamSchema(parse)
                Seq((name, schema))
            case HttpPath.Segment.Concat(left, right) =>
                extractPathParamsFromSegmentWithType(left.asInstanceOf[HttpPath.Segment[?]]) ++
                    extractPathParamsFromSegmentWithType(right.asInstanceOf[HttpPath.Segment[?]])

    private def inferPathParamSchema(parse: String => ?): SchemaObject =
        // Try to infer type from the parse function by testing with sample values
        // Try integer first
        try
            parse("1") match
                case _: Int     => return SchemaObject.integer
                case _: Long    => return SchemaObject.long
                case _: Boolean => return SchemaObject.boolean
                case _          => // continue
        catch
            case _: Exception => // continue
        end try
        // Try boolean
        try
            parse("true") match
                case _: Boolean => return SchemaObject.boolean
                case _          => // continue
        catch
            case _: Exception => // continue
        end try
        // Try UUID
        try
            parse("00000000-0000-0000-0000-000000000000") match
                case _: java.util.UUID =>
                    return SchemaObject(Some("string"), Some("uuid"), None, None, None, None, None, None, None)
                case _ => // continue
        catch
            case _: Exception => // continue
        end try
        SchemaObject.string
    end inferPathParamSchema

    private def buildRequestBody(route: HttpRoute[?, ?, ?]): Maybe[RequestBody] =
        route.bodyEncoding match
            case Present(enc: (BodyEncoding.Json | BodyEncoding.Form)) =>
                val schema = enc match
                    case BodyEncoding.Json(s) => s
                    case BodyEncoding.Form(s) => s
                val ct = enc.contentType.getOrElse("application/json")
                Present(RequestBody(
                    required = Some(true),
                    content = Map(ct -> MediaType(schema = schemaToOpenApi(schema))),
                    description = None
                ))
            case _ => Absent

    private def buildResponses(route: HttpRoute[?, ?, ?]): Map[String, Response] =
        val successStatus = route.outputStatus.code.toString
        val successResponse = route.responseEncoding match
            case Present(enc) =>
                Response(
                    description = "Success",
                    content =
                        Some(Map(enc.contentType -> MediaType(schema = schemaToOpenApi(HttpRoute.ResponseEncoding.extractSchema(enc)))))
                )
            case Absent =>
                Response(description = "Success", content = None)

        val errorResponses = route.errorSchemas.map { case (status, schema, _) =>
            status.code.toString -> Response(
                description = "Error",
                content = Some(Map("application/json" -> MediaType(schema = schemaToOpenApi(schema))))
            )
        }

        Map(successStatus -> successResponse) ++ errorResponses
    end buildResponses

    private def pathToOpenApi(path: HttpPath[Any]): String =
        path match
            case s: String => s
            case segment: HttpPath.Segment[?] =>
                segmentToOpenApi(segment)

    private def segmentToOpenApi(segment: HttpPath.Segment[?]): String =
        segment match
            case HttpPath.Segment.Literal(value) =>
                // Ensure literal starts with / if it doesn't already
                if value.startsWith("/") then value else "/" + value
            case HttpPath.Segment.Capture(name, _) => s"/{$name}"
            case HttpPath.Segment.Concat(left, right) =>
                segmentToOpenApi(left.asInstanceOf[HttpPath.Segment[?]]) +
                    segmentToOpenApi(right.asInstanceOf[HttpPath.Segment[?]])

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
            // Check if it's a simple string enum (all cases have no fields)
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

    /** Maps route auth scheme metadata to OpenAPI security scheme definitions. */
    private def collectSecuritySchemes(handlers: Seq[HttpHandler[Any]]): Map[String, SecurityScheme] =
        handlers.flatMap { handler =>
            handler.route.securityScheme.toOption.map { scheme =>
                val authParam = handler.route.headerParams.find(_.authScheme.isDefined)
                val schemeObj = authParam.flatMap(_.authScheme.toOption) match
                    case Some(HttpRoute.AuthScheme.Basic) =>
                        SecurityScheme(`type` = "http", scheme = Some("basic"), bearerFormat = None, name = None, in = None)
                    case Some(HttpRoute.AuthScheme.Bearer) =>
                        SecurityScheme(`type` = "http", scheme = Some("bearer"), bearerFormat = None, name = None, in = None)
                    case Some(HttpRoute.AuthScheme.ApiKey) =>
                        val headerName = authParam.map(_.name).getOrElse("X-API-Key")
                        SecurityScheme(
                            `type` = "apiKey",
                            scheme = None,
                            bearerFormat = None,
                            name = Some(headerName),
                            in = Some("header")
                        )
                    case None =>
                        SecurityScheme(`type` = "http", scheme = Some("bearer"), bearerFormat = None, name = None, in = None)
                scheme -> schemeObj
            }
        }.toMap
    end collectSecuritySchemes

end OpenApiGenerator
