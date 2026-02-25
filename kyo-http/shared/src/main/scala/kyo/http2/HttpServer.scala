package kyo.http2

import kyo.<
import kyo.Absent
import kyo.Async
import kyo.Duration
import kyo.Frame
import kyo.Maybe
import kyo.Present
import kyo.Record2.~
import kyo.Scope
import kyo.http2.internal.HttpPlatformBackend
import kyo.http2.internal.OpenApiGenerator

final class HttpServer private (binding: HttpBackend.Binding):
    def port: Int                                               = binding.port
    def host: String                                            = binding.host
    def close(gracePeriod: Duration)(using Frame): Unit < Async = binding.close(gracePeriod)
    def close(using Frame): Unit < Async                        = binding.close
    def closeNow(using Frame): Unit < Async                     = binding.closeNow
    def await(using Frame): Unit < Async                        = binding.await
end HttpServer

object HttpServer:

    case class Config(
        port: Int = 0,
        host: String = "0.0.0.0",
        maxContentLength: Int = 65536,
        backlog: Int = 128,
        keepAlive: Boolean = true,
        tcpFastOpen: Boolean = true,
        flushConsolidationLimit: Int = 256,
        strictCookieParsing: Boolean = false,
        openApi: Maybe[Config.OpenApiEndpoint] = Absent
    ) derives CanEqual:
        def port(p: Int): Config                    = copy(port = p)
        def host(h: String): Config                 = copy(host = h)
        def maxContentLength(v: Int): Config        = copy(maxContentLength = v)
        def backlog(v: Int): Config                 = copy(backlog = v)
        def keepAlive(v: Boolean): Config           = copy(keepAlive = v)
        def tcpFastOpen(v: Boolean): Config         = copy(tcpFastOpen = v)
        def flushConsolidationLimit(v: Int): Config = copy(flushConsolidationLimit = v)
        def strictCookieParsing(v: Boolean): Config = copy(strictCookieParsing = v)
        def openApi(
            path: String = "/openapi.json",
            title: String = "API",
            version: String = "1.0.0",
            description: Option[String] = None
        ): Config =
            copy(openApi = Present(Config.OpenApiEndpoint(path, title, version, description)))
    end Config

    object Config:
        val default: Config = Config()

        case class OpenApiEndpoint(
            path: String = "/openapi.json",
            title: String = "API",
            version: String = "1.0.0",
            description: Option[String] = None
        ) derives CanEqual
    end Config

    def init(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < (Async & Scope) =
        init(Config.default)(handlers*)

    def init(port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < (Async & Scope) =
        init(Config(port, host))(handlers*)

    def init(config: Config)(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < (Async & Scope) =
        init(HttpPlatformBackend.server, config)(handlers*)

    def init(backend: HttpBackend.Server, config: Config)(handlers: HttpHandler[?, ?, ?]*)(using
        Frame
    ): HttpServer < (Async & Scope) =
        Scope.acquireRelease(initUnscoped(backend, config)(handlers*))(_.closeNow)

    def init(backend: HttpBackend.Server, port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(using
        Frame
    ): HttpServer < (Async & Scope) =
        init(backend, Config(port, host))(handlers*)

    def initWith[A, S](handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using Frame): A < (S & Async & Scope) =
        init(handlers*).map(f)

    def initWith[A, S](port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using
        Frame
    ): A < (S & Async & Scope) =
        init(port, host)(handlers*).map(f)

    def initWith[A, S](config: Config)(handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using
        Frame
    ): A < (S & Async & Scope) =
        init(config)(handlers*).map(f)

    def initWith[A, S](backend: HttpBackend.Server, port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(
        f: HttpServer => A < S
    )(using Frame): A < (S & Async & Scope) =
        init(backend, port, host)(handlers*).map(f)

    def initUnscoped(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < Async =
        initUnscoped(Config.default)(handlers*)

    def initUnscoped(port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < Async =
        initUnscoped(Config(port, host))(handlers*)

    def initUnscoped(config: Config)(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < Async =
        initUnscoped(HttpPlatformBackend.server, config)(handlers*)

    def initUnscoped(backend: HttpBackend.Server, port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(using
        Frame
    ): HttpServer < Async =
        initUnscoped(backend, Config(port, host))(handlers*)

    def initUnscoped(backend: HttpBackend.Server, config: Config)(handlers: HttpHandler[?, ?, ?]*)(using
        Frame
    ): HttpServer < Async =
        val allHandlers = config.openApi match
            case Present(ep) =>
                val spec = OpenApiGenerator.generate(
                    handlers,
                    OpenApiGenerator.Config(ep.title, ep.version, ep.description)
                )
                val json = OpenApi.toJson(spec)
                handlers :+ HttpHandler.getText(ep.path)(_ => json)
            case Absent =>
                handlers
        backend.bind(allHandlers, config).map(new HttpServer(_))
    end initUnscoped

    def initUnscopedWith[A, S](handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using Frame): A < (S & Async) =
        initUnscoped(handlers*).map(f)

    def initUnscopedWith[A, S](port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using
        Frame
    ): A < (S & Async) =
        initUnscoped(port, host)(handlers*).map(f)

    def initUnscopedWith[A, S](config: Config)(handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using
        Frame
    ): A < (S & Async) =
        initUnscoped(config)(handlers*).map(f)

    def initUnscopedWith[A, S](backend: HttpBackend.Server, port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(
        f: HttpServer => A < S
    )(using Frame): A < (S & Async) =
        initUnscoped(backend, port, host)(handlers*).map(f)

end HttpServer
