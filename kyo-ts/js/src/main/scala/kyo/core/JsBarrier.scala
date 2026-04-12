package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Barrier")
class JsBarrier(@JSName("$barr") val underlying: Barrier) extends js.Object:
    import kyo.JsFacadeGivens.given
    def await() =
        new JsKyo(underlying.await)

    def pending() =
        new JsKyo(underlying.pending)

    def unsafe() =
        underlying.unsafe


end JsBarrier