package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("SignalRef")
class JsSignalRef[A](@JSName("$sign") val underlying: SignalRef[A]) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsSignalRef