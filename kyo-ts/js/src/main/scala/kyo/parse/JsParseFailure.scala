package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("ParseFailure")
class JsParseFailure(@JSName("$pars") val underlying: ParseFailure) extends js.Object:
    import kyo.JsFacadeGivens.given
    def message() =
        underlying.message

    def position() =
        underlying.position


end JsParseFailure