package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Retry")
object JsRetry extends js.Object:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def defaultSchedule() =
        new JsSchedule(Retry.defaultSchedule)


end JsRetry