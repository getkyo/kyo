package kyo.stats.machine

import kyo.*
import kyo.stats.internal.StatsRegistry

/** Test-support seam: a one-shot snapshot of the machine.* metrics currently in the shared `kyo.Stat`
  * registry, read exactly the way a metrics exporter reads it (`StatsRegistry.internal.<store>.map`, the same
  * enumeration `OTLPMetricsExporter` flushes from).
  *
  * It lives in package `kyo.stats.machine` because `StatsRegistry.internal` is `private[kyo]`, and it is a
  * plain value read (no effect suspension, so no `Frame` is needed inside the `kyo` package). Histograms and
  * gauges are read without side effect (`summary()` sums buckets, `collect()` re-polls); cumulative Counters
  * are read with `delta()`, which is the SAME destructive delta-temporality read the OTLP exporter performs
  * per flush (a counter carries no non-destructive cumulative accessor: `get()`/`delta()` both drain the
  * adder, by design, because counters export as deltas). This snapshot is the terminal reader in the demo
  * run (no real exporter runs concurrently), so draining the counter is correct: it yields the cpu-time
  * accumulated since the sampler's baseline tick. The `demo` package's `MachineStatsDemo` calls this to
  * observe what auto-load produced.
  */
object MachineRegistrySnapshot:

    /** One machine.* metric: dotted path, kind, a representative value, the observation count that proves
      * the sampler ticked (histogram bucket count, or the cumulative counter/gauge value), and the running
      * sum. For a histogram, `sum` is the summary's own running sum (the cumulative total a `.rate`
      * histogram carries in place of a separate cumulative Counter); for a counter, counter-gauge, or gauge,
      * `sum` degenerates to the same number as `value`, since those kinds have no separate sum concept.
      */
    case class Reading(path: String, kind: String, value: Double, observations: Long, sum: Double) derives CanEqual

    /** Every machine.* metric currently live in the registry, sorted by path. */
    def read(using AllowUnsafe): Chunk[Reading] =
        val registry = StatsRegistry.internal
        val readings = ChunkBuilder.init[Reading]

        registry.histograms.map.forEach { (path, tuple) =>
            val h = tuple._1.get()
            if h != null && path.headOption.contains("machine") then
                val s = h.summary()
                if s.count > 0 then
                    val _ = readings.addOne(Reading(path.mkString("."), "histogram", s.max, s.count, s.sum))
            end if
        }
        registry.counters.map.forEach { (path, tuple) =>
            val c = tuple._1.get()
            if c != null && path.headOption.contains("machine") then
                // delta() is the exporter's own counter read (delta-temporality): it returns the cpu-time
                // accumulated since the last flush and advances the baseline. As the terminal one-shot reader
                // here, that is the correct cumulative-since-baseline value; there is no non-destructive
                // cumulative accessor on UnsafeCounter (get()/delta() both drain, by design).
                val cumulative = c.delta()
                if cumulative > 0L then
                    val _ =
                        readings.addOne(Reading(path.mkString("."), "counter", cumulative.toDouble, cumulative, cumulative.toDouble))
            end if
        }
        registry.counterGauges.map.forEach { (path, tuple) =>
            val g = tuple._1.get()
            if g != null && path.headOption.contains("machine") then
                val v = g.collect()
                val _ = readings.addOne(Reading(path.mkString("."), "counter-gauge", v.toDouble, v, v.toDouble))
        }
        registry.gauges.map.forEach { (path, tuple) =>
            val g = tuple._1.get()
            if g != null && path.headOption.contains("machine") then
                val v = g.collect()
                val _ = readings.addOne(Reading(path.mkString("."), "gauge", v, 1L, v))
        }

        readings.result().sortBy(_.path)
    end read

    /** The running host OS label, as the sampler resolved it. */
    def hostOs(using AllowUnsafe): String = System.live.unsafe.operatingSystem().toString

end MachineRegistrySnapshot
