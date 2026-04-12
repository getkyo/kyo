package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpSseEvent")
class JsHttpSseEvent[A](@JSName("$http") val underlying: HttpSseEvent[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def data() =
        underlying.data

    def event() =
        new JsMaybe(underlying.event)

    def id() =
        new JsMaybe(underlying.id)

    def retry() =
        new JsMaybe(underlying.retry)


end JsHttpSseEvent

object JsHttpSseEvent:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def apply[A](data: A, event: JsMaybe[Predef.String], id: JsMaybe[Predef.String], retry: JsMaybe[Duration]) =
        new JsHttpSseEvent(HttpSseEvent.apply(data, event.underlying, id.underlying, retry.underlying))


end JsHttpSseEvent