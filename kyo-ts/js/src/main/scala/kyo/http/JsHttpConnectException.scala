package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpConnectException")
class JsHttpConnectException(@JSName("$http") val underlying: HttpConnectException) extends js.Object:
    import kyo.JsFacadeGivens.given
    def cause() =
        underlying.cause

    def host() =
        underlying.host

    def port() =
        underlying.port


end JsHttpConnectException