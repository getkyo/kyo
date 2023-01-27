package kyo.concurrent

import java.util.concurrent.atomic.AtomicReference

import kyo.core._
import kyo.ios._
import scala.annotation.tailrec
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.{LongAdder => JLongAdder}
import java.util.concurrent.atomic.{DoubleAdder => JDoubleAdder}

object adders {

  opaque type LongAdder = JLongAdder
  object LongAdder {
    /*inline(1)*/
    def apply(): LongAdder > IOs = IOs(JLongAdder())
  }
  extension (ref: LongAdder) {
    /*inline(1)*/
    def add(v: Long): Unit > IOs =
      IOs(ref.add(v))
    /*inline(1)*/
    def decrement: Unit > IOs =
      IOs(ref.decrement())
    /*inline(1)*/
    def get: Long > IOs =
      IOs(ref.sum())
    /*inline(1)*/
    def reset: Unit > IOs =
      IOs(ref.reset())
  }

  opaque type DoubleAdder = JDoubleAdder
  object DoubleAdder {
    /*inline(1)*/
    def apply(): DoubleAdder > IOs = IOs(JDoubleAdder())
  }
  extension (ref: DoubleAdder) {
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
