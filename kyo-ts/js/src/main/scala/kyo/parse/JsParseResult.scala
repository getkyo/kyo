package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("ParseResult")
class JsParseResult[Out](@JSName("$pars") val underlying: ParseResult[Out]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def errors() =
        new JsChunk(underlying.errors)

    def fatal() =
        underlying.fatal

    def isFailure() =
        underlying.isFailure

    def orAbort() =
        new JsKyo(underlying.orAbort)

    def out() =
        new JsMaybe(underlying.out)


end JsParseResult