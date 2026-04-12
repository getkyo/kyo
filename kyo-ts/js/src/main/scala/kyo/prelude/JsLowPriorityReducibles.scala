package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("LowPriorityReducibles")
class JsLowPriorityReducibles(@JSName("$lowp") val underlying: LowPriorityReducibles) extends js.Object:
    import kyo.JsFacadeGivens.given
    def irreducible[S]() =
        underlying.irreducible


end JsLowPriorityReducibles