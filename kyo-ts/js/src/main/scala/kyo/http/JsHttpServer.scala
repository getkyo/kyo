package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpServer")
class JsHttpServer(@JSName("$http") val underlying: HttpServer) extends js.Object:
    import kyo.JsFacadeGivens.given
    def await() =
        new JsKyo(underlying.await)

    def close(gracePeriod: JsDuration) =
        new JsKyo(underlying.close(gracePeriod.underlying))

    def close() =
        new JsKyo(underlying.close)

    def closeNow() =
        new JsKyo(underlying.closeNow)

    def host() =
        underlying.host

    def port() =
        underlying.port


end JsHttpServer

object JsHttpServer:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def init(handlers: Seq[HttpHandler[?, ?, ?]]) =
        new JsKyo(HttpServer.init(handlers*))

    @JSExportStatic
    def init(backend: HttpBackend.Server, port: Int, host: Predef.String, handlers: Seq[HttpHandler[?, ?, ?]]) =
        new JsKyo(HttpServer.init(backend, port, host)(handlers*))

    @JSExportStatic
    def init(backend: HttpBackend.Server, config: JsHttpServerConfig, handlers: Seq[HttpHandler[?, ?, ?]]) =
        new JsKyo(HttpServer.init(backend, config.underlying)(handlers*))

    @JSExportStatic
    def initUnscoped(handlers: Seq[HttpHandler[?, ?, ?]]) =
        new JsKyo(HttpServer.initUnscoped(handlers*))

    @JSExportStatic
    def initUnscoped(backend: HttpBackend.Server, config: JsHttpServerConfig, handlers: Seq[HttpHandler[?, ?, ?]]) =
        new JsKyo(HttpServer.initUnscoped(backend, config.underlying)(handlers*))

    @JSExportStatic
    def initUnscoped(backend: HttpBackend.Server, port: Int, host: Predef.String, handlers: Seq[HttpHandler[?, ?, ?]]) =
        new JsKyo(HttpServer.initUnscoped(backend, port, host)(handlers*))

    @JSExportStatic
    def initUnscopedWith[A, S](handlers: Seq[HttpHandler[?, ?, ?]], f: Function1[HttpServer, `<`[A, S]]) =
        new JsKyo(HttpServer.initUnscopedWith(handlers*)(f))

    @JSExportStatic
    def initUnscopedWith[A, S](backend: HttpBackend.Server, port: Int, host: Predef.String, handlers: Seq[HttpHandler[?, ?, ?]], f: Function1[HttpServer, `<`[A, S]]) =
        new JsKyo(HttpServer.initUnscopedWith(backend, port, host)(handlers*)(f))

    @JSExportStatic
    def initWith[A, S](backend: HttpBackend.Server, port: Int, host: Predef.String, handlers: Seq[HttpHandler[?, ?, ?]], f: Function1[HttpServer, `<`[A, S]]) =
        new JsKyo(HttpServer.initWith(backend, port, host)(handlers*)(f))

    @JSExportStatic
    def initWith[A, S](handlers: Seq[HttpHandler[?, ?, ?]], f: Function1[HttpServer, `<`[A, S]]) =
        new JsKyo(HttpServer.initWith(handlers*)(f))


end JsHttpServer