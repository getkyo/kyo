package kyo.compat

import kyo.*

/** Underlying carrier is `kyo.AtomicInt`, a lock-free atomic int cell. `lift` and `lower` are identity since the carrier is already a
  * kyo-native atomic. Operations match the `java.util.concurrent.atomic` contract: reads, writes, compare-and-swap, and numeric increments
  * all execute without holding a lock.
  */
opaque type CAtomicInt = AtomicInt

object CAtomicInt:

    /** Allocates a fresh atomic int initialized to `v`. */
    inline def init(inline v: Int)(using inline frame: Frame): CIO[CAtomicInt] =
        CIO.lift(AtomicInt.init(v))

    /** Wraps a native `kyo.AtomicInt` as a `CAtomicInt`. Identity on the carrier. */
    inline def lift(inline u: AtomicInt): CAtomicInt = u

    extension (inline self: CAtomicInt)

        /** Unwraps to the native `kyo.AtomicInt`. Identity on the carrier. */
        inline def lower: AtomicInt = self

        /** Reads the current value. */
        inline def get(using inline frame: Frame): CIO[Int] = CIO.lift(self.get)

        /** Atomically sets the value to `v`. */
        inline def set(inline v: Int)(using inline frame: Frame): CIO[Unit] = CIO.lift(self.set(v))

        /** Atomically sets the value to `v` and returns the previous value. */
        inline def getAndSet(inline v: Int)(using inline frame: Frame): CIO[Int] = CIO.lift(self.getAndSet(v))

        /** Atomically increments by 1 and returns the new value. */
        inline def incrementAndGet(using inline frame: Frame): CIO[Int] = CIO.lift(self.incrementAndGet)

        /** Atomically increments by 1 and returns the previous value. */
        inline def getAndIncrement(using inline frame: Frame): CIO[Int] = CIO.lift(self.getAndIncrement)

        /** Atomically decrements by 1 and returns the new value. */
        inline def decrementAndGet(using inline frame: Frame): CIO[Int] = CIO.lift(self.decrementAndGet)

        /** Atomically decrements by 1 and returns the previous value. */
        inline def getAndDecrement(using inline frame: Frame): CIO[Int] = CIO.lift(self.getAndDecrement)

        /** Atomically adds `delta` and returns the new value. */
        inline def addAndGet(inline delta: Int)(using inline frame: Frame): CIO[Int] = CIO.lift(self.addAndGet(delta))

        /** Atomically adds `delta` and returns the previous value. */
        inline def getAndAdd(inline delta: Int)(using inline frame: Frame): CIO[Int] = CIO.lift(self.getAndAdd(delta))

        /** Atomic compare-and-set: replaces `expected` with `updated` iff the current value equals `expected`. */
        inline def compareAndSet(inline expected: Int, inline updated: Int)(
            using inline frame: Frame
        ): CIO[Boolean] =
            CIO.lift(self.compareAndSet(expected, updated))

    end extension

end CAtomicInt
