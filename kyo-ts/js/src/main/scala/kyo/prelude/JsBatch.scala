package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Batch")
class JsBatch(@JSName("$batc") val underlying: Batch) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsBatch

object JsBatch:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def eval[A](seq: Seq[A]) =
        new JsKyo(Batch.eval(seq))

    @JSExportStatic
    def foreach[A, B, S](seq: Seq[A], f: Function1[A, kyo.kernel.`<`[B, S]]) =
        new JsKyo(Batch.foreach(seq)(f))

    @JSExportStatic
    def internal() =
        Batch.internal

    @JSExportStatic
    def source[A, B, S](f: Function1[Seq[A], kyo.kernel.`<`[Function1[A, kyo.kernel.`<`[B, S]], S]]) =
        Batch.source(f)

    @JSExportStatic
    def sourceMap[A, B, S](f: Function1[Seq[A], kyo.kernel.`<`[Predef.Map[A, B], S]]) =
        Batch.sourceMap(f)

    @JSExportStatic
    def sourceSeq[A, B, S](f: Function1[Seq[A], kyo.kernel.`<`[Seq[B], S]]) =
        Batch.sourceSeq(f)


end JsBatch