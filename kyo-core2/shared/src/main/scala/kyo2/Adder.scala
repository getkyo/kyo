package kyo2

import java.util.concurrent.atomic as j

class LongAdder private[kyo2] (private val ref: j.LongAdder) extends AnyVal:
    inline def add(v: Long)(using Frame): Unit < IO = IO(ref.add(v))
    inline def decrement(using Frame): Unit < IO    = IO(ref.decrement())
    inline def increment(using Frame): Unit < IO    = IO(ref.increment())
    inline def get(using Frame): Long < IO          = IO(ref.sum())
    inline def reset(using Frame): Unit < IO        = IO(ref.reset())
    inline def sumThenReset(using Frame): Long < IO = IO(ref.sumThenReset())
end LongAdder

object LongAdder:
    def init(using Frame): LongAdder < IO = IO(LongAdder(new j.LongAdder))

class DoubleAdder private[kyo2] (private val ref: j.DoubleAdder) extends AnyVal:
    inline def add(v: Double)(using Frame): Unit < IO = IO(ref.add(v))
    inline def get(using Frame): Double < IO          = IO(ref.sum())
    inline def reset(using Frame): Unit < IO          = IO(ref.reset())
    inline def sumThenReset(using Frame): Double < IO = IO(ref.sumThenReset())
end DoubleAdder

object DoubleAdder:
    def init(using Frame): DoubleAdder < IO = IO(DoubleAdder(new j.DoubleAdder))
