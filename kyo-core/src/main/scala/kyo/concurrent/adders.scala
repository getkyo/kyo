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
    inline def apply(): LongAdder > IOs = IOs(JLongAdder())
  }
  extension (ref: LongAdder) {
    inline def add(v: Long): Unit > IOs =
      IOs(ref.add(v))
    inline def decrement: Unit > IOs =
      IOs(ref.decrement())
    inline def get: Long > IOs =
      IOs(ref.sum())
    inline def reset: Unit > IOs =
      IOs(ref.reset())
  }

  opaque type DoubleAdder = JDoubleAdder
  object DoubleAdder {
    inline def apply(): DoubleAdder > IOs = IOs(JDoubleAdder())
  }
  extension (ref: DoubleAdder) {
    inline def add(v: Double): Unit > IOs =
      IOs(ref.add(v))
    inline def get: Double > IOs =
      IOs(ref.sum())
    inline def reset: Unit > IOs =
      IOs(ref.reset())
  }
}
