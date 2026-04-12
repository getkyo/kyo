package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpCodec")
class JsHttpCodec[A](@JSName("$http") val underlying: HttpCodec[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def decode(raw: Predef.String) =
        new JsResult(underlying.decode(raw))

    def encode(value: A) =
        underlying.encode(value)


end JsHttpCodec

object JsHttpCodec:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def apply[A](enc: Function1[A, Predef.String], dec: Function1[Predef.String, A]) =
        new JsHttpCodec(HttpCodec.apply(enc, dec))

    @JSExportStatic
    def given_HttpCodec_BigDecimal() =
        new JsHttpCodec(HttpCodec.given_HttpCodec_BigDecimal)

    @JSExportStatic
    def given_HttpCodec_BigInt() =
        new JsHttpCodec(HttpCodec.given_HttpCodec_BigInt)

    @JSExportStatic
    def given_HttpCodec_Boolean() =
        new JsHttpCodec(HttpCodec.given_HttpCodec_Boolean)

    @JSExportStatic
    def given_HttpCodec_Byte() =
        new JsHttpCodec(HttpCodec.given_HttpCodec_Byte)

    @JSExportStatic
    def given_HttpCodec_Double() =
        new JsHttpCodec(HttpCodec.given_HttpCodec_Double)

    @JSExportStatic
    def given_HttpCodec_Duration() =
        new JsHttpCodec(HttpCodec.given_HttpCodec_Duration)

    @JSExportStatic
    def given_HttpCodec_Float() =
        new JsHttpCodec(HttpCodec.given_HttpCodec_Float)

    @JSExportStatic
    def given_HttpCodec_Instant() =
        new JsHttpCodec(HttpCodec.given_HttpCodec_Instant)

    @JSExportStatic
    def given_HttpCodec_Int() =
        new JsHttpCodec(HttpCodec.given_HttpCodec_Int)

    @JSExportStatic
    def given_HttpCodec_Long() =
        new JsHttpCodec(HttpCodec.given_HttpCodec_Long)

    @JSExportStatic
    def given_HttpCodec_Short() =
        new JsHttpCodec(HttpCodec.given_HttpCodec_Short)

    @JSExportStatic
    def given_HttpCodec_String() =
        new JsHttpCodec(HttpCodec.given_HttpCodec_String)

    @JSExportStatic
    def given_HttpCodec_UUID() =
        new JsHttpCodec(HttpCodec.given_HttpCodec_UUID)


end JsHttpCodec