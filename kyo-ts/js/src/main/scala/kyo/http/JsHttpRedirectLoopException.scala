package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpRedirectLoopException")
class JsHttpRedirectLoopException(@JSName("$http") val underlying: HttpRedirectLoopException) extends js.Object:
    import kyo.JsFacadeGivens.given
    def chain() =
        new JsChunk(underlying.chain)

    def count() =
        underlying.count

    def method() =
        underlying.method

    def url() =
        underlying.url


end JsHttpRedirectLoopException