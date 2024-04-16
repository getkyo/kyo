package kyo.stats

import kyo.*
import kyo.stats.internal.UnsafeHistogram

case class Histogram(unsafe: UnsafeHistogram) extends AnyVal:

    def observe(v: Double): Unit < IOs =
        IOs(unsafe.observe(v))

    def observe(v: Double, b: Attributes): Unit < IOs =
        IOs(unsafe.observe(v, b))

    def attributes(b: Attributes): Histogram =
        Histogram(unsafe.attributes(b))
end Histogram

object Histogram:

    val noop: Histogram =
        Histogram(UnsafeHistogram.noop)

    def all(l: List[Histogram]): Histogram =
        Histogram(UnsafeHistogram.all(l.map(_.unsafe)))

end Histogram
