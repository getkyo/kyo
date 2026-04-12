package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpBindException")
class JsHttpBindException(@JSName("$http") val underlying: HttpBindException) extends js.Object:
    import kyo.JsFacadeGivens.given
    def cause() =
        underlying.cause

    def host() =
        underlying.host

    def port() =
        underlying.port


end JsHttpBindException