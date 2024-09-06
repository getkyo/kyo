package kyo

import java.util.concurrent.atomic as j

/** A wrapper for Java's LongAdder.
  */
class LongAdder private[kyo] (private val ref: j.LongAdder) extends AnyVal:

    /** Adds the given value to the sum.
      *
      * @param v
      *   The value to add
      * @return
      *   Unit
      */
    inline def add(v: Long)(using Frame): Unit < IO = IO(ref.add(v))

    /** Decrements the sum by one.
      *
      * @return
      *   Unit
      */
    inline def decrement(using Frame): Unit < IO = IO(ref.decrement())

    /** Increments the sum by one.
      *
      * @return
      *   Unit
      */
    inline def increment(using Frame): Unit < IO = IO(ref.increment())

    /** Returns the current sum.
      *
      * @return
      *   The current sum
      */
    inline def get(using Frame): Long < IO = IO(ref.sum())

    /** Resets the sum to zero.
      *
      * @return
      *   Unit
      */
    inline def reset(using Frame): Unit < IO = IO(ref.reset())

    /** Returns the current sum and resets it to zero.
      *
      * @return
      *   The sum before reset,
      */
    inline def sumThenReset(using Frame): Long < IO = IO(ref.sumThenReset())
end LongAdder

object LongAdder:
    /** Creates a new LongAdder instance.
      *
      * @return
      *   A new LongAdder
      */
    def init(using Frame): LongAdder < IO = IO(LongAdder(new j.LongAdder))
end LongAdder

/** A wrapper for Java's DoubleAdde
  */
class DoubleAdder private[kyo] (private val ref: j.DoubleAdder) extends AnyVal:

    /** Adds the given value to the sum.
      *
      * @param v
      *   The value to add
      * @return
      *   Unit
      */
    inline def add(v: Double)(using Frame): Unit < IO = IO(ref.add(v))

    /** Returns the current sum.
      *
      * @return
      *   The current sum
      */
    inline def get(using Frame): Double < IO = IO(ref.sum())

    /** Resets the sum to zero.
      *
      * @return
      *   Unit
      */
    inline def reset(using Frame): Unit < IO = IO(ref.reset())

    /** Returns the current sum and resets it to zero.
      *
      * @return
      *   The sum before reset,
      */
    inline def sumThenReset(using Frame): Double < IO = IO(ref.sumThenReset())
end DoubleAdder

object DoubleAdder:

    /** Creates a new DoubleAdder instance.
      *
      * @return
      *   A new DoubleAdder
      */
    def init(using Frame): DoubleAdder < IO = IO(DoubleAdder(new j.DoubleAdder))
end DoubleAdder
