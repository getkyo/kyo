package kyo.stats.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import kyo.AllowUnsafe
import kyo.stats.Attributes

/** A counter, one series per attribute set.
  *
  * The series with no attributes is the counter's own and sits on the hot path with no lookup; a series for an attribute set is made the
  * first time that set is observed and lives as long as the counter does, the way an OpenTelemetry SDK instrument owns its data points.
  * Attribute order is not identity: a set is normalized to name order before it keys a series.
  */
class UnsafeCounter extends Serializable {
    private val base  = new UnsafeCounter.Cell
    private val cells = new ConcurrentHashMap[Attributes, UnsafeCounter.Cell]

    private def cell(attributes: Attributes): UnsafeCounter.Cell =
        if (attributes.get.isEmpty) base
        else cells.computeIfAbsent(attributes.normalized, _ => new UnsafeCounter.Cell)

    def get()(implicit _au: AllowUnsafe): Long        = base.get()
    def inc()(implicit _au: AllowUnsafe): Unit        = base.inc()
    def add(v: Long)(implicit _au: AllowUnsafe): Unit = base.add(v)

    def inc(attributes: Attributes)(implicit _au: AllowUnsafe): Unit          = cell(attributes).inc()
    def add(v: Long, attributes: Attributes)(implicit _au: AllowUnsafe): Unit = cell(attributes).add(v)

    /** The series `attributes`, drained as `get()` drains, or 0 when nothing was observed with them. Never makes a series. */
    def get(attributes: Attributes)(implicit _au: AllowUnsafe): Long = {
        val found = if (attributes.get.isEmpty) base else cells.get(attributes.normalized)
        if (found == null) 0L else found.get()
    }

    /** The series with no attributes drained since its last `delta`. */
    def delta()(implicit _au: AllowUnsafe): Long = base.delta()

    /** Every series drained since its last read, the attribute-free one first: what an exporter emits as data points. */
    def series()(implicit _au: AllowUnsafe): Seq[UnsafeCounter.Series] = {
        val out = List.newBuilder[UnsafeCounter.Series]
        out += UnsafeCounter.Series(Attributes.empty, base.delta())
        val it = cells.entrySet().iterator()
        while (it.hasNext) {
            val entry = it.next()
            out += UnsafeCounter.Series(entry.getKey, entry.getValue.delta())
        }
        out.result()
    }

    private[kyo] def getLast(): Long = base.getLast()
}

object UnsafeCounter {

    /** One series' delta since its last read. */
    final case class Series(attributes: Attributes, delta: Long)

    final private[kyo] class Cell extends Serializable {
        private var last  = 0L
        private val adder = new LongAdder

        def get(): Long        = adder.sumThenReset()
        def inc(): Unit        = adder.increment()
        def add(v: Long): Unit = adder.add(v)

        private def addExact(a: Long, b: Long) = {
            val sum = a + b
            if (sum < 0) {
                (Long.MaxValue + sum) + 2
            } else {
                sum
            }
        }

        private def findDelta(a: Long, b: Long) = {
            (Long.MaxValue - a) + b
        }

        def delta(): Long = {
            val curr  = addExact(get(), last)
            val delta = if (curr >= last) curr - last else findDelta(last, curr)
            last = curr
            delta
        }

        def getLast(): Long = last
    }
}
