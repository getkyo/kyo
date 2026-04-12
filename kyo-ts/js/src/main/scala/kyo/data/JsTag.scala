package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Tag")
class JsTag[A](@JSName("$tag") val underlying: Tag[A]) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsTag

object JsTag:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def apply[A]() =
        new JsTag(Tag.apply)

    @JSExportStatic
    def derive[A]() =
        new JsTag(Tag.derive)

    @JSExportStatic
    def erased[A]() =
        new JsTag(Tag.erased)

    @JSExportStatic
    def hash[A]() =
        Tag.hash

    @JSExportStatic
    def show[A]() =
        Tag.show

    @JSExportStatic
    def tpe[A]() =
        Tag.tpe


end JsTag