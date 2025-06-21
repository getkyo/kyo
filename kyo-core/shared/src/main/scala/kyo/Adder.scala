package kyo

import java.util.concurrent.atomic as j

/** A high-performance accumulator optimized for concurrent updates.
  *
  * LongAdder provides efficient methods for concurrent addition and increment operations by reducing contention between threads. Rather
  * than using a single counter that all threads must atomically update, LongAdder maintains multiple internal counters that are dynamically
  * distributed across threads, which are then combined on read operations.
  *
  * This approach offers significant performance benefits in high-contention scenarios:
  *   - Write operations (add, increment) are extremely fast with minimal thread interference
  *   - Read operations (get) are slightly slower as they must sum across all internal counters
  *
  * On the JVM, this is implemented using `java.util.concurrent.atomic.LongAdder`, while other platforms use equivalent specialized
  * implementations.
  *
  * Ideal for:
  *   - High-throughput statistics collection
  *   - Concurrent request counters
  *   - Performance monitoring systems
  *   - Any scenario with many updates and fewer reads
  *
  * @see
  *   [[kyo.AtomicLong]] Alternative with immediate visibility of updates and faster reads, but slower writes under contention.
  */
final case class LongAdder private (unsafe: LongAdder.Unsafe):

    /** Adds the given value to the sum.
      *
      * This operation is highly optimized for concurrent use. Under contention, different threads may update different internal counters,
      * minimizing thread synchronization overhead.
      *
      * @param v
      *   The value to add
      * @return
      *   Unit
      */
    inline def add(v: Long)(using inline frame: Frame): Unit < Sync = Sync.Unsafe(unsafe.add(v))

    /** Decrements the sum by one.
      *
      * This is functionally equivalent to `add(-1)` but may be more efficiently implemented on some platforms.
      *
      * @return
      *   Unit
      */
    inline def decrement(using inline frame: Frame): Unit < Sync = Sync.Unsafe(unsafe.decrement())

    /** Increments the sum by one.
      *
      * This is functionally equivalent to `add(1)` but may be more efficiently implemented on some platforms.
      *
      * @return
      *   Unit
      */
    inline def increment(using inline frame: Frame): Unit < Sync = Sync.Unsafe(unsafe.increment())

    /** Returns the current sum.
      *
      * This operation requires calculating a sum across all internal counters and is therefore more expensive than increment/add
      * operations. In high-performance scenarios, it's best to minimize calls to this method if possible.
      *
      * @return
      *   The current sum
      */
    inline def get(using inline frame: Frame): Long < Sync = Sync.Unsafe(unsafe.get())

    /** Resets the sum to zero.
      *
      * This operation resets all internal counters to zero, which may require synchronization across all of them.
      *
      * @return
      *   Unit
      */
    inline def reset(using inline frame: Frame): Unit < Sync = Sync.Unsafe(unsafe.reset())

    /** Returns the current sum and resets it to zero.
      *
      * This is an atomic operation combining get and reset, which can be more efficient than calling them separately when both operations
      * are needed.
      *
      * @return
      *   The sum before reset,
      */
    inline def sumThenReset(using inline frame: Frame): Long < Sync = Sync.Unsafe(unsafe.sumThenReset())

end LongAdder

object LongAdder:

    /** Creates a new LongAdder instance.
      *
      * @return
      *   A new LongAdder
      */
    def init(using frame: Frame): LongAdder < Sync = initWith(identity)

    /** Uses a new LongAdder.
      * @param f
      *   The function to apply to the new LongAdder
      * @return
      *   The result of applying the function
      */
    inline def initWith[A, S](inline f: LongAdder => A < S)(using inline frame: Frame): A < (Sync & S) =
        Sync.Unsafe(f(LongAdder(Unsafe.init())))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    opaque type Unsafe = j.LongAdder

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
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

/** A high-performance accumulator for double values optimized for concurrent updates.
  *
  * DoubleAdder provides efficient methods for concurrent addition operations on double-precision floating-point values. Like LongAdder, it
  * maintains multiple internal accumulators that are dynamically distributed across threads, which are then combined during read
  * operations.
  *
  * This approach significantly reduces thread contention:
  *   - Addition operations have minimal thread synchronization overhead
  *   - Read operations are relatively more expensive as they must combine values from all internal cells
  *
  * On the JVM, this is implemented using `java.util.concurrent.atomic.DoubleAdder`, while other platforms use equivalent specialized
  * implementations.
  *
  * Ideal for:
  *   - Accumulating floating-point measurements
  *   - Scientific computing with concurrent updates
  *   - Financial calculations requiring high throughput
  *   - Statistics gathering with decimal precision
  *
  * @see
  *   [[AtomicLong]] Alternative for integer values with immediate visibility but higher contention on updates
  */
final case class DoubleAdder private (unsafe: DoubleAdder.Unsafe):

    /** Adds the given value to the sum.
      *
      * This operation is highly optimized for concurrent use. Under contention, different threads may update different internal
      * accumulators, minimizing thread synchronization overhead.
      *
      * @param v
      *   The value to add
      * @return
      *   Unit
      */
    inline def add(v: Double)(using inline frame: Frame): Unit < Sync = Sync.Unsafe(unsafe.add(v))

    /** Returns the current sum.
      *
      * This operation requires calculating a sum across all internal accumulators and is therefore more expensive than add operations. In
      * high-performance scenarios, minimize calls to this method if possible.
      *
      * @return
      *   The current sum
      */
    inline def get(using inline frame: Frame): Double < Sync = Sync.Unsafe(unsafe.get())

    /** Resets the sum to zero.
      *
      * This operation resets all internal accumulators to zero, which may require synchronization across all of them.
      *
      * @return
      *   Unit
      */
    inline def reset(using inline frame: Frame): Unit < Sync = Sync.Unsafe(unsafe.reset())

    /** Returns the current sum and resets it to zero.
      *
      * This is an atomic operation combining get and reset, which can be more efficient than calling them separately when both operations
      * are needed.
      *
      * @return
      *   The sum before reset,
      */
    inline def sumThenReset(using inline frame: Frame): Double < Sync = Sync.Unsafe(unsafe.sumThenReset())

end DoubleAdder

object DoubleAdder:

    /** Creates a new DoubleAdder instance.
      *
      * @return
      *   A new DoubleAdder
      */
    def init(using Frame): DoubleAdder < Sync = initWith(identity)

    /** Uses a new DoubleAdder.
      * @param f
      *   The function to apply to the new DoubleAdder
      * @return
      *   The result of applying the function
      */
    inline def initWith[A, S](inline f: DoubleAdder => A < S)(using inline frame: Frame): A < (Sync & S) =
        Sync.Unsafe(f(DoubleAdder(Unsafe.init())))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    opaque type Unsafe = j.DoubleAdder

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
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
