package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("AtomicRef")
class JsAtomicRef[A](@JSName("$atom") val underlying: AtomicRef[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def compareAndSet(curr: A, next: A) =
        new JsKyo(underlying.compareAndSet(curr, next))

    def get() =
        new JsKyo(underlying.get)

    def getAndSet(v: A) =
        new JsKyo(underlying.getAndSet(v))

    def getAndUpdate(f: Function1[A, A]) =
        new JsKyo(underlying.getAndUpdate(f))

    def lazySet(v: A) =
        new JsKyo(underlying.lazySet(v))

    def set(v: A) =
        new JsKyo(underlying.set(v))

    def unsafe() =
        underlying.unsafe

    def updateAndGet(f: Function1[A, A]) =
        new JsKyo(underlying.updateAndGet(f))

    def use[B, S](f: Function1[A, `<`[B, S]]) =
        new JsKyo(underlying.use(f))


end JsAtomicRef

object JsAtomicRef:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def init[A](initialValue: A) =
        new JsKyo(AtomicRef.init(initialValue))

    @JSExportStatic
    def initWith[A, B, S](initialValue: A, f: Function1[AtomicRef[A], `<`[B, S]]) =
        new JsKyo(AtomicRef.initWith(initialValue)(f))


end JsAtomicRef