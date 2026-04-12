package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Const")
class JsConst[A](@JSName("$cons") val underlying: Const[A]) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsConst