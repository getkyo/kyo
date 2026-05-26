package kyo.compat

import java.util.concurrent.atomic.AtomicLong

/** Underlying carrier is `java.util.concurrent.atomic.AtomicLong`. The Future ecosystem has no `Frame` / `Trace` to propagate. `lift` and
  * `lower` are identity since the carrier is already a native JDK atomic.
  */
opaque type CAtomicLong = AtomicLong

object CAtomicLong:

    /** Allocates a fresh atomic long initialized to `v`. */
    inline def init(inline v: Long): CIO[CAtomicLong] =
        CIO.defer(new AtomicLong(v))

    /** Wraps a native `java.util.concurrent.atomic.AtomicLong` as a `CAtomicLong`. Identity on the carrier. */
    inline def lift(inline u: AtomicLong): CAtomicLong = u

    extension (inline self: CAtomicLong)

        /** Unwraps to the native `java.util.concurrent.atomic.AtomicLong`. Identity on the carrier. */
        inline def lower: AtomicLong = self

        /** Reads the current value. */
        inline def get: CIO[Long] = CIO.defer(self.get())

        /** Atomically sets the value to `v`. */
        inline def set(inline v: Long): CIO[Unit] = CIO.defer(self.set(v))

        /** Atomically sets the value to `v` and returns the previous value. */
        inline def getAndSet(inline v: Long): CIO[Long] = CIO.defer(self.getAndSet(v))

        /** Atomically increments by 1 and returns the new value. */
        inline def incrementAndGet: CIO[Long] = CIO.defer(self.incrementAndGet())

        /** Atomically increments by 1 and returns the previous value. */
        inline def getAndIncrement: CIO[Long] = CIO.defer(self.getAndIncrement())

        /** Atomically decrements by 1 and returns the new value. */
        inline def decrementAndGet: CIO[Long] = CIO.defer(self.decrementAndGet())

        /** Atomically decrements by 1 and returns the previous value. */
        inline def getAndDecrement: CIO[Long] = CIO.defer(self.getAndDecrement())

        /** Atomically adds `delta` and returns the new value. */
        inline def addAndGet(inline delta: Long): CIO[Long] = CIO.defer(self.addAndGet(delta))

        /** Atomically adds `delta` and returns the previous value. */
        inline def getAndAdd(inline delta: Long): CIO[Long] = CIO.defer(self.getAndAdd(delta))

        /** Atomic compare-and-set: replaces `expected` with `updated` iff the current value equals `expected`. */
        inline def compareAndSet(inline expected: Long, inline updated: Long): CIO[Boolean] =
            CIO.defer(self.compareAndSet(expected, updated))

    end extension

end CAtomicLong
