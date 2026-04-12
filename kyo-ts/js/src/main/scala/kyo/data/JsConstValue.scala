package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("ConstValue")
class JsConstValue[A](@JSName("$cons") val underlying: ConstValue[A]) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsConstValue

object JsConstValue:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def given_ConstValue_A[A]() =
        new JsConstValue(ConstValue.given_ConstValue_A)


end JsConstValue