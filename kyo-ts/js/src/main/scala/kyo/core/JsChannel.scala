package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Channel")
class JsChannel[A](@JSName("$chan") val underlying: Channel[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def capacity() =
        Channel.capacity(underlying)

    def close() =
        new JsKyo(Channel.close(underlying))

    def closeAwaitEmpty() =
        new JsKyo(Channel.closeAwaitEmpty(underlying))

    def closed() =
        new JsKyo(Channel.closed(underlying))

    def drain() =
        new JsKyo(Channel.drain(underlying))

    def drainUpTo(max: Int) =
        new JsKyo(Channel.drainUpTo(underlying)(max))

    def empty() =
        new JsKyo(Channel.empty(underlying))

    def full() =
        new JsKyo(Channel.full(underlying))

    def offer(value: A) =
        new JsKyo(Channel.offer(underlying)(value))

    def offerDiscard(value: A) =
        new JsKyo(Channel.offerDiscard(underlying)(value))

    def pendingPuts() =
        new JsKyo(Channel.pendingPuts(underlying))

    def pendingTakes() =
        new JsKyo(Channel.pendingTakes(underlying))

    def poll() =
        new JsKyo(Channel.poll(underlying))

    def put(value: A) =
        new JsKyo(Channel.put(underlying)(value))

    def putBatch(values: Seq[A]) =
        new JsKyo(Channel.putBatch(underlying)(values))

    def size() =
        new JsKyo(Channel.size(underlying))

    def stream(maxChunkSize: Int) =
        new JsStream(Channel.stream(underlying)(maxChunkSize))

    def streamUntilClosed(maxChunkSize: Int) =
        new JsStream(Channel.streamUntilClosed(underlying)(maxChunkSize))

    def take() =
        new JsKyo(Channel.take(underlying))

    def takeExactly(n: Int) =
        new JsKyo(Channel.takeExactly(underlying)(n))

    def unsafe() =
        Channel.unsafe(underlying)


end JsChannel