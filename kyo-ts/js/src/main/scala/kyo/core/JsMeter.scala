package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Meter")
class JsMeter(@JSName("$mete") val underlying: Meter) extends js.Object:
    import kyo.JsFacadeGivens.given
    def availablePermits() =
        new JsKyo(underlying.availablePermits)

    def close() =
        new JsKyo(underlying.close)

    def closed() =
        new JsKyo(underlying.closed)

    def pendingWaiters() =
        new JsKyo(underlying.pendingWaiters)

    def run[A, S](v: js.Function0[JsKyo[A, S]]) =
        new JsKyo(underlying.run(v().underlying))

    def tryRun[A, S](v: js.Function0[JsKyo[A, S]]) =
        new JsKyo(underlying.tryRun(v().underlying))


end JsMeter

object JsMeter:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def initMutex() =
        new JsKyo(Meter.initMutex)

    @JSExportStatic
    def initMutexUnscoped() =
        new JsKyo(Meter.initMutexUnscoped)

    @JSExportStatic
    def pipeline[S](meters: Seq[`<`[Meter, `&`[Sync, S]]]) =
        new JsKyo(Meter.pipeline(meters))

    @JSExportStatic
    def useMutex[A, S](f: Function1[Meter, `<`[A, S]]) =
        new JsKyo(Meter.useMutex(f))


end JsMeter