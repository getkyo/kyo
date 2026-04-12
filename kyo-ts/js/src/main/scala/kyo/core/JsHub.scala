package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Hub")
class JsHub[A](@JSName("$hub") val underlying: Hub[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def close() =
        new JsKyo(underlying.close)

    def closed() =
        new JsKyo(underlying.closed)

    def empty() =
        new JsKyo(underlying.empty)

    def full() =
        new JsKyo(underlying.full)

    def listen() =
        new JsKyo(underlying.listen)

    def listen(bufferSize: Int, filter: Function1[A, Boolean]) =
        new JsKyo(underlying.listen(bufferSize, filter))

    def listen(bufferSize: Int) =
        new JsKyo(underlying.listen(bufferSize))

    def offer(v: A) =
        new JsKyo(underlying.offer(v))

    def offerDiscard(v: A) =
        new JsKyo(underlying.offerDiscard(v))

    def put(v: A) =
        new JsKyo(underlying.put(v))

    def putBatch(values: Seq[A]) =
        new JsKyo(underlying.putBatch(values))


end JsHub

object JsHub:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def init[A]() =
        new JsKyo(Hub.init)


end JsHub