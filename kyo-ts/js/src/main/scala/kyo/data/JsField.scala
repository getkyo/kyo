package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Field")
class JsField[Name, Value](@JSName("$fiel") val underlying: Field[Name, Value]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def name() =
        underlying.name

    def nested() =
        underlying.nested

    def tag() =
        new JsTag(underlying.tag)


end JsField

object JsField:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def apply[Name, Value]() =
        new JsField(Field.apply)

    @JSExportStatic
    def apply[Name, Value](name: Name, nested: List[Field[?, ?]]) =
        new JsField(Field.apply(name, nested))


end JsField