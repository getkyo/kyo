package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpHandler")
class JsHttpHandler[In, Out, E](@JSName("$http") val underlying: HttpHandler[In, Out, E]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def apply(request: JsHttpRequest[In]) =
        new JsKyo(underlying.apply(request.underlying))

    def route() =
        new JsHttpRoute(underlying.route)


end JsHttpHandler

object JsHttpHandler:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def health() =
        new JsHttpHandler(HttpHandler.health())


end JsHttpHandler