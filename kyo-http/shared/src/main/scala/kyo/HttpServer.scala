package kyo

import kyo.internal.HttpRouter

/** HTTP server for routing incoming requests to handlers with automatic path matching, filter chains, and OpenAPI generation.
  *
  * Server binds to a port, routes incoming requests to `HttpHandler` instances using prefix-trie matching, and applies the active
  * `HttpFilter` chain. Routing handles method dispatch, path parameter extraction, and 404/405 responses automatically.
  *
  * Supports both buffered and streaming request bodies — handlers opt in to streaming via `HttpHandler.streamingBody`. The backend reads
  * the full body only for non-streaming handlers, avoiding unnecessary buffering. OpenAPI spec generation is built in — call `openApi` on a
  * running server or configure `Config.openApi` to auto-serve the spec at a path.
  *
  *   - Prefix-trie routing with path parameter extraction
  *   - Automatic 404 (Not Found) and 405 (Method Not Allowed) responses
  *   - Buffered and streaming request body support
  *   - Built-in OpenAPI 3.0 spec generation from handler metadata
  *   - Filter chain applied to every request
  *   - Configurable backlog, keep-alive, TCP fast open, idle timeout
  *
  * IMPORTANT: The server runs in the background after `init`. Use `await` to keep the main fiber alive if there's nothing else to block on.
  *
  * IMPORTANT: The filter chain is captured from `Local` at init time, not re-read per request. Set filters (via `HttpFilter.enable`) before
  * calling `HttpServer.init`.
  *
  * Note: The server reads the full request body before dispatching to non-streaming handlers. For large uploads, use
  * `HttpHandler.streamingBody`.
  *
  * Note: `port = 0` binds to a random available port. Read the actual port from `server.port`.
  *
  * @see
  *   [[kyo.HttpHandler]]
  * @see
  *   [[kyo.HttpFilter]]
  * @see
  *   [[kyo.HttpRoute]]
  * @see
  *   [[kyo.HttpServer.Config]]
  * @see
  *   [[kyo.HttpOpenApi]]
  * @see
  *   [[kyo.Backend]]
  */
final class HttpServer private (
    private val backendServer: Backend.Server.Binding,
    private val handlers: Seq[HttpHandler[?]]
):
    def port: Int    = backendServer.port
    def host: String = backendServer.host

    def closeNow(using Frame): Unit < Async =
        close(Duration.Zero)

    def close(using Frame): Unit < Async =
        close(30.seconds)

    def close(gracePeriod: Duration)(using Frame): Unit < Async =
        backendServer.close(gracePeriod)

    /** Suspends until the server shuts down. Use to keep the main fiber alive. */
    def await(using Frame): Unit < Async =
        backendServer.await

    /** Generates an OpenAPI 3.0 spec from the server's registered handlers. */
    def openApi: HttpOpenApi =
        HttpOpenApi.fromHandlers(handlers*)

    def openApi(title: String, version: String = "1.0.0", description: Maybe[String] = Absent): HttpOpenApi =
        HttpOpenApi.fromHandlers(HttpOpenApi.Config(title, version, description))(handlers*)

end HttpServer

object HttpServer:

    // --- Factory methods ---

    /** Scope-managed server lifecycle. Automatically shuts down on scope exit. */
    def init(handlers: HttpHandler[?]*)(using Frame): HttpServer < (Async & Scope) =
        init(Config.default)(handlers*)

    def init(config: Config)(handlers: HttpHandler[?]*)(using Frame): HttpServer < (Async & Scope) =
        init(config, HttpPlatformBackend.server)(handlers*)

    def init(config: Config, backend: Backend.Server)(handlers: HttpHandler[?]*)(using Frame): HttpServer < (Async & Scope) =
        Scope.acquireRelease(initUnscoped(config, backend)(handlers*))(_.closeNow)

    def init(
        port: Int = Config.default.port,
        host: String = Config.default.host,
        maxContentLength: Int = Config.default.maxContentLength,
        idleTimeout: Duration = Config.default.idleTimeout
    )(handlers: HttpHandler[?]*)(using Frame): HttpServer < (Async & Scope) =
        init(Config(port, host, maxContentLength, idleTimeout))(handlers*)

    def initWith[B, S](handlers: HttpHandler[?]*)(f: HttpServer => B < S)(using Frame): B < (S & Async & Scope) =
        init(handlers*).map(f)

    def initWith[B, S](config: Config)(handlers: HttpHandler[?]*)(f: HttpServer => B < S)(using Frame): B < (S & Async & Scope) =
        init(config)(handlers*).map(f)

    def initWith[B, S](config: Config, backend: Backend.Server)(handlers: HttpHandler[?]*)(f: HttpServer => B < S)(using
        Frame
    ): B < (S & Async & Scope) =
        init(config, backend)(handlers*).map(f)

    /** No automatic shutdown. Caller must close explicitly. */
    def initUnscoped(handlers: HttpHandler[?]*)(using Frame): HttpServer < Async =
        initUnscoped(Config.default)(handlers*)

    def initUnscoped(config: Config)(handlers: HttpHandler[?]*)(using Frame): HttpServer < Async =
        initUnscoped(config, HttpPlatformBackend.server)(handlers*)

    def initUnscoped(config: Config, backend: Backend.Server)(handlers: HttpHandler[?]*)(using Frame): HttpServer < Async =
        val h = handlers
        // Filter chain is captured once here — not re-read per request
        HttpFilter.use { filter =>
            val allHandlers = config.openApi match
                case Present(openApiConfig) =>
                    val spec = HttpOpenApi.fromHandlers(
                        HttpOpenApi.Config(openApiConfig.title, openApiConfig.version, openApiConfig.description)
                    )(h*)
                    val json = spec.toJson
                    val openApiHandler = HttpHandler.get(openApiConfig.path) { _ =>
                        HttpResponse.ok(json).setHeader("Content-Type", "application/json")
                    }
                    h :+ openApiHandler
                case Absent =>
                    h

            // ServerHandler bridges the router + filter into the Backend's transport layer
            val serverHandler = HttpServer.buildServerHandler(allHandlers, config, filter)

            backend.server(
                config.port,
                config.host,
                config.maxContentLength,
                config.backlog,
                config.keepAlive,
                config.tcpFastOpen,
                config.flushConsolidationLimit,
                serverHandler
            ).map { srv =>
                new HttpServer(srv, allHandlers)
            }
        }
    end initUnscoped

    def initUnscoped(
        port: Int = Config.default.port,
        host: String = Config.default.host,
        maxContentLength: Int = Config.default.maxContentLength,
        idleTimeout: Duration = Config.default.idleTimeout
    )(handlers: HttpHandler[?]*)(using Frame): HttpServer < Async =
        initUnscoped(Config(port, host, maxContentLength, idleTimeout))(handlers*)

    def initUnscopedWith[B, S](handlers: HttpHandler[?]*)(f: HttpServer => B < S)(using Frame): B < (S & Async) =
        initUnscoped(handlers*).map(f)

    def initUnscopedWith[B, S](config: Config)(handlers: HttpHandler[?]*)(f: HttpServer => B < S)(using Frame): B < (S & Async) =
        initUnscoped(config)(handlers*).map(f)

    def initUnscopedWith[B, S](config: Config, backend: Backend.Server)(handlers: HttpHandler[?]*)(f: HttpServer => B < S)(using
        Frame
    ): B < (S & Async) =
        initUnscoped(config, backend)(handlers*).map(f)

    // --- Config ---

    /** Server binding and transport configuration. */
    case class Config(
        port: Int = 8080,
        host: String = "0.0.0.0",
        maxContentLength: Int = 65536,
        idleTimeout: Duration = 60.seconds,
        strictCookieParsing: Boolean = false,
        backlog: Int = 128,
        keepAlive: Boolean = true,
        tcpFastOpen: Boolean = false,
        flushConsolidationLimit: Int = 256,
        openApi: Maybe[Config.OpenApiEndpoint] = Absent
    ) derives CanEqual:
        require(port >= 0 && port <= 65535, s"Port must be between 0 and 65535: $port")
        require(host.nonEmpty, "Host cannot be empty")
        require(maxContentLength > 0, s"maxContentLength must be positive: $maxContentLength")
        require(idleTimeout > Duration.Zero, s"idleTimeout must be positive: $idleTimeout")
        require(backlog > 0, s"backlog must be positive: $backlog")
        require(flushConsolidationLimit > 0, s"flushConsolidationLimit must be positive: $flushConsolidationLimit")

        def port(p: Int): Config                    = copy(port = p)
        def host(h: String): Config                 = copy(host = h)
        def maxContentLength(n: Int): Config        = copy(maxContentLength = n)
        def idleTimeout(d: Duration): Config        = copy(idleTimeout = d)
        def strictCookieParsing(b: Boolean): Config = copy(strictCookieParsing = b)
        def backlog(n: Int): Config                 = copy(backlog = n)
        def keepAlive(b: Boolean): Config           = copy(keepAlive = b)
        def tcpFastOpen(b: Boolean): Config         = copy(tcpFastOpen = b)
        def flushConsolidationLimit(n: Int): Config = copy(flushConsolidationLimit = n)
        def openApi(path: String = "/openapi.json", title: String = "API", version: String = "1.0.0"): Config =
            copy(openApi = Present(Config.OpenApiEndpoint(path, title, version)))
    end Config

    object Config:
        val default: Config = Config()

        case class OpenApiEndpoint(
            path: String = "/openapi.json",
            title: String = "API",
            version: String = "1.0.0",
            description: Maybe[String] = Absent
        ) derives CanEqual
    end Config

    // --- Private: build a Backend.ServerHandler from handlers + filter ---

    private[kyo] def buildServerHandler(
        handlers: Seq[HttpHandler[?]],
        config: Config,
        filter: HttpFilter
    )(using Frame): Backend.ServerHandler =
        val router = HttpRouter(handlers)

        new Backend.ServerHandler:

            private def errorResponse(error: HttpRouter.FindError): HttpResponse[HttpBody.Bytes] =
                error match
                    case HttpRouter.FindError.MethodNotAllowed(allowed) =>
                        val allowHeader = allowed.map(_.name).mkString(", ")
                        HttpResponse(HttpStatus.MethodNotAllowed).setHeader("Allow", allowHeader)
                    case HttpRouter.FindError.NotFound =>
                        HttpResponse.notFound

            def reject(method: HttpRequest.Method, path: String): Maybe[HttpResponse[HttpBody.Bytes]] =
                router.find(method, path) match
                    case Result.Success(_) => Absent
                    case Result.Failure(e) => Present(errorResponse(e))
                    case Result.Panic(e)   => Present(HttpResponse.serverError(e.getMessage))

            def isStreaming(method: HttpRequest.Method, path: String): Boolean =
                router.find(method, path) match
                    case Result.Success(handler) => handler.streamingRequest
                    case _                       => false

            def handle(request: HttpRequest[HttpBody.Bytes])(using Frame): HttpResponse[?] < Async =
                router.find(request.method, request.path) match
                    case Result.Success(handler) =>
                        val req = if config.strictCookieParsing then request.withStrictCookieParsing(true) else request
                        filter(req, handler.asInstanceOf[HttpHandler[Any]].apply)
                    case Result.Failure(e) => errorResponse(e)
                    case Result.Panic(e)   => HttpResponse.serverError(e.getMessage)
                end match
            end handle

            def handleStreaming(request: HttpRequest[HttpBody.Streamed])(using Frame): HttpResponse[?] < Async =
                router.find(request.method, request.path) match
                    case Result.Success(handler) =>
                        filter(request, handler.asInstanceOf[HttpHandler[Any]].apply)
                    case Result.Failure(e) => errorResponse(e)
                    case Result.Panic(e)   => HttpResponse.serverError(e.getMessage)
                end match
            end handleStreaming
        end new
    end buildServerHandler

end HttpServer
