package kyo

import java.util.concurrent.atomic.DoubleAdder as JDoubleAdder
import java.util.concurrent.atomic.LongAdder as JLongAdder
import kyo.internal.Trace

object Adders:
    val initLong: LongAdder < IOs     = IOs(new LongAdder(new JLongAdder()))
    val initDouble: DoubleAdder < IOs = IOs(new DoubleAdder(new JDoubleAdder()))

class LongAdder private[kyo] (private val ref: JLongAdder) extends AnyVal:

    def add(v: Long)(using Trace): Unit < IOs = IOs(ref.add(v))
    def decrement(using Trace): Unit < IOs    = IOs(ref.decrement())
    def increment(using Trace): Unit < IOs    = IOs(ref.increment())
    def get(using Trace): Long < IOs          = IOs(ref.sum())
    def reset(using Trace): Unit < IOs        = IOs(ref.reset())
end LongAdder

class DoubleAdder private[kyo] (private val ref: JDoubleAdder) extends AnyVal:

    def add(v: Double)(using Trace): Unit < IOs = IOs(ref.add(v))
    def get(using Trace): Double < IOs          = IOs(ref.sum())
    def reset(using Trace): Unit < IOs          = IOs(ref.reset())
end DoubleAdder
