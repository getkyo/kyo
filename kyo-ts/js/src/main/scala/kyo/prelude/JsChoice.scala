package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Choice")
class JsChoice(@JSName("$choi") val underlying: Choice) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsChoice

object JsChoice:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def drop() =
        new JsKyo(Choice.drop)

    @JSExportStatic
    def eval[A](a: Seq[A]) =
        new JsKyo(Choice.eval(a*))

    @JSExportStatic
    def evalSeq[A](seq: Seq[A]) =
        new JsKyo(Choice.evalSeq(seq))

    @JSExportStatic
    def evalWith[A, B, S](seq: Seq[A], f: Function1[A, kyo.kernel.`<`[B, S]]) =
        new JsKyo(Choice.evalWith(seq)(f))


end JsChoice