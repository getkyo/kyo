package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("STM")
class JsSTM(@JSName("$stm") val underlying: STM) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsSTM

object JsSTM:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def defaultRetrySchedule() =
        new JsSchedule(STM.defaultRetrySchedule)

    @JSExportStatic
    def retry() =
        new JsKyo(STM.retry)


end JsSTM