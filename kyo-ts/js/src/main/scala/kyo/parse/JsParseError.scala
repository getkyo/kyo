package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("ParseError")
class JsParseError(@JSName("$pars") val underlying: ParseError) extends js.Object:
    import kyo.JsFacadeGivens.given
    def failures() =
        new JsChunk(underlying.failures)


end JsParseError