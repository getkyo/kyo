package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpUrlParseException")
class JsHttpUrlParseException(@JSName("$http") val underlying: HttpUrlParseException) extends js.Object:
    import kyo.JsFacadeGivens.given
    def cause() =
        underlying.cause

    def detail() =
        underlying.detail

    def url() =
        underlying.url


end JsHttpUrlParseException