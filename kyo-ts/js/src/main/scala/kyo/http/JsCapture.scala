package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Capture")
class JsCapture[N, A](@JSName("$capt") val underlying: Capture[N, A]) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsCapture