package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Histogram")
class JsHistogram(@JSName("$hist") val underlying: Histogram) extends js.Object:
    import kyo.JsFacadeGivens.given
    def count() =
        new JsKyo(underlying.count)

    def observe(v: Long) =
        new JsKyo(underlying.observe(v))

    def unsafe() =
        underlying.unsafe

    def valueAtPercentile(v: Double) =
        new JsKyo(underlying.valueAtPercentile(v))


end JsHistogram