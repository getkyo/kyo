package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Queue")
class JsQueue[A](@JSName("$queu") val underlying: Queue[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def capacity() =
        Queue.capacity(underlying)

    def close() =
        new JsKyo(Queue.close(underlying))

    def closeAwaitEmpty() =
        new JsKyo(Queue.closeAwaitEmpty(underlying))

    def closed() =
        new JsKyo(Queue.closed(underlying))

    def drain() =
        new JsKyo(Queue.drain(underlying))

    def drainUpTo(max: Int) =
        new JsKyo(Queue.drainUpTo(underlying)(max))

    def empty() =
        new JsKyo(Queue.empty(underlying))

    def full() =
        new JsKyo(Queue.full(underlying))

    def offer(v: A) =
        new JsKyo(Queue.offer(underlying)(v))

    def offerDiscard(v: A) =
        new JsKyo(Queue.offerDiscard(underlying)(v))

    def peek() =
        new JsKyo(Queue.peek(underlying))

    def poll() =
        new JsKyo(Queue.poll(underlying))

    def size() =
        new JsKyo(Queue.size(underlying))

    def unsafe() =
        Queue.unsafe(underlying)


end JsQueue