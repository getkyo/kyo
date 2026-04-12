package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Ansi")
object JsAnsi extends js.Object:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def highlight() =
        Ansi.highlight


end JsAnsi