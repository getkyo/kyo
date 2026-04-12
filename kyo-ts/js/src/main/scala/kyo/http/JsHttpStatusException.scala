package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpStatusException")
class JsHttpStatusException(@JSName("$http") val underlying: HttpStatusException) extends js.Object:
    import kyo.JsFacadeGivens.given
    def method() =
        underlying.method

    def status() =
        new JsHttpStatus(underlying.status)

    def url() =
        underlying.url


end JsHttpStatusException