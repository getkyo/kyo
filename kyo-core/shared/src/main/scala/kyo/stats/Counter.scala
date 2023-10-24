package kyo.stats

import kyo._
import kyo.ios._
import kyo.lists._

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
    l match {
      case Nil =>
        noop
      case h :: Nil =>
        h
      case l =>
        new Counter {
          def add(v: Long)                = Lists.traverseUnit(l)(_.add(v))
          def add(v: Long, b: Attributes) = Lists.traverseUnit(l)(_.add(v, b))
          def attributes(b: Attributes)   = all(l.map(_.attributes(b)))
        }
    }
}
