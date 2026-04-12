package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Present")
class JsPresent[A](@JSName("$pres") val underlying: Present[A]) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsPresent