package kyo.compat

import zio.*

/** Underlying carrier is `zio.Ref[Long]`. Operations propagate ZIO `Trace` through `(using inline trace: Trace)` on every entry point.
  * `lift` and `lower` are identity since the carrier is already a native ZIO ref. ZIO has no specialised `AtomicLong`; arithmetic
  * operations compose via `Ref[Long]` updates, and `compareAndSet` is composed via `Ref.modify` since `zio.Ref` exposes no native CAS
  * primitive.
  */
opaque type CAtomicLong = Ref[Long]

object CAtomicLong:

    /** Allocates a fresh atomic long initialized to `v`. */
    inline def init(inline v: Long)(using inline trace: Trace): CIO[CAtomicLong] =
        CIO.lift(Ref.make[Long](v))

    /** Wraps a native `zio.Ref[Long]` as a `CAtomicLong`. Identity on the carrier. */
    inline def lift(inline u: Ref[Long]): CAtomicLong = u

    extension (inline self: CAtomicLong)

        /** Unwraps to the native `zio.Ref[Long]`. Identity on the carrier. */
        inline def lower: Ref[Long] = self

        /** Reads the current value. */
        inline def get(using inline trace: Trace): CIO[Long] =
            CIO.lift(self.get)

        /** Atomically sets the value to `v`. */
        inline def set(inline v: Long)(using inline trace: Trace): CIO[Unit] =
            CIO.lift(self.set(v))

        /** Atomically sets the value to `v` and returns the previous value. */
        inline def getAndSet(inline v: Long)(using inline trace: Trace): CIO[Long] =
            CIO.lift(self.getAndSet(v))

        /** Atomically increments by 1 and returns the new value. */
        inline def incrementAndGet(using inline trace: Trace): CIO[Long] =
            CIO.lift(self.updateAndGet(_ + 1L))

        /** Atomically increments by 1 and returns the previous value. */
        inline def getAndIncrement(using inline trace: Trace): CIO[Long] =
            CIO.lift(self.getAndUpdate(_ + 1L))

        /** Atomically decrements by 1 and returns the new value. */
        inline def decrementAndGet(using inline trace: Trace): CIO[Long] =
            CIO.lift(self.updateAndGet(_ - 1L))

        /** Atomically decrements by 1 and returns the previous value. */
        inline def getAndDecrement(using inline trace: Trace): CIO[Long] =
            CIO.lift(self.getAndUpdate(_ - 1L))

        /** Atomically adds `delta` and returns the new value. */
        inline def addAndGet(inline delta: Long)(using inline trace: Trace): CIO[Long] =
            CIO.lift(self.updateAndGet(_ + delta))

        /** Atomically adds `delta` and returns the previous value. */
        inline def getAndAdd(inline delta: Long)(using inline trace: Trace): CIO[Long] =
            CIO.lift(self.getAndUpdate(_ + delta))

        /** Atomic compare-and-set: replaces `expected` with `updated` iff the current value equals `expected`. */
        inline def compareAndSet(inline expected: Long, inline updated: Long)(
            using inline trace: Trace
        ): CIO[Boolean] =
            CIO.lift(self.modify(cur => if cur == expected then (true, updated) else (false, cur)))
    end extension

end CAtomicLong
