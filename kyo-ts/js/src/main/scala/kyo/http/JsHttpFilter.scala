package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpFilter")
class JsHttpFilter[ReqUse, ReqAdd, ResUse, ResAdd, E](@JSName("$http") val underlying: HttpFilter[ReqUse, ReqAdd, ResUse, ResAdd, E]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def adapt[In, Out]() =
        new JsHttpFilter(HttpFilter.adapt(underlying))

    def andThen[RI2, RO2, SI2, SO2, E2](that: JsHttpFilter[RI2, RO2, SI2, SO2, E2]) =
        new JsHttpFilter(underlying.andThen(that.underlying))

    def apply[In, Out, E2](request: JsHttpRequest[`&`[In, ReqUse]], next: Function1[HttpRequest[`&`[`&`[In, ReqUse], ReqAdd]], `<`[HttpResponse[`&`[Out, ResUse]], `&`[Async, Abort[`|`[E2, HttpResponse.Halt]]]]]) =
        new JsKyo(underlying.apply(request.underlying, next))


end JsHttpFilter

object JsHttpFilter:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def client() =
        HttpFilter.client

    @JSExportStatic
    def noop() =
        HttpFilter.noop

    @JSExportStatic
    def server() =
        HttpFilter.server


end JsHttpFilter