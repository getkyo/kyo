package kyo2

import java.util.concurrent.atomic.DoubleAdder
import java.util.concurrent.atomic.LongAdder

object Adder:

    def initLong(using Frame): OfLong < IO     = IO(OfLong(new LongAdder))
    def initDouble(using Frame): OfDouble < IO = IO(OfDouble(new DoubleAdder))

    class OfLong private[kyo2] (private val ref: LongAdder) extends AnyVal:
        inline def add(v: Long)(using Frame): Unit < IO = IO(ref.add(v))
        inline def decrement(using Frame): Unit < IO    = IO(ref.decrement())
        inline def increment(using Frame): Unit < IO    = IO(ref.increment())
        inline def get(using Frame): Long < IO          = IO(ref.sum())
        inline def reset(using Frame): Unit < IO        = IO(ref.reset())
    end OfLong

    class OfDouble private[kyo2] (private val ref: DoubleAdder) extends AnyVal:
        inline def add(v: Double)(using Frame): Unit < IO = IO(ref.add(v))
        inline def get(using Frame): Double < IO          = IO(ref.sum())
        inline def reset(using Frame): Unit < IO          = IO(ref.reset())
    end OfDouble

end Adder
