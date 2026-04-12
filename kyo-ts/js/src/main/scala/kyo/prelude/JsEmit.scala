package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Emit")
class JsEmit[V](@JSName("$emit") val underlying: Emit[V]) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsEmit

object JsEmit:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def eliminateEmit() =
        Emit.eliminateEmit

    @JSExportStatic
    def isolate() =
        Emit.isolate

    @JSExportStatic
    def runFold[V, A, S, VR, B, S2](acc: A, f: Function2[A, V, kyo.kernel.`<`[A, S]], v: JsKyo[B, `&`[`&`[Emit[V], Emit[VR]], S2]]) =
        new JsKyo(Emit.runFold(acc)(f)(v.underlying))

    @JSExportStatic
    def value[V](value: V) =
        new JsKyo(Emit.value(value))

    @JSExportStatic
    def valueWith[V, A, S](value: V, f: js.Function0[JsKyo[A, S]]) =
        new JsKyo(Emit.valueWith(value)(f().underlying))


end JsEmit