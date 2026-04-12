package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("LongAdder")
class JsLongAdder(@JSName("$long") val underlying: LongAdder) extends js.Object:
    import kyo.JsFacadeGivens.given
    def add(v: Long) =
        new JsKyo(underlying.add(v))

    def decrement() =
        new JsKyo(underlying.decrement)

    def get() =
        new JsKyo(underlying.get)

    def increment() =
        new JsKyo(underlying.increment)

    def reset() =
        new JsKyo(underlying.reset)

    def sumThenReset() =
        new JsKyo(underlying.sumThenReset)

    def unsafe() =
        underlying.unsafe


end JsLongAdder

object JsLongAdder:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def init() =
        new JsKyo(LongAdder.init)

    @JSExportStatic
    def initWith[A, S](f: Function1[LongAdder, `<`[A, S]]) =
        new JsKyo(LongAdder.initWith(f))


end JsLongAdder