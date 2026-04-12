package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpUrl")
class JsHttpUrl(@JSName("$http") val underlying: HttpUrl) extends js.Object:
    import kyo.JsFacadeGivens.given
    def baseUrl() =
        underlying.baseUrl

    def full() =
        underlying.full

    def host() =
        underlying.host

    def path() =
        underlying.path

    def port() =
        underlying.port

    def query(name: Predef.String) =
        new JsMaybe(underlying.query(name))

    def queryAll(name: Predef.String) =
        underlying.queryAll(name)

    def rawQuery() =
        new JsMaybe(underlying.rawQuery)

    def scheme() =
        new JsMaybe(underlying.scheme)

    def ssl() =
        underlying.ssl


end JsHttpUrl