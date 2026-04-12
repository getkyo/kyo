package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpFormCodec")
class JsHttpFormCodec[A](@JSName("$http") val underlying: HttpFormCodec[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def decode(s: Predef.String) =
        new JsResult(underlying.decode(s))

    def encode(a: A) =
        underlying.encode(a)


end JsHttpFormCodec

object JsHttpFormCodec:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def derived[A]() =
        new JsHttpFormCodec(HttpFormCodec.derived)


end JsHttpFormCodec