package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("AllowUnsafe")
class JsAllowUnsafe(@JSName("$allo") val underlying: AllowUnsafe) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsAllowUnsafe

object JsAllowUnsafe:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def embrace() =
        AllowUnsafe.embrace


end JsAllowUnsafe