package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("ConcreteTag")
class JsConcreteTag[A](@JSName("$conc") val underlying: ConcreteTag[A]) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsConcreteTag

object JsConcreteTag:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def accepts[A](value: Any) =
        ConcreteTag.accepts(value)

    @JSExportStatic
    def and[A, B]() =
        new JsConcreteTag(ConcreteTag.`&`)

    @JSExportStatic
    def apply[A]() =
        new JsConcreteTag(ConcreteTag.apply)

    @JSExportStatic
    def given_CanEqual_ConcreteTag_ConcreteTag[A, B]() =
        ConcreteTag.given_CanEqual_ConcreteTag_ConcreteTag

    @JSExportStatic
    def or[A, B]() =
        new JsConcreteTag(ConcreteTag.`|`)

    @JSExportStatic
    def show[A]() =
        ConcreteTag.show


end JsConcreteTag