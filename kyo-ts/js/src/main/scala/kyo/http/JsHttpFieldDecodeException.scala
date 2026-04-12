package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpFieldDecodeException")
class JsHttpFieldDecodeException(@JSName("$http") val underlying: HttpFieldDecodeException) extends js.Object:
    import kyo.JsFacadeGivens.given
    def cause() =
        underlying.cause

    def detail() =
        underlying.detail

    def fieldName() =
        underlying.fieldName

    def fieldType() =
        underlying.fieldType

    def method() =
        underlying.method

    def url() =
        underlying.url


end JsHttpFieldDecodeException