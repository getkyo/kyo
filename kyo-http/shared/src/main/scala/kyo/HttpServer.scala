package kyo

import kyo.*
import kyo.internal.HttpPlatformBackend
import kyo.internal.OpenApiGenerator

/** HTTP server that binds handlers to a port and manages the server lifecycle.
  *
  * `HttpServer.init` returns a server managed by `Scope` — it shuts down automatically when the enclosing scope exits.
  * `HttpServer.initUnscoped` returns a server that must be closed explicitly via `close()`. Both accept one or more `HttpHandler` instances
  * as varargs.
  *
  * When `HttpServerConfig.openApi` is configured, the server automatically generates an OpenAPI 3.x spec from all registered handlers and
  * serves it at the configured path.
  *
  * Note: Port 0 tells the OS to assign an available port. After binding, the actual port is available via `server.port`.
  *
  * @see
  *   [[kyo.HttpHandler]] The endpoint implementations to register
  * @see
  *   [[kyo.HttpServerConfig]] Controls port, host, content limits, and optional features
  * @see
  *   [[kyo.HttpBackend.Server]] The platform-specific backend
  */
final class HttpServer private (binding: HttpBackend.Binding):
    def port: Int                                               = binding.port
    def host: String                                            = binding.host
    def close(gracePeriod: Duration)(using Frame): Unit < Async = binding.close(gracePeriod)
    def close(using Frame): Unit < Async                        = binding.close
    def closeNow(using Frame): Unit < Async                     = binding.closeNow
    def await(using Frame): Unit < Async                        = binding.await
end HttpServer

object HttpServer:

    def init(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < (Async & Scope) =
        init(HttpServerConfig.default)(handlers*)

    def init(port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < (Async & Scope) =
        init(HttpServerConfig.default.port(port).host(host))(handlers*)

    def init(config: HttpServerConfig)(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < (Async & Scope) =
        init(HttpPlatformBackend.server, config)(handlers*)

    def init(backend: HttpBackend.Server, config: HttpServerConfig)(handlers: HttpHandler[?, ?, ?]*)(using
        Frame
    ): HttpServer < (Async & Scope) =
        Scope.acquireRelease(initUnscoped(backend, config)(handlers*))(_.closeNow)

    def init(backend: HttpBackend.Server, port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(using
        Frame
    ): HttpServer < (Async & Scope) =
        init(backend, HttpServerConfig.default.port(port).host(host))(handlers*)

    def initWith[A, S](handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using Frame): A < (S & Async & Scope) =
        init(handlers*).map(f)

    def initWith[A, S](port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using
        Frame
    ): A < (S & Async & Scope) =
        init(port, host)(handlers*).map(f)

    def initWith[A, S](config: HttpServerConfig)(handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using
        Frame
    ): A < (S & Async & Scope) =
        init(config)(handlers*).map(f)

    def initWith[A, S](backend: HttpBackend.Server, port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(
        f: HttpServer => A < S
    )(using Frame): A < (S & Async & Scope) =
        init(backend, port, host)(handlers*).map(f)

    def initUnscoped(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < Async =
        initUnscoped(HttpServerConfig.default)(handlers*)

    def initUnscoped(port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < Async =
        initUnscoped(HttpServerConfig.default.port(port).host(host))(handlers*)

    def initUnscoped(config: HttpServerConfig)(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < Async =
        initUnscoped(HttpPlatformBackend.server, config)(handlers*)

    def initUnscoped(backend: HttpBackend.Server, port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(using
        Frame
    ): HttpServer < Async =
        initUnscoped(backend, HttpServerConfig.default.port(port).host(host))(handlers*)

    def initUnscoped(backend: HttpBackend.Server, config: HttpServerConfig)(handlers: HttpHandler[?, ?, ?]*)(using
        Frame
    ): HttpServer < Async =
        val allHandlers = config.openApi match
            case Present(ep) =>
                val spec = OpenApiGenerator.generate(
                    handlers,
                    OpenApiGenerator.Config(ep.title, ep.version, ep.description)
                )
                val json      = HttpOpenApi.toJson(spec)
                val jsonBytes = Span.fromUnsafe(json.getBytes("UTF-8"))
                handlers :+ HttpHandler.init(HttpRoute.getRaw(ep.path).response(_.bodyBinary)) { _ =>
                    HttpResponse.ok(jsonBytes).addHeader("Content-Type", "application/json")
                }
            case Absent =>
                handlers
        backend.bind(allHandlers, config).map(new HttpServer(_))
    end initUnscoped

    def initUnscopedWith[A, S](handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using Frame): A < (S & Async & Scope) =
        initUnscoped(handlers*).map(f)

    def initUnscopedWith[A, S](port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using
        Frame
    ): A < (S & Async & Scope) =
        initUnscoped(port, host)(handlers*).map(f)

    def initUnscopedWith[A, S](config: HttpServerConfig)(handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using
        Frame
    ): A < (S & Async & Scope) =
        initUnscoped(config)(handlers*).map(f)

    def initUnscopedWith[A, S](backend: HttpBackend.Server, port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(
        f: HttpServer => A < S
    )(using Frame): A < (S & Async & Scope) =
        initUnscoped(backend, port, host)(handlers*).map(f)

end HttpServer
