package kyo.compat

import java.util.concurrent.atomic.AtomicInteger

/** Underlying carrier is `java.util.concurrent.atomic.AtomicInteger`. The Future ecosystem has no `Frame` / `Trace` to propagate. `lift`
  * and `lower` are identity since the carrier is already a native JDK atomic.
  */
opaque type CAtomicInt = AtomicInteger

object CAtomicInt:

    /** Allocates a fresh atomic int initialized to `v`. */
    inline def init(inline v: Int): CIO[CAtomicInt] =
        CIO.defer(new AtomicInteger(v))

    /** Wraps a native `java.util.concurrent.atomic.AtomicInteger` as a `CAtomicInt`. Identity on the carrier. */
    inline def lift(inline u: AtomicInteger): CAtomicInt = u

    extension (inline self: CAtomicInt)

        /** Unwraps to the native `java.util.concurrent.atomic.AtomicInteger`. Identity on the carrier. */
        inline def lower: AtomicInteger = self

        /** Reads the current value. */
        inline def get: CIO[Int] = CIO.defer(self.get())

        /** Atomically sets the value to `v`. */
        inline def set(inline v: Int): CIO[Unit] = CIO.defer(self.set(v))

        /** Atomically sets the value to `v` and returns the previous value. */
        inline def getAndSet(inline v: Int): CIO[Int] = CIO.defer(self.getAndSet(v))

        /** Atomically increments by 1 and returns the new value. */
        inline def incrementAndGet: CIO[Int] = CIO.defer(self.incrementAndGet())

        /** Atomically increments by 1 and returns the previous value. */
        inline def getAndIncrement: CIO[Int] = CIO.defer(self.getAndIncrement())

        /** Atomically decrements by 1 and returns the new value. */
        inline def decrementAndGet: CIO[Int] = CIO.defer(self.decrementAndGet())

        /** Atomically decrements by 1 and returns the previous value. */
        inline def getAndDecrement: CIO[Int] = CIO.defer(self.getAndDecrement())

        /** Atomically adds `delta` and returns the new value. */
        inline def addAndGet(inline delta: Int): CIO[Int] = CIO.defer(self.addAndGet(delta))

        /** Atomically adds `delta` and returns the previous value. */
        inline def getAndAdd(inline delta: Int): CIO[Int] = CIO.defer(self.getAndAdd(delta))

        /** Atomic compare-and-set: replaces `expected` with `updated` iff the current value equals `expected`. */
        inline def compareAndSet(inline expected: Int, inline updated: Int): CIO[Boolean] =
            CIO.defer(self.compareAndSet(expected, updated))

    end extension

end CAtomicInt
