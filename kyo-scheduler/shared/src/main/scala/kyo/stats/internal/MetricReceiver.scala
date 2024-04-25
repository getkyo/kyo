package kyo.stats.internal

import java.util.ServiceLoader
import kyo.stats.Attributes

trait MetricReceiver {

    def counter(
        scope: List[String],
        name: String,
        description: String = "",
        unit: String = "",
        a: Attributes = Attributes.empty
    ): UnsafeCounter

    def histogram(
        scope: List[String],
        name: String,
        description: String = "",
        unit: String = "",
        a: Attributes = Attributes.empty
    ): UnsafeHistogram

    def gauge(
        scope: List[String],
        name: String,
        description: String = "",
        unit: String = "",
        a: Attributes = Attributes.empty
    )(f: => Double): UnsafeGauge

}

object MetricReceiver {

    val noop: MetricReceiver =
        new MetricReceiver {
            def counter(
                scope: List[String],
                name: String,
                description: String,
                unit: String,
                a: Attributes
            ) =
                UnsafeCounter.noop
            def histogram(
                scope: List[String],
                name: String,
                description: String,
                unit: String,
                a: Attributes
            ) =
                UnsafeHistogram.noop

            def gauge(
                scope: List[String],
                name: String,
                description: String,
                unit: String,
                a: Attributes
            )(f: => Double) =
                UnsafeGauge.noop
        }

    def all(receivers: List[MetricReceiver]): MetricReceiver =
        new MetricReceiver {

            def counter(
                scope: List[String],
                name: String,
                description: String,
                unit: String,
                a: Attributes
            ) =
                UnsafeCounter.all(receivers.map(_.counter(scope, name, description, unit, a)))

            def histogram(
                scope: List[String],
                name: String,
                description: String,
                unit: String,
                a: Attributes
            ) =
                UnsafeHistogram.all(receivers.map(_.histogram(scope, name, description, unit, a)))

            def gauge(
                scope: List[String],
                name: String,
                description: String,
                unit: String,
                a: Attributes
            )(f: => Double) =
                UnsafeGauge.all(receivers.map(_.gauge(scope, name, description, unit, a)(f)))
        }

    val get: MetricReceiver = {
        val it = ServiceLoader.load(classOf[MetricReceiver]).iterator()
        val b  = List.newBuilder[MetricReceiver]
        while (it.hasNext())
            b += it.next()
        b.result() match {
            case Nil =>
                MetricReceiver.noop
            case head :: Nil =>
                head
            case l =>
                MetricReceiver.all(l)
        }
    }
}
