package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("RecoverStrategy")
class JsRecoverStrategy[In, Out](@JSName("$reco") val underlying: RecoverStrategy[In, Out]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def apply(failedParser: JsKyo[Out, Parse[In]]) =
        new JsKyo(underlying.apply(failedParser.underlying))


end JsRecoverStrategy

object JsRecoverStrategy:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def nestedDelimiters[In, Out](left: In, right: In, others: Seq[Tuple2[In, In]], fallback: Out) =
        new JsRecoverStrategy(RecoverStrategy.nestedDelimiters(left, right, others, fallback))


end JsRecoverStrategy