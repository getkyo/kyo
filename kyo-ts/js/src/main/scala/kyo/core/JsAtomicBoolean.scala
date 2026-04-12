package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("AtomicBoolean")
class JsAtomicBoolean(@JSName("$atom") val underlying: AtomicBoolean) extends js.Object:
    import kyo.JsFacadeGivens.given
    def compareAndSet(curr: Boolean, next: Boolean) =
        new JsKyo(underlying.compareAndSet(curr, next))

    def get() =
        new JsKyo(underlying.get)

    def getAndSet(v: Boolean) =
        new JsKyo(underlying.getAndSet(v))

    def lazySet(v: Boolean) =
        new JsKyo(underlying.lazySet(v))

    def set(v: Boolean) =
        new JsKyo(underlying.set(v))

    def unsafe() =
        underlying.unsafe

    def use[A, S](f: Function1[Boolean, `<`[A, S]]) =
        new JsKyo(underlying.use(f))


end JsAtomicBoolean

object JsAtomicBoolean:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def init() =
        new JsKyo(AtomicBoolean.init)

    @JSExportStatic
    def initWith[A, S](f: Function1[AtomicBoolean, `<`[A, S]]) =
        new JsKyo(AtomicBoolean.initWith(f))


end JsAtomicBoolean