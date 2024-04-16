package kyo.stats.internal

import java.util.ServiceLoader
import kyo.stats.*
import kyo.stats.Attributes
import scala.jdk.CollectionConverters.*

trait MetricReceiver:

    def counter(
        scope: List[String],
        name: String,
        description: String,
        unit: String,
        a: Attributes
    ): Counter

    def histogram(
        scope: List[String],
        name: String,
        description: String,
        unit: String,
        a: Attributes
    ): Histogram

    def gauge(
        scope: List[String],
        name: String,
        description: String,
        unit: String,
        a: Attributes
    )(f: => Double): Gauge

end MetricReceiver

object MetricReceiver:

    val noop: MetricReceiver =
        new MetricReceiver:
            def counter(
                scope: List[String],
                name: String,
                description: String,
                unit: String,
                a: Attributes
            ) =
                Counter.noop
            def histogram(
                scope: List[String],
                name: String,
                description: String,
                unit: String,
                a: Attributes
            ) =
                Histogram.noop

            def gauge(
                scope: List[String],
                name: String,
                description: String,
                unit: String,
                a: Attributes
            )(f: => Double) =
                Gauge.noop

            def startSpan(
                scope: List[String],
                name: String,
                parent: Option[Span] = None,
                attributes: Attributes = Attributes.empty
            ) =
                Span.noop

    def all(receivers: List[MetricReceiver]): MetricReceiver =
        new MetricReceiver:

            def counter(
                scope: List[String],
                name: String,
                description: String,
                unit: String,
                a: Attributes
            ) =
                Counter.all(receivers.map(_.counter(scope, name, description, unit, a)))

            def histogram(
                scope: List[String],
                name: String,
                description: String,
                unit: String,
                a: Attributes
            ) =
                Histogram.all(receivers.map(_.histogram(scope, name, description, unit, a)))

            def gauge(
                scope: List[String],
                name: String,
                description: String,
                unit: String,
                a: Attributes
            )(f: => Double) =
                Gauge.all(receivers.map(_.gauge(scope, name, description, unit, a)(f)))

    val get: MetricReceiver =
        ServiceLoader.load(classOf[MetricReceiver]).iterator().asScala.toList match
            case Nil =>
                MetricReceiver.noop
            case head :: Nil =>
                head
            case l =>
                MetricReceiver.all(l)
end MetricReceiver
