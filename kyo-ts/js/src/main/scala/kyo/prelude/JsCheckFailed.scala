package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("CheckFailed")
class JsCheckFailed(@JSName("$chec") val underlying: CheckFailed) extends js.Object:
    import kyo.JsFacadeGivens.given
    def frame() =
        new JsFrame(underlying.frame)

    def getMessage() =
        underlying.getMessage()

    def message() =
        underlying.message


end JsCheckFailed