package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("AtomicInt")
class JsAtomicInt(@JSName("$atom") val underlying: AtomicInt) extends js.Object:
    import kyo.JsFacadeGivens.given
    def addAndGet(v: Int) =
        new JsKyo(underlying.addAndGet(v))

    def compareAndSet(curr: Int, next: Int) =
        new JsKyo(underlying.compareAndSet(curr, next))

    def decrementAndGet() =
        new JsKyo(underlying.decrementAndGet)

    def get() =
        new JsKyo(underlying.get)

    def getAndAdd(v: Int) =
        new JsKyo(underlying.getAndAdd(v))

    def getAndDecrement() =
        new JsKyo(underlying.getAndDecrement)

    def getAndIncrement() =
        new JsKyo(underlying.getAndIncrement)

    def getAndSet(v: Int) =
        new JsKyo(underlying.getAndSet(v))

    def getAndUpdate(f: Function1[Int, Int]) =
        new JsKyo(underlying.getAndUpdate(f))

    def incrementAndGet() =
        new JsKyo(underlying.incrementAndGet)

    def lazySet(v: Int) =
        new JsKyo(underlying.lazySet(v))

    def set(v: Int) =
        new JsKyo(underlying.set(v))

    def unsafe() =
        underlying.unsafe

    def updateAndGet(f: Function1[Int, Int]) =
        new JsKyo(underlying.updateAndGet(f))

    def use[A, S](f: Function1[Int, `<`[A, S]]) =
        new JsKyo(underlying.use(f))


end JsAtomicInt

object JsAtomicInt:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def init() =
        new JsKyo(AtomicInt.init)

    @JSExportStatic
    def initWith[A, S](f: Function1[AtomicInt, `<`[A, S]]) =
        new JsKyo(AtomicInt.initWith(f))


end JsAtomicInt