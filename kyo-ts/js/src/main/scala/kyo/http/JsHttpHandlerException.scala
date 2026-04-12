package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpHandlerException")
class JsHttpHandlerException(@JSName("$http") val underlying: HttpHandlerException) extends js.Object:
    import kyo.JsFacadeGivens.given
    def error() =
        underlying.error


end JsHttpHandlerException

object JsHttpHandlerException:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def apply(error: Any) =
        new JsHttpHandlerException(HttpHandlerException.apply(error))


end JsHttpHandlerException