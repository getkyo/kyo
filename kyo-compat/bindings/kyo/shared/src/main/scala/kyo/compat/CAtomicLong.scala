package kyo.compat

import kyo.*

/** Underlying carrier is `kyo.AtomicLong`, a lock-free atomic long cell. `lift` and `lower` are identity since the carrier is already a
  * kyo-native atomic. Operations match the `java.util.concurrent.atomic` contract: reads, writes, compare-and-swap, and numeric increments
  * all execute without holding a lock.
  */
opaque type CAtomicLong = AtomicLong

object CAtomicLong:

    /** Allocates a fresh atomic long initialized to `v`. */
    inline def init(inline v: Long)(using inline frame: Frame): CIO[CAtomicLong] =
        CIO.lift(AtomicLong.init(v))

    /** Wraps a native `kyo.AtomicLong` as a `CAtomicLong`. Identity on the carrier. */
    inline def lift(inline u: AtomicLong): CAtomicLong = u

    extension (inline self: CAtomicLong)

        /** Unwraps to the native `kyo.AtomicLong`. Identity on the carrier. */
        inline def lower: AtomicLong = self

        /** Reads the current value. */
        inline def get(using inline frame: Frame): CIO[Long] = CIO.lift(self.get)

        /** Atomically sets the value to `v`. */
        inline def set(inline v: Long)(using inline frame: Frame): CIO[Unit] = CIO.lift(self.set(v))

        /** Atomically sets the value to `v` and returns the previous value. */
        inline def getAndSet(inline v: Long)(using inline frame: Frame): CIO[Long] = CIO.lift(self.getAndSet(v))

        /** Atomically increments by 1 and returns the new value. */
        inline def incrementAndGet(using inline frame: Frame): CIO[Long] = CIO.lift(self.incrementAndGet)

        /** Atomically increments by 1 and returns the previous value. */
        inline def getAndIncrement(using inline frame: Frame): CIO[Long] = CIO.lift(self.getAndIncrement)

        /** Atomically decrements by 1 and returns the new value. */
        inline def decrementAndGet(using inline frame: Frame): CIO[Long] = CIO.lift(self.decrementAndGet)

        /** Atomically decrements by 1 and returns the previous value. */
        inline def getAndDecrement(using inline frame: Frame): CIO[Long] = CIO.lift(self.getAndDecrement)

        /** Atomically adds `delta` and returns the new value. */
        inline def addAndGet(inline delta: Long)(using inline frame: Frame): CIO[Long] = CIO.lift(self.addAndGet(delta))

        /** Atomically adds `delta` and returns the previous value. */
        inline def getAndAdd(inline delta: Long)(using inline frame: Frame): CIO[Long] = CIO.lift(self.getAndAdd(delta))

        /** Atomic compare-and-set: replaces `expected` with `updated` iff the current value equals `expected`. */
        inline def compareAndSet(inline expected: Long, inline updated: Long)(
            using inline frame: Frame
        ): CIO[Boolean] =
            CIO.lift(self.compareAndSet(expected, updated))

    end extension

end CAtomicLong
