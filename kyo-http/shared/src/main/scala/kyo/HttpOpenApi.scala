package kyo

import kyo.internal.OpenApiGenerator

case class HttpOpenApi(
    openapi: String,
    info: HttpOpenApi.Info,
    paths: Map[String, HttpOpenApi.PathItem],
    components: Option[HttpOpenApi.Components]
) derives Schema:
    def toJson: String = Schema[HttpOpenApi].encode(this)
end HttpOpenApi

object HttpOpenApi:

    // --- OpenAPI 3.0 Data Model ---
    // Note: These classes use Option for zio-schema derivation compatibility (JSON serialization)

    case class Info(
        title: String,
        version: String,
        description: Option[String]
    ) derives Schema

    case class PathItem(
        get: Option[Operation],
        post: Option[Operation],
        put: Option[Operation],
        delete: Option[Operation],
        patch: Option[Operation],
        head: Option[Operation],
        options: Option[Operation]
    ) derives Schema

    case class Operation(
        tags: Option[List[String]],
        summary: Option[String],
        description: Option[String],
        operationId: Option[String],
        deprecated: Option[Boolean],
        parameters: Option[List[Parameter]],
        requestBody: Option[RequestBody],
        responses: Map[String, Response],
        security: Option[List[Map[String, List[String]]]]
    ) derives Schema

    case class Parameter(
        name: String,
        in: String,
        required: Option[Boolean],
        schema: SchemaObject,
        description: Option[String]
    ) derives Schema

    case class RequestBody(
        required: Option[Boolean],
        content: Map[String, MediaType],
        description: Option[String]
    ) derives Schema

    case class Response(
        description: String,
        content: Option[Map[String, MediaType]]
    ) derives Schema

    case class MediaType(
        schema: SchemaObject
    ) derives Schema

    case class SchemaObject(
        `type`: Option[String],
        format: Option[String],
        items: Option[SchemaObject],
        properties: Option[Map[String, SchemaObject]],
        required: Option[List[String]],
        additionalProperties: Option[SchemaObject],
        oneOf: Option[List[SchemaObject]],
        `enum`: Option[List[String]],
        `$ref`: Option[String]
    ) derives Schema

    object SchemaObject:
        def string: SchemaObject  = SchemaObject(Some("string"), None, None, None, None, None, None, None, None)
        def integer: SchemaObject = SchemaObject(Some("integer"), Some("int32"), None, None, None, None, None, None, None)
        def long: SchemaObject    = SchemaObject(Some("integer"), Some("int64"), None, None, None, None, None, None, None)
        def number: SchemaObject  = SchemaObject(Some("number"), Some("double"), None, None, None, None, None, None, None)
        def boolean: SchemaObject = SchemaObject(Some("boolean"), None, None, None, None, None, None, None, None)
        def obj: SchemaObject     = SchemaObject(Some("object"), None, None, None, None, None, None, None, None)
        def array(items: SchemaObject): SchemaObject =
            SchemaObject(Some("array"), None, Some(items), None, None, None, None, None, None)
    end SchemaObject

    case class Components(
        schemas: Option[Map[String, SchemaObject]],
        securitySchemes: Option[Map[String, SecurityScheme]]
    ) derives Schema

    case class SecurityScheme(
        `type`: String,
        scheme: Option[String],
        bearerFormat: Option[String],
        name: Option[String],
        in: Option[String]
    ) derives Schema

    // --- Configuration ---

    case class Config(
        title: String = "API",
        version: String = "1.0.0",
        description: Maybe[String] = Absent
    )

    object Config:
        val default: Config = Config()
    end Config

    // --- Public API ---

    val defaultPath = "/openapi.json"

    def fromRoutes(routes: HttpRoute[?, ?, ?]*): HttpOpenApi =
        fromRoutes(Config.default)(routes*)

    def fromRoutes(config: Config)(routes: HttpRoute[?, ?, ?]*): HttpOpenApi =
        val handlers = routes.map(routeToHandler)
        OpenApiGenerator.generate(handlers, config)

    def fromHandlers(handlers: HttpHandler[Any]*): HttpOpenApi =
        fromHandlers(Config.default)(handlers*)

    def fromHandlers(config: Config)(handlers: HttpHandler[Any]*): HttpOpenApi =
        OpenApiGenerator.generate(handlers, config)

    def handler(routes: HttpRoute[?, ?, ?]*)(using Frame): HttpHandler[Any] =
        handler(defaultPath, Config.default)(routes*)

    def handler(path: String)(routes: HttpRoute[?, ?, ?]*)(using Frame): HttpHandler[Any] =
        handler(path, Config.default)(routes*)

    def handler(path: String, config: Config)(routes: HttpRoute[?, ?, ?]*)(using Frame): HttpHandler[Any] =
        val spec = fromRoutes(config)(routes*)
        val json = spec.toJson
        HttpHandler.get(path) { (_, _) =>
            HttpResponse.ok(json).addHeader("Content-Type", "application/json")
        }
    end handler

    private def routeToHandler(r: HttpRoute[?, ?, ?]): HttpHandler[Any] =
        new HttpHandler[Any]:
            val route: HttpRoute[?, ?, ?] = r
            def apply(request: HttpRequest): HttpResponse < Async =
                HttpResponse.ok

end HttpOpenApi
