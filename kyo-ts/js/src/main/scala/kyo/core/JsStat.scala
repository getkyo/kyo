package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Stat")
class JsStat(@JSName("$stat") val underlying: Stat) extends js.Object:
    import kyo.JsFacadeGivens.given
    def initCounter(name: Predef.String, description: Predef.String) =
        new JsCounter(underlying.initCounter(name, description))

    def initCounterGauge(name: Predef.String, description: Predef.String, f: js.Function0[Long]) =
        new JsCounterGauge(underlying.initCounterGauge(name, description)(f()))

    def initGauge(name: Predef.String, description: Predef.String, f: js.Function0[Double]) =
        new JsGauge(underlying.initGauge(name, description)(f()))

    def initHistogram(name: Predef.String, description: Predef.String) =
        new JsHistogram(underlying.initHistogram(name, description))

    def scope(path: Seq[Predef.String]) =
        new JsStat(underlying.scope(path*))

    def traceSpan[A, S](name: Predef.String, attributes: kyo.stats.Attributes, v: js.Function0[JsKyo[A, S]]) =
        new JsKyo(underlying.traceSpan(name, attributes)(v().underlying))


end JsStat

object JsStat:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def traceListen[A, S](receiver: kyo.stats.internal.TraceReceiver, v: JsKyo[A, S]) =
        new JsKyo(Stat.traceListen(receiver)(v.underlying))


end JsStat