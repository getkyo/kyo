package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Aspect")
class JsAspect[Input, Output, S](@JSName("$aspe") val underlying: Aspect[Input, Output, S]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def apply[C](input: Input[C], cont: Function1[Input[C], `<`[Output[C], S]]) =
        new JsKyo(underlying.apply(input)(cont))

    def asCut() =
        underlying.asCut

    def let_[A, S2](a: Aspect.Cut[Input, Output, S], v: JsKyo[A, S2]) =
        new JsKyo(underlying.let(a)(v.underlying))

    def sandbox[A, S](v: JsKyo[A, S]) =
        new JsKyo(underlying.sandbox(v.underlying))


end JsAspect

object JsAspect:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def init[I, O, S]() =
        new JsAspect(Aspect.init)


end JsAspect