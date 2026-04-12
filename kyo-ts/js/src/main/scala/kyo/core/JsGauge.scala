package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Gauge")
class JsGauge(@JSName("$gaug") val underlying: Gauge) extends js.Object:
    import kyo.JsFacadeGivens.given
    def collect() =
        new JsKyo(underlying.collect)

    def unsafe() =
        underlying.unsafe


end JsGauge