package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Admission")
object JsAdmission extends js.Object:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def reject() =
        new JsKyo(Admission.reject())


end JsAdmission