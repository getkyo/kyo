package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Reducible")
class JsReducible[S](@JSName("$redu") val underlying: Reducible[S]) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsReducible

object JsReducible:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def eliminate[S]() =
        Reducible.eliminate


end JsReducible