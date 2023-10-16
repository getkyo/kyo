package kyo.stats

import kyo._
import kyo.ios._
import kyo.choices._

trait Histogram {

  def observe(v: Double): Unit > IOs

  def observe(v: Double, b: Attributes): Unit > IOs

  def attributes(b: Attributes): Histogram
}

object Histogram {

  val noop: Histogram =
    new Histogram {
      def observe(v: Double)                = ()
      def observe(v: Double, b: Attributes) = ()
      def attributes(b: Attributes)         = this
    }

  def all(l: List[Histogram]): Histogram =
    l match {
      case Nil =>
        noop
      case h :: Nil =>
        h
      case l =>
        new Histogram {
          def observe(v: Double)                = Choices.traverseUnit(l)(_.observe(v))
          def observe(v: Double, b: Attributes) = Choices.traverseUnit(l)(_.observe(v, b))
          def attributes(b: Attributes)         = all(l.map(_.attributes(b)))
        }
    }

}
