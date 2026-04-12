package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("ParseInput")
class JsParseInput[In](@JSName("$pars") val underlying: ParseInput[In]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def advance(n: Int) =
        new JsParseInput(underlying.advance(n))

    def advanceWhile(f: Function1[In, Boolean]) =
        new JsParseInput(underlying.advanceWhile(f))

    def done() =
        underlying.done

    def position() =
        underlying.position

    def remaining() =
        new JsChunk(underlying.remaining)

    def tokens() =
        new JsChunk(underlying.tokens)


end JsParseInput