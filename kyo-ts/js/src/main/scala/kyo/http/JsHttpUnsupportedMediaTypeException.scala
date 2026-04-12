package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpUnsupportedMediaTypeException")
class JsHttpUnsupportedMediaTypeException(@JSName("$http") val underlying: HttpUnsupportedMediaTypeException) extends js.Object:
    import kyo.JsFacadeGivens.given
    def actual() =
        new JsMaybe(underlying.actual)

    def expected() =
        underlying.expected

    def method() =
        underlying.method

    def url() =
        underlying.url


end JsHttpUnsupportedMediaTypeException