package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("KyoException")
class JsKyoException(@JSName("$kyoe") val underlying: KyoException) extends js.Object:
    import kyo.JsFacadeGivens.given
    def frame() =
        new JsFrame(underlying.frame)

    def getCause() =
        underlying.getCause()

    def getMessage() =
        underlying.getMessage()


end JsKyoException

object JsKyoException:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def maxMessageLength() =
        KyoException.maxMessageLength


end JsKyoException