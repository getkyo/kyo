package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Latch")
class JsLatch(@JSName("$latc") val underlying: Latch) extends js.Object:
    import kyo.JsFacadeGivens.given
    def await() =
        new JsKyo(underlying.await)

    def pending() =
        new JsKyo(underlying.pending)

    def release() =
        new JsKyo(underlying.release)

    def unsafe() =
        underlying.unsafe


end JsLatch