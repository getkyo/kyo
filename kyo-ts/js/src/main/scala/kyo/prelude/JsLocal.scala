package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Local")
class JsLocal[A](@JSName("$loca") val underlying: Local[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def default_() =
        underlying.default

    def get() =
        new JsKyo(underlying.get)

    def let_[B, S](value: A, v: JsKyo[B, S]) =
        new JsKyo(underlying.let(value)(v.underlying))

    def update[B, S](f: Function1[A, A], v: JsKyo[B, S]) =
        new JsKyo(underlying.update(f)(v.underlying))

    def use[B, S](f: Function1[A, kyo.kernel.`<`[B, S]]) =
        new JsKyo(underlying.use(f))


end JsLocal

object JsLocal:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def init[A](defaultValue: A) =
        new JsLocal(Local.init(defaultValue))

    @JSExportStatic
    def initNoninheritable[A](defaultValue: A) =
        new JsLocal(Local.initNoninheritable(defaultValue))

    @JSExportStatic
    def internal() =
        Local.internal


end JsLocal