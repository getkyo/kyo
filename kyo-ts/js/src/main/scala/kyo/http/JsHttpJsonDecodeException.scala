package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpJsonDecodeException")
class JsHttpJsonDecodeException(@JSName("$http") val underlying: HttpJsonDecodeException) extends js.Object:
    import kyo.JsFacadeGivens.given
    def detail() =
        underlying.detail

    def method() =
        underlying.method

    def url() =
        underlying.url


end JsHttpJsonDecodeException