package kyo.stats.internal

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import scala.annotation.tailrec

object StatsRegistry {

    def scope(path: String*): Scope = new Scope(path.reverse.toList)

    class Scope private[kyo] (reversePath: List[String]) extends Serializable {

        def path: List[String] = reversePath.reverse

        def scope(p: String*) = new Scope(p.reverse.toList ::: reversePath)

        def counter(name: String, description: String = "empty"): UnsafeCounter =
            internal.counters.get(name :: reversePath, description, new UnsafeCounter())

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

        def gauge(name: String, description: String = "empty")(run: => Double): UnsafeGauge =
            internal.gauges.get(name :: reversePath, description, new UnsafeGauge(() => run))

        def counterGauge(name: String, description: String = "empty")(run: => Long): UnsafeCounterGauge =
            internal.counterGauges.get(name :: reversePath, description, new UnsafeCounterGauge(() => run))
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
                val ref   = map.computeIfAbsent(path, _ => (new WeakReference(init), description))._1
                val value = ref.get()
                if (value == null) {
                    map.remove(path)
                    get(reversePath, description, init)
                } else {
                    value
                }
            }
        }

    }
}
