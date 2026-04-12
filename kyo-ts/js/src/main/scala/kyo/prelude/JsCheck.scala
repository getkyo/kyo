package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Check")
class JsCheck(@JSName("$chec") val underlying: Check) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsCheck

object JsCheck:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def isolate() =
        Check.isolate


end JsCheck