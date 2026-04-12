package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpStreamingDecodeException")
class JsHttpStreamingDecodeException(@JSName("$http") val underlying: HttpStreamingDecodeException) extends js.Object:
    import kyo.JsFacadeGivens.given
    def contentType() =
        underlying.contentType

    def method() =
        underlying.method

    def url() =
        underlying.url


end JsHttpStreamingDecodeException