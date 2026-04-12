package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Json")
class JsJson[A](@JSName("$json") val underlying: Json[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def decode(json: Predef.String) =
        new JsResult(underlying.decode(json))

    def encode(value: A) =
        underlying.encode(value)


end JsJson

object JsJson:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def apply[A]() =
        new JsJson(Json.apply)

    @JSExportStatic
    def derived[A]() =
        new JsJson(Json.derived)

    @JSExportStatic
    def fromZio[A](zs: zio.schema.Schema[A]) =
        new JsJson(Json.fromZio(zs))

    @JSExportStatic
    def given_Json_Boolean() =
        new JsJson(Json.given_Json_Boolean)

    @JSExportStatic
    def given_Json_Byte() =
        new JsJson(Json.given_Json_Byte)

    @JSExportStatic
    def given_Json_Char() =
        new JsJson(Json.given_Json_Char)

    @JSExportStatic
    def given_Json_Double() =
        new JsJson(Json.given_Json_Double)

    @JSExportStatic
    def given_Json_Either[A, B]() =
        new JsJson(Json.given_Json_Either)

    @JSExportStatic
    def given_Json_Float() =
        new JsJson(Json.given_Json_Float)

    @JSExportStatic
    def given_Json_Int() =
        new JsJson(Json.given_Json_Int)

    @JSExportStatic
    def given_Json_List[A]() =
        new JsJson(Json.given_Json_List)

    @JSExportStatic
    def given_Json_Long() =
        new JsJson(Json.given_Json_Long)

    @JSExportStatic
    def given_Json_Map[A, B]() =
        new JsJson(Json.given_Json_Map)

    @JSExportStatic
    def given_Json_Maybe[A]() =
        new JsJson(Json.given_Json_Maybe)

    @JSExportStatic
    def given_Json_Option[A]() =
        new JsJson(Json.given_Json_Option)

    @JSExportStatic
    def given_Json_Seq[A]() =
        new JsJson(Json.given_Json_Seq)

    @JSExportStatic
    def given_Json_Set[A]() =
        new JsJson(Json.given_Json_Set)

    @JSExportStatic
    def given_Json_Short() =
        new JsJson(Json.given_Json_Short)

    @JSExportStatic
    def given_Json_String() =
        new JsJson(Json.given_Json_String)

    @JSExportStatic
    def given_Json_Unit() =
        new JsJson(Json.given_Json_Unit)

    @JSExportStatic
    def given_Json_Vector[A]() =
        new JsJson(Json.given_Json_Vector)


end JsJson