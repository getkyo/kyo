package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpFormDecodeException")
class JsHttpFormDecodeException(@JSName("$http") val underlying: HttpFormDecodeException) extends js.Object:
    import kyo.JsFacadeGivens.given
    def cause() =
        underlying.cause

    def detail() =
        underlying.detail

    def method() =
        underlying.method

    def url() =
        underlying.url


end JsHttpFormDecodeException