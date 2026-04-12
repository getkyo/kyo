package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Isolate")
class JsIsolate[Remove, Keep, Restore](@JSName("$isol") val underlying: Isolate[Remove, Keep, Restore]) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsIsolate