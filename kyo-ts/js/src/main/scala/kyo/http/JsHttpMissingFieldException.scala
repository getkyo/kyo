package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpMissingFieldException")
class JsHttpMissingFieldException(@JSName("$http") val underlying: HttpMissingFieldException) extends js.Object:
    import kyo.JsFacadeGivens.given
    def fieldName() =
        underlying.fieldName

    def fieldType() =
        underlying.fieldType

    def method() =
        underlying.method

    def url() =
        underlying.url


end JsHttpMissingFieldException