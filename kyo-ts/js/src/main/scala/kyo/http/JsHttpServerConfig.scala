package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpServerConfig")
class JsHttpServerConfig(@JSName("$http") val underlying: HttpServerConfig) extends js.Object:
    import kyo.JsFacadeGivens.given
    def backlog() =
        underlying.backlog

    def backlog(v: Int) =
        new JsHttpServerConfig(underlying.backlog(v))

    def cors() =
        new JsMaybe(underlying.cors)

    def cors(c: HttpServerConfig.Cors) =
        new JsHttpServerConfig(underlying.cors(c))

    def flushConsolidationLimit(v: Int) =
        new JsHttpServerConfig(underlying.flushConsolidationLimit(v))

    def flushConsolidationLimit() =
        underlying.flushConsolidationLimit

    def host(h: Predef.String) =
        new JsHttpServerConfig(underlying.host(h))

    def host() =
        underlying.host

    def keepAlive() =
        underlying.keepAlive

    def keepAlive(v: Boolean) =
        new JsHttpServerConfig(underlying.keepAlive(v))

    def maxContentLength() =
        underlying.maxContentLength

    def maxContentLength(v: Int) =
        new JsHttpServerConfig(underlying.maxContentLength(v))

    def openApi() =
        new JsMaybe(underlying.openApi)

    def openApi(path: Predef.String, title: Predef.String, version: Predef.String, description: Option[Predef.String]) =
        new JsHttpServerConfig(underlying.openApi(path, title, version, description))

    def port(p: Int) =
        new JsHttpServerConfig(underlying.port(p))

    def port() =
        underlying.port

    def strictCookieParsing() =
        underlying.strictCookieParsing

    def strictCookieParsing(v: Boolean) =
        new JsHttpServerConfig(underlying.strictCookieParsing(v))

    def tcpFastOpen(v: Boolean) =
        new JsHttpServerConfig(underlying.tcpFastOpen(v))

    def tcpFastOpen() =
        underlying.tcpFastOpen


end JsHttpServerConfig

object JsHttpServerConfig:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def default_() =
        new JsHttpServerConfig(HttpServerConfig.default)


end JsHttpServerConfig