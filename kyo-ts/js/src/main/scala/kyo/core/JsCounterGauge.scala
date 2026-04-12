package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("CounterGauge")
class JsCounterGauge(@JSName("$coun") val underlying: CounterGauge) extends js.Object:
    import kyo.JsFacadeGivens.given
    def collect() =
        new JsKyo(underlying.collect)

    def unsafe() =
        underlying.unsafe


end JsCounterGauge