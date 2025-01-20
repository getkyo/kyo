package kyo

import java.util.concurrent.atomic as j

/** A wrapper for Java's LongAdder.
  */
final case class LongAdder private (unsafe: LongAdder.Unsafe):

    /** Adds the given value to the sum.
      *
      * @param v
      *   The value to add
      * @return
      *   Unit
      */
    inline def add(v: Long)(using inline frame: Frame): Unit < IO = IO.Unsafe(unsafe.add(v))

    /** Decrements the sum by one.
      *
      * @return
      *   Unit
      */
    inline def decrement(using inline frame: Frame): Unit < IO = IO.Unsafe(unsafe.decrement())

    /** Increments the sum by one.
      *
      * @return
      *   Unit
      */
    inline def increment(using inline frame: Frame): Unit < IO = IO.Unsafe(unsafe.increment())

    /** Returns the current sum.
      *
      * @return
      *   The current sum
      */
    inline def get(using inline frame: Frame): Long < IO = IO.Unsafe(unsafe.get())

    /** Resets the sum to zero.
      *
      * @return
      *   Unit
      */
    inline def reset(using inline frame: Frame): Unit < IO = IO.Unsafe(unsafe.reset())

    /** Returns the current sum and resets it to zero.
      *
      * @return
      *   The sum before reset,
      */
    inline def sumThenReset(using inline frame: Frame): Long < IO = IO.Unsafe(unsafe.sumThenReset())

end LongAdder

object LongAdder:

    /** Creates a new LongAdder instance.
      *
      * @return
      *   A new LongAdder
      */
    def init(using frame: Frame): LongAdder < IO = initWith(identity)

    /** Uses a new LongAdder.
      * @param f
      *   The function to apply to the new LongAdder
      * @return
      *   The result of applying the function
      */
    inline def initWith[A, S](inline f: LongAdder => A < S)(using inline frame: Frame): A < (IO & S) =
        IO.Unsafe(f(LongAdder(Unsafe.init())))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    opaque type Unsafe = j.LongAdder

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
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
final case class DoubleAdder private (unsafe: DoubleAdder.Unsafe):

    /** Adds the given value to the sum.
      *
      * @param v
      *   The value to add
      * @return
      *   Unit
      */
    inline def add(v: Double)(using inline frame: Frame): Unit < IO = IO.Unsafe(unsafe.add(v))

    /** Returns the current sum.
      *
      * @return
      *   The current sum
      */
    inline def get(using inline frame: Frame): Double < IO = IO.Unsafe(unsafe.get())

    /** Resets the sum to zero.
      *
      * @return
      *   Unit
      */
    inline def reset(using inline frame: Frame): Unit < IO = IO.Unsafe(unsafe.reset())

    /** Returns the current sum and resets it to zero.
      *
      * @return
      *   The sum before reset,
      */
    inline def sumThenReset(using inline frame: Frame): Double < IO = IO.Unsafe(unsafe.sumThenReset())

end DoubleAdder

object DoubleAdder:

    /** Creates a new DoubleAdder instance.
      *
      * @return
      *   A new DoubleAdder
      */
    def init(using Frame): DoubleAdder < IO = initWith(identity)

    /** Uses a new DoubleAdder.
      * @param f
      *   The function to apply to the new DoubleAdder
      * @return
      *   The result of applying the function
      */
    inline def initWith[A, S](inline f: DoubleAdder => A < S)(using inline frame: Frame): A < (IO & S) =
        IO.Unsafe(f(DoubleAdder(Unsafe.init())))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    opaque type Unsafe = j.DoubleAdder

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
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
