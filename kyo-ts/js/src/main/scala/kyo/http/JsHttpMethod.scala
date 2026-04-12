package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpMethod")
class JsHttpMethod(@JSName("$http") val underlying: HttpMethod) extends js.Object:
    import kyo.JsFacadeGivens.given
    def name() =
        HttpMethod.name(underlying)


end JsHttpMethod

object JsHttpMethod:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def given_CanEqual_HttpMethod_HttpMethod() =
        HttpMethod.given_CanEqual_HttpMethod_HttpMethod


end JsHttpMethod