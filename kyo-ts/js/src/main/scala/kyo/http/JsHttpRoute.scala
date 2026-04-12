package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpRoute")
class JsHttpRoute[In, Out, E](@JSName("$http") val underlying: HttpRoute[In, Out, E]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def error[E2](s: JsHttpStatus) =
        new JsHttpRoute(underlying.error(s.underlying))

    def filter[ReqUse, ReqAdd, ResUse, ResAdd, E2](f: JsHttpFilter[ReqUse, ReqAdd, ResUse, ResAdd, E2]) =
        new JsHttpRoute(underlying.filter(f.underlying))

    def filter() =
        new JsHttpFilter(underlying.filter)

    def handler[E2](f: Function1[HttpRequest[In], `<`[HttpResponse[Out], `&`[Async, Abort[`|`[E2, HttpResponse.Halt]]]]]) =
        new JsHttpHandler(underlying.handler(f))

    def metadata() =
        underlying.metadata

    def metadata(f: Function1[HttpRoute.Metadata, HttpRoute.Metadata]) =
        new JsHttpRoute(underlying.metadata(f))

    def method() =
        new JsHttpMethod(underlying.method)

    def pathAppend[In2](suffix: JsHttpPath[In2]) =
        new JsHttpRoute(underlying.pathAppend(suffix.underlying))

    def pathPrepend[In2](prefix: JsHttpPath[In2]) =
        new JsHttpRoute(underlying.pathPrepend(prefix.underlying))

    def request() =
        underlying.request

    def response() =
        underlying.response


end JsHttpRoute