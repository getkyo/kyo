package kyo.stats

import kyo._
import kyo.ios._
import kyo.choices._
import kyo.stats.Attributes

trait Counter {
  def inc: Unit > IOs = add(1)
  def add(v: Long): Unit > IOs
  def add(v: Long, b: Attributes): Unit > IOs
  def attributes(b: Attributes): Counter
}

object Counter {

  val noop: Counter =
    new Counter {
      def add(v: Long)                = ()
      def add(v: Long, b: Attributes) = ()
      def attributes(b: Attributes)   = this
    }

  def all(l: List[Counter]): Counter =
    new Counter {
      def add(v: Long)                = Choices.traverseUnit(l)(_.add(v))
      def add(v: Long, b: Attributes) = Choices.traverseUnit(l)(_.add(v, b))
      def attributes(b: Attributes)   = all(l.map(_.attributes(b)))
    }
}
