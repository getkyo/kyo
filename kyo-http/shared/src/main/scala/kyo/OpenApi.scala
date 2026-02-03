package kyo

import kyo.internal.OpenApiGenerator

/** Public API for OpenAPI spec generation from routes and handlers. */
object OpenApi:

    /** Default path for serving OpenAPI spec. */
    val defaultPath = "/openapi.json"

    // Re-export types for public use
    type Spec           = OpenApiGenerator.OpenApi
    type Info           = OpenApiGenerator.Info
    type PathItem       = OpenApiGenerator.PathItem
    type Operation      = OpenApiGenerator.Operation
    type Parameter      = OpenApiGenerator.Parameter
    type RequestBody    = OpenApiGenerator.RequestBody
    type Response       = OpenApiGenerator.Response
    type MediaType      = OpenApiGenerator.MediaType
    type SchemaObject   = OpenApiGenerator.SchemaObject
    type Components     = OpenApiGenerator.Components
    type SecurityScheme = OpenApiGenerator.SecurityScheme

    /** Configuration for OpenAPI spec generation. */
    case class Config(
        title: String = "API",
        version: String = "1.0.0",
        description: Maybe[String] = Absent
    )

    object Config:
        val default: Config = Config()
    end Config

    /** Generate OpenAPI spec from routes. */
    def fromRoutes(routes: HttpRoute[?, ?, ?]*): Spec =
        fromRoutes(Config.default)(routes*)

    /** Generate OpenAPI spec from routes with custom config. */
    def fromRoutes(config: Config)(routes: HttpRoute[?, ?, ?]*): Spec =
        // Create minimal handlers from routes for spec generation
        val handlers = routes.map(routeToHandler)
        OpenApiGenerator.generate(handlers, toInternalConfig(config))
    end fromRoutes

    /** Generate OpenAPI spec from handlers. */
    def fromHandlers(handlers: HttpHandler[Any]*): Spec =
        fromHandlers(Config.default)(handlers*)

    /** Generate OpenAPI spec from handlers with custom config. */
    def fromHandlers(config: Config)(handlers: HttpHandler[Any]*): Spec =
        OpenApiGenerator.generate(handlers, toInternalConfig(config))

    /** Encode OpenAPI spec to JSON string. */
    def toJson(spec: Spec): String =
        Schema[OpenApiGenerator.OpenApi].encode(spec)

    /** Create a handler that serves the OpenAPI spec at the given path.
      *
      * Usage:
      * {{{
      * val routes = Seq(route1, route2, ...)
      * val handlers = routes.map(_.handle(...))
      * val allHandlers = handlers :+ OpenApi.handler(routes*)
      * HttpServer.init(allHandlers*)
      * }}}
      */
    def handler(routes: HttpRoute[?, ?, ?]*)(using Frame): HttpHandler[Any] =
        handler(defaultPath, Config.default)(routes*)

    /** Create a handler that serves the OpenAPI spec at the given path. */
    def handler(path: String)(routes: HttpRoute[?, ?, ?]*)(using Frame): HttpHandler[Any] =
        handler(path, Config.default)(routes*)

    /** Create a handler that serves the OpenAPI spec at the given path with custom config. */
    def handler(path: String, config: Config)(routes: HttpRoute[?, ?, ?]*)(using Frame): HttpHandler[Any] =
        val spec = fromRoutes(config)(routes*)
        val json = toJson(spec)
        HttpHandler.get(path) { (_, _) =>
            HttpResponse.ok(json).addHeader("Content-Type", "application/json")
        }
    end handler

    private def toInternalConfig(config: Config): OpenApiGenerator.Config =
        OpenApiGenerator.Config(config.title, config.version, config.description)

    private def routeToHandler(r: HttpRoute[?, ?, ?]): HttpHandler[Any] =
        new HttpHandler[Any]:
            val route: HttpRoute[?, ?, ?] = r
            def apply(request: HttpRequest): HttpResponse < Async =
                // Stub implementation - only used for spec generation
                HttpResponse.ok

end OpenApi
