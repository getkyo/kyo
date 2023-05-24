package kyo.concurrent

import kyo._
import kyo.ios._

import java.util.concurrent.atomic.{DoubleAdder => JDoubleAdder}
import java.util.concurrent.atomic.{LongAdder => JLongAdder}

object adders {

  object Adders {
    def initLong: LongAdder > IOs     = IOs(LongAdder(JLongAdder()))
    def initDouble: DoubleAdder > IOs = IOs(DoubleAdder(JDoubleAdder()))
  }

  class LongAdder private[adders] (private[adders] val ref: JLongAdder) extends AnyVal {
    /*inline(1)*/
    def add(v: Long): Unit > IOs =
      IOs(ref.add(v))
    /*inline(1)*/
    def decrement: Unit > IOs =
      IOs(ref.decrement())
    def increment: Unit > IOs =
      IOs(ref.increment())
    /*inline(1)*/
    def get: Long > IOs =
      IOs(ref.sum())
    /*inline(1)*/
    def reset: Unit > IOs =
      IOs(ref.reset())
  }

  class DoubleAdder private[adders] (private[adders] val ref: JDoubleAdder) extends AnyVal {
    /*inline(1)*/
    def add(v: Double): Unit > IOs =
      IOs(ref.add(v))
    /*inline(1)*/
    def get: Double > IOs =
      IOs(ref.sum())
    /*inline(1)*/
    def reset: Unit > IOs =
      IOs(ref.reset())
  }
}
