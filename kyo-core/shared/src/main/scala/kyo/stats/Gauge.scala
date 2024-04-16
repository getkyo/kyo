package kyo.stats

import kyo.*
import kyo.stats.internal.UnsafeGauge

case class Gauge(unsafe: UnsafeGauge) extends AnyVal:
    def close: Unit < IOs = IOs(unsafe.close())

object Gauge:

    val noop: Gauge =
        Gauge(UnsafeGauge.noop)

    def all(l: List[Gauge]): Gauge =
        Gauge(UnsafeGauge.all(l.map(_.unsafe)))
end Gauge
