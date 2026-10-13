package kyo.stats.internal

import java.util.concurrent.ConcurrentHashMap
import kyo.AllowUnsafe
import kyo.stats.Attributes

/** A gauge, one series per attribute set, each series the thunk it was registered with.
  *
  * The thunk given at construction is the series with no attributes. A series for an attribute set is registered with `register`,
  * first-writer-wins per set, which is how a producer of several of something, one value per mount, per route, per model, publishes them
  * as one metric. Attribute order is not identity: a set is normalized to name order before it keys a series.
  */
class UnsafeGauge(run: () => Double) extends Serializable {
    private val cells = new ConcurrentHashMap[Attributes, () => Double]
    cells.put(Attributes.empty, run): Unit

    /** Registers the series `attributes` as `run`, or keeps the one already registered there. */
    def register(attributes: Attributes, run: () => Double): Unit =
        cells.putIfAbsent(attributes.normalized, run): Unit

    def collect()(implicit _au: AllowUnsafe): Double = run()

    /** The series `attributes`, or NaN when none is registered there. */
    def collect(attributes: Attributes)(implicit _au: AllowUnsafe): Double = {
        val thunk = cells.get(attributes.normalized)
        if (thunk == null) Double.NaN else thunk()
    }

    /** Every series' current value, the attribute-free one first: what an exporter emits as data points. */
    def series()(implicit _au: AllowUnsafe): Seq[UnsafeGauge.Series] = {
        val out = List.newBuilder[UnsafeGauge.Series]
        out += UnsafeGauge.Series(Attributes.empty, run())
        val it = cells.entrySet().iterator()
        while (it.hasNext) {
            val entry = it.next()
            if (entry.getKey.get.nonEmpty)
                out += UnsafeGauge.Series(entry.getKey, entry.getValue.apply())
        }
        out.result()
    }
}

object UnsafeGauge {

    /** One series' current value. */
    final case class Series(attributes: Attributes, value: Double)
}

/** A counter gauge, one series per attribute set, as `UnsafeGauge`; each series drains to its delta since its last read. */
class UnsafeCounterGauge(run: () => Long) extends Serializable {
    private val base  = new UnsafeCounterGauge.Cell(run)
    private val cells = new ConcurrentHashMap[Attributes, UnsafeCounterGauge.Cell]
    cells.put(Attributes.empty, base): Unit

    /** Registers the series `attributes` as `run`, or keeps the one already registered there. */
    def register(attributes: Attributes, run: () => Long): Unit =
        cells.putIfAbsent(attributes.normalized, new UnsafeCounterGauge.Cell(run)): Unit

    def collect()(implicit _au: AllowUnsafe): Long = base.collect()

    /** The series `attributes`, or 0 when none is registered there. */
    def collect(attributes: Attributes)(implicit _au: AllowUnsafe): Long = {
        val cell = cells.get(attributes.normalized)
        if (cell == null) 0L else cell.collect()
    }

    def delta()(implicit _au: AllowUnsafe): Long = base.delta()

    /** Every series drained since its last read, the attribute-free one first: what an exporter emits as data points. */
    def series()(implicit _au: AllowUnsafe): Seq[UnsafeCounterGauge.Series] = {
        val out = List.newBuilder[UnsafeCounterGauge.Series]
        out += UnsafeCounterGauge.Series(Attributes.empty, base.delta())
        val it = cells.entrySet().iterator()
        while (it.hasNext) {
            val entry = it.next()
            if (entry.getKey.get.nonEmpty)
                out += UnsafeCounterGauge.Series(entry.getKey, entry.getValue.delta())
        }
        out.result()
    }

    private[kyo] def getLast(): Long = base.getLast()
}

object UnsafeCounterGauge {

    /** One series' delta since its last read. */
    final case class Series(attributes: Attributes, delta: Long)

    final private[kyo] class Cell(run: () => Long) extends Serializable {
        private var last = 0L

        def collect(): Long = {
            val value = run()
            if (value < 0) {
                (Long.MaxValue + value) + 2
            } else {
                value
            }
        }

        private def findDelta(a: Long, b: Long) = {
            (Long.MaxValue - a) + b
        }

        def delta(): Long = {
            val curr  = collect()
            val delta = if (curr >= last) curr - last else findDelta(last, curr)
            last = curr
            delta
        }

        def getLast(): Long = last
    }
}
