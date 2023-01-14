package kyo.concurrent

import java.util.concurrent.atomic.AtomicReference

import kyo.core._
import kyo.ios._
import scala.annotation.tailrec
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.atomic.DoubleAdder

object sums {

  opaque type LongSum = LongAdder
  object LongSum {
    inline def apply(): LongSum > IOs = IOs(LongAdder())
  }
  extension (ref: LongSum) {
    inline def add(v: Long): Unit > IOs =
      IOs(ref.add(v))
    inline def decrement: Unit > IOs =
      IOs(ref.decrement())
    inline def get: Long > IOs =
      IOs(ref.sum())
    inline def reset: Unit > IOs =
      IOs(ref.reset())
  }

  opaque type DoubleSum = DoubleAdder
  object DoubleSum {
    inline def apply(): DoubleSum > IOs = IOs(DoubleAdder())
  }
  extension (ref: DoubleSum) {
    inline def add(v: Double): Unit > IOs =
      IOs(ref.add(v))
    inline def get: Double > IOs =
      IOs(ref.sum())
    inline def reset: Unit > IOs =
      IOs(ref.reset())
  }
}
