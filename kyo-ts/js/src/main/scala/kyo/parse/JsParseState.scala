package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("ParseState")
class JsParseState[In](@JSName("$pars") val underlying: ParseState[In]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def failures() =
        new JsChunk(underlying.failures)

    def input() =
        new JsParseInput(underlying.input)

    def isDiscarded() =
        underlying.isDiscarded


end JsParseState