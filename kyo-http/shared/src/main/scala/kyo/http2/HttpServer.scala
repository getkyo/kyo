package kyo.http2

import kyo.<
import kyo.Async
import kyo.Duration
import kyo.Frame
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

    def init(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < (Async & Scope) =
        init(0, "0.0.0.0")(handlers*)

    def init(port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < (Async & Scope) =
        init(HttpPlatformBackend.server, port, host)(handlers*)

    def init(backend: HttpBackend.Server, port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(using
        Frame
    ): HttpServer < (Async & Scope) =
        Scope.acquireRelease(initUnscoped(backend, port, host)(handlers*))(_.closeNow)

    def initWith[A, S](handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using Frame): A < (S & Async & Scope) =
        init(handlers*).map(f)

    def initWith[A, S](port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using
        Frame
    ): A < (S & Async & Scope) =
        init(port, host)(handlers*).map(f)

    def initWith[A, S](backend: HttpBackend.Server, port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(
        f: HttpServer => A < S
    )(using Frame): A < (S & Async & Scope) =
        init(backend, port, host)(handlers*).map(f)

    def initUnscoped(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < Async =
        initUnscoped(0, "0.0.0.0")(handlers*)

    def initUnscoped(port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(using Frame): HttpServer < Async =
        initUnscoped(HttpPlatformBackend.server, port, host)(handlers*)

    def initUnscoped(backend: HttpBackend.Server, port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(using
        Frame
    ): HttpServer < Async =
        backend.bind(handlers, port, host).map(new HttpServer(_))

    def initUnscopedWith[A, S](handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using Frame): A < (S & Async) =
        initUnscoped(handlers*).map(f)

    def initUnscopedWith[A, S](port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(f: HttpServer => A < S)(using
        Frame
    ): A < (S & Async) =
        initUnscoped(port, host)(handlers*).map(f)

    def initUnscopedWith[A, S](backend: HttpBackend.Server, port: Int, host: String)(handlers: HttpHandler[?, ?, ?]*)(
        f: HttpServer => A < S
    )(using Frame): A < (S & Async) =
        initUnscoped(backend, port, host)(handlers*).map(f)

end HttpServer
