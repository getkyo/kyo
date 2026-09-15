package kyo.stats.internal

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import scala.annotation.tailrec

object StatsRegistry {

    def scope(path: String*): Scope = new Scope(path.reverse.toList)

    /** A live instrument held at a registry path, tagged with the kind it was registered as.
      *
      * A registry key is kind-scoped: the same path may hold a counter and a histogram at once, so an
      * enumeration has to say which store an entry came from rather than leaving the caller to guess from
      * the name. Each case carries the unsafe handle itself, so a consumer reads the producer's live values
      * (`summary()`, `collect()`, `delta()`) without registering anything of its own.
      */
    sealed trait Instrument extends Serializable {

        /** The description the instrument was first registered with. */
        def description: String
    }

    object Instrument {
        case class Counter(unsafe: UnsafeCounter, description: String)           extends Instrument
        case class Histogram(unsafe: UnsafeHistogram, description: String)       extends Instrument
        case class Gauge(unsafe: UnsafeGauge, description: String)               extends Instrument
        case class CounterGauge(unsafe: UnsafeCounterGauge, description: String) extends Instrument
    }

    /** One registered metric: its full registry key and the instrument at it.
      *
      * `path` is the flattened key, the same `List[String]` a `Scope`'s own `path` plus the instrument name
      * produce, so a consumer that discovered a metric here can address it again through `scope(path.init:
      * _*).find*(path.last)`.
      */
    case class Registration(path: List[String], instrument: Instrument)

    /** Every instrument currently registered under `prefix`, sorted by key.
      *
      * This is the read side of the registry: an exporter, a dashboard or a test asks what the process has
      * actually produced instead of guessing paths. It is the only way to reach a family whose names are
      * discovered at runtime rather than documented, such as kyo-stats-machine's per-mount
      * `machine.disk.<store>.*` and its `machine.pressure.<resource>.<kind>.*` pairs.
      *
      * The snapshot is a point-in-time copy: it holds the instruments alive for as long as the returned
      * value is, but it does not observe registrations that happen after it returns. Reading an instrument
      * from it has whatever effect that instrument's accessor has, so `UnsafeCounter.get`/`delta` still
      * drain, exactly as they do for the producer.
      *
      * @param prefix
      *   leading key segments an entry must match; no segments enumerates the whole registry.
      */
    def snapshot(prefix: String*): Seq[Registration] = {
        val want    = prefix.toList
        val entries = List.newBuilder[Registration]

        def collect[A <: AnyRef](store: internal.Store[A], wrap: (A, String) => Instrument): Unit = {
            val it = store.map.entrySet().iterator()
            while (it.hasNext) {
                val entry = it.next()
                val path  = entry.getKey
                if (path.startsWith(want)) {
                    val value = entry.getValue._1.get()
                    if (value != null)
                        entries += Registration(path, wrap(value, entry.getValue._2))
                }
            }
        }

        collect[UnsafeCounter](internal.counters, Instrument.Counter(_, _))
        collect[UnsafeHistogram](internal.histograms, Instrument.Histogram(_, _))
        collect[UnsafeGauge](internal.gauges, Instrument.Gauge(_, _))
        collect[UnsafeCounterGauge](internal.counterGauges, Instrument.CounterGauge(_, _))

        entries.result().sortBy(_.path)
    }

    /** Lexicographic order over registry keys, so a snapshot reads in the order a taxonomy is documented in. */
    implicit private val pathOrdering: Ordering[List[String]] = Ordering.Implicits.seqOrdering

    class Scope private[kyo] (reversePath: List[String]) extends Serializable {

        def path: List[String] = reversePath.reverse

        def scope(p: String*) = new Scope(p.reverse.toList ::: reversePath)

        /** Registers a counter at `name`, or returns the one already registered there.
          *
          * Registration is first-writer-wins per key: a later call with the same path returns the instrument
          * the first call created, which is what lets a producer and a consumer share one handle. Use
          * `findCounter` to READ a counter someone else registered; this method mints one when the path is
          * empty, which turns a misspelled path into a permanent, always-zero series.
          */
        def counter(name: String, description: String = "empty"): UnsafeCounter =
            internal.counters.get(name :: reversePath, description, new UnsafeCounter())

        /** Registers a histogram at `name`, or returns the one already registered there.
          *
          * First-writer-wins per key, as for `counter`: the first registration's `boundaries` are the ones
          * that stand, and a later call with different boundaries silently gets the first histogram. Use
          * `findHistogram` to read one back.
          */
        def histogram(
            name: String,
            description: String = "empty",
            boundaries: Array[Double] = UnsafeHistogram.defaultBoundaries
        ): UnsafeHistogram =
            internal.histograms.get(
                name :: reversePath,
                description,
                new UnsafeHistogram(boundaries)
            )

        /** Registers a gauge at `name`, or returns the one already registered there.
          *
          * A gauge's value IS its thunk, and registration is first-writer-wins, so the first caller to reach
          * a path decides what every later holder of that gauge reads. That makes this the one instrument a
          * READER must never mint: a consumer that calls `gauge(name)(placeholder)` before the producer
          * registers wins the path, and the producer's later registration silently hands back the consumer's
          * placeholder for the process lifetime. Read a gauge with `findGauge`, which never registers.
          */
        def gauge(name: String, description: String = "empty")(run: => Double): UnsafeGauge =
            internal.gauges.get(name :: reversePath, description, new UnsafeGauge(() => run))

        /** Registers a counter gauge at `name`, or returns the one already registered there. First-writer-wins
          * per key, with the same reader hazard as `gauge`: read one back with `findCounterGauge`.
          */
        def counterGauge(name: String, description: String = "empty")(run: => Long): UnsafeCounterGauge =
            internal.counterGauges.get(name :: reversePath, description, new UnsafeCounterGauge(() => run))

        /** The counter registered at `name`, or `None` when nothing is registered there.
          *
          * A lookup, never a registration: an unknown path stays unknown instead of becoming a fresh
          * always-zero instrument an exporter would then publish.
          */
        def findCounter(name: String): Option[UnsafeCounter] =
            internal.counters.find(name :: reversePath)

        /** The histogram registered at `name`, or `None` when nothing is registered there. Never registers. */
        def findHistogram(name: String): Option[UnsafeHistogram] =
            internal.histograms.find(name :: reversePath)

        /** The gauge registered at `name`, or `None` when nothing is registered there.
          *
          * This is the accessor a consumer of someone else's gauge must use. `gauge` would register the
          * caller's own thunk at the path and permanently shadow the producer's value.
          */
        def findGauge(name: String): Option[UnsafeGauge] =
            internal.gauges.find(name :: reversePath)

        /** The counter gauge registered at `name`, or `None` when nothing is registered there. Never registers. */
        def findCounterGauge(name: String): Option[UnsafeCounterGauge] =
            internal.counterGauges.find(name :: reversePath)
    }

    private[kyo] object internal {

        val counters      = new Store[UnsafeCounter]
        val histograms    = new Store[UnsafeHistogram]
        val gauges        = new Store[UnsafeGauge]
        val counterGauges = new Store[UnsafeCounterGauge]

        class Store[A <: AnyRef] extends Serializable {
            val map = new ConcurrentHashMap[List[String], (WeakReference[A], String)]

            @tailrec final def get(reversePath: List[String], description: String, init: => A): A = {
                val path  = reversePath.reverse
                val entry = map.computeIfAbsent(path, _ => (new WeakReference(init), description))
                val value = entry._1.get()
                if (value == null) {
                    // Conditional on the exact entry that was observed cleared: an unconditional remove would
                    // drop a live registration another thread installed between the read and the removal.
                    map.remove(path, entry)
                    get(reversePath, description, init)
                } else {
                    value
                }
            }

            /** The instrument at `reversePath`, or `None`. Never installs one, so a miss leaves the registry
              * exactly as it was.
              */
            final def find(reversePath: List[String]): Option[A] = {
                val path  = reversePath.reverse
                val entry = map.get(path)
                if (entry == null) None
                else {
                    val value = entry._1.get()
                    if (value == null) {
                        map.remove(path, entry)
                        None
                    } else Some(value)
                }
            }
        }

    }
}
