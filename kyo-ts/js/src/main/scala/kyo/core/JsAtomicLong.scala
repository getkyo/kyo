package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("AtomicLong")
class JsAtomicLong(@JSName("$atom") val underlying: AtomicLong) extends js.Object:
    import kyo.JsFacadeGivens.given
    def addAndGet(v: Long) =
        new JsKyo(underlying.addAndGet(v))

    def compareAndSet(curr: Long, next: Long) =
        new JsKyo(underlying.compareAndSet(curr, next))

    def decrementAndGet() =
        new JsKyo(underlying.decrementAndGet)

    def get() =
        new JsKyo(underlying.get)

    def getAndAdd(v: Long) =
        new JsKyo(underlying.getAndAdd(v))

    def getAndDecrement() =
        new JsKyo(underlying.getAndDecrement)

    def getAndIncrement() =
        new JsKyo(underlying.getAndIncrement)

    def getAndSet(v: Long) =
        new JsKyo(underlying.getAndSet(v))

    def getAndUpdate(f: Function1[Long, Long]) =
        new JsKyo(underlying.getAndUpdate(f))

    def incrementAndGet() =
        new JsKyo(underlying.incrementAndGet)

    def lazySet(v: Long) =
        new JsKyo(underlying.lazySet(v))

    def set(v: Long) =
        new JsKyo(underlying.set(v))

    def unsafe() =
        underlying.unsafe

    def updateAndGet(f: Function1[Long, Long]) =
        new JsKyo(underlying.updateAndGet(f))

    def use[A, S](f: Function1[Long, `<`[A, S]]) =
        new JsKyo(underlying.use(f))


end JsAtomicLong

object JsAtomicLong:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def init() =
        new JsKyo(AtomicLong.init)

    @JSExportStatic
    def initWith[A, S](f: Function1[AtomicLong, `<`[A, S]]) =
        new JsKyo(AtomicLong.initWith(f))


end JsAtomicLong