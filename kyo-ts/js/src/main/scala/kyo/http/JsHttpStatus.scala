package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpStatus")
class JsHttpStatus(@JSName("$http") val underlying: HttpStatus) extends js.Object:
    import kyo.JsFacadeGivens.given
    def code() =
        underlying.code

    def isClientError() =
        underlying.isClientError

    def isError() =
        underlying.isError

    def isInformational() =
        underlying.isInformational

    def isRedirect() =
        underlying.isRedirect

    def isServerError() =
        underlying.isServerError

    def isSuccess() =
        underlying.isSuccess

    def name() =
        underlying.name


end JsHttpStatus