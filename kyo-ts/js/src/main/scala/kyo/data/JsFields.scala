package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Fields")
class JsFields[A](@JSName("$fiel") val underlying: Fields[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def fields() =
        underlying.fields

    def names() =
        underlying.names


end JsFields

object JsFields:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def derive[A]() =
        new JsFields(Fields.derive)

    @JSExportStatic
    def fields[A]() =
        Fields.fields

    @JSExportStatic
    def foreach[A, F](fn: Function2[Predef.String, F[Any], Unit]) =
        Fields.foreach(fn)

    @JSExportStatic
    def names[A]() =
        Fields.names


end JsFields