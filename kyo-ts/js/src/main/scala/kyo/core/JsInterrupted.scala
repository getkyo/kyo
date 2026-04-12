package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Interrupted")
class JsInterrupted(@JSName("$inte") val underlying: Interrupted) extends js.Object:
    import kyo.JsFacadeGivens.given
    def at() =
        new JsFrame(underlying.at)

    def by() =
        new JsMaybe(underlying.by)


end JsInterrupted