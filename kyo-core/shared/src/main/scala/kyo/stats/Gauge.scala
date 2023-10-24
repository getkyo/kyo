package kyo.stats

import kyo._
import kyo.ios._
import kyo.lists._

trait Gauge {
  def close: Unit > IOs
}

object Gauge {
  val noop: Gauge =
    new Gauge {
      def close = ()
    }

  def all(l: List[Gauge]): Gauge =
    l match {
      case Nil =>
        noop
      case h :: Nil =>
        h
      case l =>
        new Gauge {
          def close = Lists.traverseUnit(l)(_.close)
        }
    }
}
