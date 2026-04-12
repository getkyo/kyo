package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Var")
class JsVar[V](@JSName("$var") val underlying: Var[V]) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsVar

object JsVar:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def get[V]() =
        new JsKyo(Var.get)

    @JSExportStatic
    def internal() =
        Var.internal

    @JSExportStatic
    def isolate() =
        Var.isolate

    @JSExportStatic
    def run[V, A, S](state: V, v: JsKyo[A, `&`[Var[V], S]]) =
        new JsKyo(Var.run(state)(v.underlying))

    @JSExportStatic
    def runTuple[V, A, S](state: V, v: JsKyo[A, `&`[Var[V], S]]) =
        new JsKyo(Var.runTuple(state)(v.underlying))

    @JSExportStatic
    def set[V](value: V) =
        new JsKyo(Var.set(value))

    @JSExportStatic
    def setDiscard[V](value: V) =
        new JsKyo(Var.setDiscard(value))

    @JSExportStatic
    def setWith[V, A, S](value: V, f: js.Function0[JsKyo[A, S]]) =
        new JsKyo(Var.setWith(value)(f().underlying))

    @JSExportStatic
    def update[V](update: Function1[V, V]) =
        new JsKyo(Var.update(update))

    @JSExportStatic
    def updateDiscard[V](f: Function1[V, V]) =
        new JsKyo(Var.updateDiscard(f))

    @JSExportStatic
    def updateWith[V, A, S](update: Function1[V, V], f: Function1[V, kyo.kernel.`<`[A, S]]) =
        new JsKyo(Var.updateWith(update)(f))

    @JSExportStatic
    def use[V, A, S](f: Function1[V, kyo.kernel.`<`[A, S]]) =
        new JsKyo(Var.use(f))


end JsVar