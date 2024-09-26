package kyo

import java.util.concurrent.atomic as j

/** A wrapper for Java's LongAdder.
  */
final case class LongAdder private (unsafe: LongAdder.Unsafe) extends AnyVal:

    /** Adds the given value to the sum.
      *
      * @param v
      *   The value to add
      * @return
      *   Unit
      */
    inline def add(v: Long)(using inline frame: Frame): Unit < IO = IO(unsafe.add(v))

    /** Decrements the sum by one.
      *
      * @return
      *   Unit
      */
    inline def decrement(using inline frame: Frame): Unit < IO = IO(unsafe.decrement())

    /** Increments the sum by one.
      *
      * @return
      *   Unit
      */
    inline def increment(using inline frame: Frame): Unit < IO = IO(unsafe.increment())

    /** Returns the current sum.
      *
      * @return
      *   The current sum
      */
    inline def get(using inline frame: Frame): Long < IO = IO(unsafe.get())

    /** Resets the sum to zero.
      *
      * @return
      *   Unit
      */
    inline def reset(using inline frame: Frame): Unit < IO = IO(unsafe.reset())

    /** Returns the current sum and resets it to zero.
      *
      * @return
      *   The sum before reset,
      */
    inline def sumThenReset(using inline frame: Frame): Long < IO = IO(unsafe.sumThenReset())

end LongAdder

object LongAdder:

    /** Creates a new LongAdder instance.
      *
      * @return
      *   A new LongAdder
      */
    def init(using frame: Frame): LongAdder < IO = IO(LongAdder(Unsafe.init()))

    opaque type Unsafe = j.LongAdder

    object Unsafe:
        given Flat[Unsafe]                    = Flat.unsafe.bypass
        def init()(using AllowUnsafe): Unsafe = new j.LongAdder

        extension (self: Unsafe)
            inline def add(v: Long)(using inline allow: AllowUnsafe): Unit   = self.add(v)
            inline def decrement()(using inline allow: AllowUnsafe): Unit    = self.decrement()
            inline def increment()(using inline allow: AllowUnsafe): Unit    = self.increment()
            inline def get()(using inline allow: AllowUnsafe): Long          = self.sum()
            inline def reset()(using inline allow: AllowUnsafe): Unit        = self.reset()
            inline def sumThenReset()(using inline allow: AllowUnsafe): Long = self.sumThenReset()
            inline def safe: LongAdder                                       = LongAdder(self)
        end extension
    end Unsafe
end LongAdder

/** A wrapper for Java's DoubleAdde
  */
final case class DoubleAdder private (unsafe: DoubleAdder.Unsafe) extends AnyVal:

    /** Adds the given value to the sum.
      *
      * @param v
      *   The value to add
      * @return
      *   Unit
      */
    inline def add(v: Double)(using inline frame: Frame): Unit < IO = IO(unsafe.add(v))

    /** Returns the current sum.
      *
      * @return
      *   The current sum
      */
    inline def get(using inline frame: Frame): Double < IO = IO(unsafe.get())

    /** Resets the sum to zero.
      *
      * @return
      *   Unit
      */
    inline def reset(using inline frame: Frame): Unit < IO = IO(unsafe.reset())

    /** Returns the current sum and resets it to zero.
      *
      * @return
      *   The sum before reset,
      */
    inline def sumThenReset(using inline frame: Frame): Double < IO = IO(unsafe.sumThenReset())

end DoubleAdder

object DoubleAdder:

    /** Creates a new DoubleAdder instance.
      *
      * @return
      *   A new DoubleAdder
      */
    def init(using Frame): DoubleAdder < IO = IO(DoubleAdder(new j.DoubleAdder))

    opaque type Unsafe = j.DoubleAdder

    object Unsafe:
        given Flat[Unsafe] = Flat.unsafe.bypass

        def init()(using AllowUnsafe): Unsafe = new j.DoubleAdder

        extension (self: Unsafe)
            inline def add(v: Double)(using inline allow: AllowUnsafe): Unit   = self.add(v)
            inline def get()(using inline allow: AllowUnsafe): Double          = self.sum()
            inline def reset()(using inline allow: AllowUnsafe): Unit          = self.reset()
            inline def sumThenReset()(using inline allow: AllowUnsafe): Double = self.sumThenReset()
            inline def safe: DoubleAdder                                       = DoubleAdder(self)
        end extension
    end Unsafe
end DoubleAdder
