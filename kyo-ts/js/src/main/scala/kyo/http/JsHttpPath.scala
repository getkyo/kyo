package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpPath")
class JsHttpPath[A](@JSName("$http") val underlying: HttpPath[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def show() =
        underlying.show

    def slash[B](next: JsHttpPath[B]) =
        new JsHttpPath(underlying.`/`(next.underlying))


end JsHttpPath

object JsHttpPath:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def empty() =
        new JsHttpPath(HttpPath.empty)


end JsHttpPath