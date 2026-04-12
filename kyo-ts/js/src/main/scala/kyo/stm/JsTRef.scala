package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("TRef")
class JsTRef[A](@JSName("$tref") val underlying: TRef[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def get() =
        new JsKyo(underlying.get)

    def set(v: A) =
        new JsKyo(underlying.set(v))

    def update[S](f: Function1[A, `<`[A, S]]) =
        new JsKyo(underlying.update(f))

    def use[B, S](f: Function1[A, `<`[B, S]]) =
        new JsKyo(underlying.use(f))


end JsTRef

object JsTRef:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def init[A](value: A) =
        new JsKyo(TRef.init(value))

    @JSExportStatic
    def initWith[A, B, S](value: A, f: Function1[TRef[A], `<`[B, S]]) =
        new JsKyo(TRef.initWith(value)(f))


end JsTRef