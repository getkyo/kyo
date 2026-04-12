package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("DoubleAdder")
class JsDoubleAdder(@JSName("$doub") val underlying: DoubleAdder) extends js.Object:
    import kyo.JsFacadeGivens.given
    def add(v: Double) =
        new JsKyo(underlying.add(v))

    def get() =
        new JsKyo(underlying.get)

    def reset() =
        new JsKyo(underlying.reset)

    def sumThenReset() =
        new JsKyo(underlying.sumThenReset)

    def unsafe() =
        underlying.unsafe


end JsDoubleAdder

object JsDoubleAdder:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def init() =
        new JsKyo(DoubleAdder.init)

    @JSExportStatic
    def initWith[A, S](f: Function1[DoubleAdder, `<`[A, S]]) =
        new JsKyo(DoubleAdder.initWith(f))


end JsDoubleAdder