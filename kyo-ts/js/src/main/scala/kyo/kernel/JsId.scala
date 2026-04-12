package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Id")
class JsId[A](@JSName("$id") val underlying: Id[A]) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsId