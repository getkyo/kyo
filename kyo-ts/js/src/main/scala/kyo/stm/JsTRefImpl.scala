package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("TRefImpl")
class JsTRefImpl[A](@JSName("$tref") val underlying: TRefImpl[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def set(v: A) =
        new JsKyo(underlying.set(v))

    def use[B, S](f: Function1[A, `<`[B, S]]) =
        new JsKyo(underlying.use(f))


end JsTRefImpl