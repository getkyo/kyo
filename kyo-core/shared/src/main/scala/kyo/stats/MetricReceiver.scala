package kyo.stats

import kyo.stats.metrics._
import kyo.stats.attributes._
import java.util.ServiceLoader
import scala.jdk.CollectionConverters._

trait MetricReceiver {
  def counter(name: String, description: String, unit: String, a: Attributes): Counter
  def histogram(name: String, description: String, unit: String, a: Attributes): Histogram
}

object MetricReceiver {
  val get: MetricReceiver =
    ServiceLoader.load(classOf[MetricReceiver]).iterator().asScala.toList match {
      case Nil =>
        MetricReceiver.noop
      case head :: Nil =>
        head
      case l =>
        MetricReceiver.all(l)
    }
  val noop: MetricReceiver =
    new MetricReceiver {
      def counter(name: String, description: String, unit: String, a: Attributes) =
        Counter.noop
      def histogram(name: String, description: String, unit: String, a: Attributes) =
        Histogram.noop
    }
  def all(receivers: List[MetricReceiver]): MetricReceiver =
    new MetricReceiver {
      def counter(name: String, description: String, unit: String, a: Attributes) =
        Counter.all(receivers.map(_.counter(name, description, unit, a)))
      def histogram(name: String, description: String, unit: String, a: Attributes) =
        Histogram.all(receivers.map(_.histogram(name, description, unit, a)))
    }
}
