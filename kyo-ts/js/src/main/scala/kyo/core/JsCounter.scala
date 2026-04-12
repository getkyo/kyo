package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Counter")
class JsCounter(@JSName("$coun") val underlying: Counter) extends js.Object:
    import kyo.JsFacadeGivens.given
    def add(v: Long) =
        new JsKyo(underlying.add(v))

    def get() =
        new JsKyo(underlying.get)

    def inc() =
        new JsKyo(underlying.inc)

    def unsafe() =
        underlying.unsafe


end JsCounter