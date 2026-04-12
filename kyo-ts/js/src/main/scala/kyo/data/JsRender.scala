package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Render")
class JsRender[A](@JSName("$rend") val underlying: Render[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def asString(value: A) =
        underlying.asString(value)

    def asText(value: A) =
        new JsText(underlying.asText(value))


end JsRender

object JsRender:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def apply[A]() =
        new JsRender(Render.apply)

    @JSExportStatic
    def asText[A](value: A) =
        new JsText(Render.asText(value))

    @JSExportStatic
    def from[A](impl: Function1[A, Text]) =
        new JsRender(Render.from(impl))

    @JSExportStatic
    def given_Render_A[A]() =
        new JsRender(Render.given_Render_A)


end JsRender