package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpClient")
class JsHttpClient(@JSName("$http") val underlying: HttpClient) extends js.Object:
    import kyo.JsFacadeGivens.given
    def close() =
        new JsKyo(underlying.close)

    def close(gracePeriod: JsDuration) =
        new JsKyo(underlying.close(gracePeriod.underlying))

    def closeNow() =
        new JsKyo(underlying.closeNow)

    def let_[A, S](v: JsKyo[A, S]) =
        new JsKyo(HttpClient.let(underlying)(v.underlying))

    def sendWith[In, Out, A](route: JsHttpRoute[In, Out, Any], request: JsHttpRequest[In], f: Function1[HttpResponse[Out], `<`[A, `&`[Async, Abort[HttpException]]]]) =
        new JsKyo(underlying.sendWith(route.underlying, request.underlying)(f))


end JsHttpClient

object JsHttpClient:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def init(backend: HttpBackend.Client, maxConnectionsPerHost: Int, idleConnectionTimeout: JsDuration) =
        new JsKyo(HttpClient.init(backend, maxConnectionsPerHost, idleConnectionTimeout.underlying))

    @JSExportStatic
    def initUnscoped(backend: HttpBackend.Client, maxConnectionsPerHost: Int, idleConnectionTimeout: JsDuration) =
        new JsKyo(HttpClient.initUnscoped(backend, maxConnectionsPerHost, idleConnectionTimeout.underlying))

    @JSExportStatic
    def update[A, S](f: Function1[HttpClient, HttpClient], v: JsKyo[A, S]) =
        new JsKyo(HttpClient.update(f)(v.underlying))

    @JSExportStatic
    def use[A, S](f: Function1[HttpClient, `<`[A, S]]) =
        new JsKyo(HttpClient.use(f))

    @JSExportStatic
    def withConfig[A, S](f: Function1[HttpClientConfig, HttpClientConfig], v: JsKyo[A, S]) =
        new JsKyo(HttpClient.withConfig(f)(v.underlying))


end JsHttpClient