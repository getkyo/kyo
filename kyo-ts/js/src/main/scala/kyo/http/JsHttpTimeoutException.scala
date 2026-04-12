package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpTimeoutException")
class JsHttpTimeoutException(@JSName("$http") val underlying: HttpTimeoutException) extends js.Object:
    import kyo.JsFacadeGivens.given
    def duration() =
        new JsDuration(underlying.duration)

    def method() =
        underlying.method

    def url() =
        underlying.url


end JsHttpTimeoutException