package kyo

import kyo.internal.OpenApiGenerator

/** OpenAPI 3.0 specification generated from route and handler definitions.
  *
  * Produces a JSON-serializable OpenAPI spec from `HttpRoute` or `HttpHandler` metadata. Can be generated programmatically via
  * `fromRoutes`/`fromHandlers`, served from a running `HttpServer` via `server.openApi`, or auto-served by configuring
  * `HttpServer.Config.openApi`. The spec includes paths, parameters, request/response bodies, and error responses derived from route
  * definitions.
  *
  * @see
  *   [[kyo.HttpRoute]]
  * @see
  *   [[kyo.HttpHandler]]
  * @see
  *   [[kyo.HttpServer]]
  */
case class HttpOpenApi(
    openapi: String,
    info: HttpOpenApi.Info,
    paths: Map[String, HttpOpenApi.PathItem],
    components: Option[HttpOpenApi.Components]
) derives Schema, CanEqual:
    /** Serializes the spec to a JSON string. */
    def toJson: String = Schema[HttpOpenApi].encode(this)
end HttpOpenApi

object HttpOpenApi:

    // --- OpenAPI 3.0 Data Model ---
    // Note: These classes use Option for zio-schema derivation compatibility (JSON serialization)

    case class Info(
        title: String,
        version: String,
        description: Option[String]
    ) derives Schema, CanEqual

    case class PathItem(
        get: Option[Operation],
        post: Option[Operation],
        put: Option[Operation],
        delete: Option[Operation],
        patch: Option[Operation],
        head: Option[Operation],
        options: Option[Operation]
    ) derives Schema, CanEqual

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
    ) derives Schema, CanEqual

    case class Parameter(
        name: String,
        in: String,
        required: Option[Boolean],
        schema: SchemaObject,
        description: Option[String]
    ) derives Schema, CanEqual

    case class RequestBody(
        required: Option[Boolean],
        content: Map[String, MediaType],
        description: Option[String]
    ) derives Schema, CanEqual

    case class Response(
        description: String,
        content: Option[Map[String, MediaType]]
    ) derives Schema, CanEqual

    case class MediaType(
        schema: SchemaObject
    ) derives Schema, CanEqual

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
    ) derives Schema, CanEqual

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
    ) derives Schema, CanEqual

    case class SecurityScheme(
        `type`: String,
        scheme: Option[String],
        bearerFormat: Option[String],
        name: Option[String],
        in: Option[String]
    ) derives Schema, CanEqual

    // --- Configuration ---

    case class Config(
        title: String = "API",
        version: String = "1.0.0",
        description: Maybe[String] = Absent
    ) derives CanEqual

    object Config:
        val default: Config = Config()
    end Config

    // --- Public API ---

    val defaultPath = "/openapi.json"

    /** Generates a spec from route definitions. */
    def fromRoutes(routes: HttpRoute[?, ?, ?, ?]*): HttpOpenApi =
        fromRoutes(Config.default)(routes*)

    def fromRoutes(config: Config)(routes: HttpRoute[?, ?, ?, ?]*): HttpOpenApi =
        val handlers = routes.map(routeToHandler)
        OpenApiGenerator.generate(handlers, config)

    /** Generates a spec from handler definitions. */
    def fromHandlers(handlers: HttpHandler[?]*): HttpOpenApi =
        fromHandlers(Config.default)(handlers*)

    def fromHandlers(config: Config)(handlers: HttpHandler[?]*): HttpOpenApi =
        OpenApiGenerator.generate(handlers, config)

    /** Creates an HttpHandler that serves the generated spec as JSON at the given path. */
    def handler(routes: HttpRoute[?, ?, ?, ?]*)(using Frame): HttpHandler[?] =
        handler(defaultPath, Config.default)(routes*)

    def handler(path: String)(routes: HttpRoute[?, ?, ?, ?]*)(using Frame): HttpHandler[?] =
        handler(path, Config.default)(routes*)

    def handler(path: String, config: Config)(routes: HttpRoute[?, ?, ?, ?]*)(using Frame): HttpHandler[?] =
        val spec = fromRoutes(config)(routes*)
        val json = spec.toJson
        HttpHandler.get(path) { _ =>
            HttpResponse.ok(json).setHeader("Content-Type", "application/json")
        }
    end handler

    private def routeToHandler(r: HttpRoute[?, ?, ?, ?]): HttpHandler[?] =
        HttpHandler.stub(r)

end HttpOpenApi
