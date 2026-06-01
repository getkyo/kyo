package kyo.compat

import zio.*

/** Underlying carrier is `zio.Ref[Int]`. Operations propagate ZIO `Trace` through `(using inline trace: Trace)` on every entry point.
  * `lift` and `lower` are identity since the carrier is already a native ZIO ref. ZIO has no specialised `AtomicInt`; arithmetic operations
  * compose via `Ref[Int]` updates, and `compareAndSet` is composed via `Ref.modify` since `zio.Ref` exposes no native CAS primitive.
  */
opaque type CAtomicInt = Ref[Int]

object CAtomicInt:

    /** Allocates a fresh atomic int initialized to `v`. */
    inline def init(inline v: Int)(using inline trace: Trace): CIO[CAtomicInt] =
        CIO.lift(Ref.make[Int](v))

    /** Wraps a native `zio.Ref[Int]` as a `CAtomicInt`. Identity on the carrier. */
    inline def lift(inline u: Ref[Int]): CAtomicInt = u

    extension (inline self: CAtomicInt)

        /** Unwraps to the native `zio.Ref[Int]`. Identity on the carrier. */
        inline def lower: Ref[Int] = self

        /** Reads the current value. */
        inline def get(using inline trace: Trace): CIO[Int] =
            CIO.lift(self.get)

        /** Atomically sets the value to `v`. */
        inline def set(inline v: Int)(using inline trace: Trace): CIO[Unit] =
            CIO.lift(self.set(v))

        /** Atomically sets the value to `v` and returns the previous value. */
        inline def getAndSet(inline v: Int)(using inline trace: Trace): CIO[Int] =
            CIO.lift(self.getAndSet(v))

        /** Atomically increments by 1 and returns the new value. */
        inline def incrementAndGet(using inline trace: Trace): CIO[Int] =
            CIO.lift(self.updateAndGet(_ + 1))

        /** Atomically increments by 1 and returns the previous value. */
        inline def getAndIncrement(using inline trace: Trace): CIO[Int] =
            CIO.lift(self.getAndUpdate(_ + 1))

        /** Atomically decrements by 1 and returns the new value. */
        inline def decrementAndGet(using inline trace: Trace): CIO[Int] =
            CIO.lift(self.updateAndGet(_ - 1))

        /** Atomically decrements by 1 and returns the previous value. */
        inline def getAndDecrement(using inline trace: Trace): CIO[Int] =
            CIO.lift(self.getAndUpdate(_ - 1))

        /** Atomically adds `delta` and returns the new value. */
        inline def addAndGet(inline delta: Int)(using inline trace: Trace): CIO[Int] =
            CIO.lift(self.updateAndGet(_ + delta))

        /** Atomically adds `delta` and returns the previous value. */
        inline def getAndAdd(inline delta: Int)(using inline trace: Trace): CIO[Int] =
            CIO.lift(self.getAndUpdate(_ + delta))

        /** Atomic compare-and-set: replaces `expected` with `updated` iff the current value equals `expected`. */
        inline def compareAndSet(inline expected: Int, inline updated: Int)(
            using inline trace: Trace
        ): CIO[Boolean] =
            CIO.lift(self.modify(cur => if cur == expected then (true, updated) else (false, cur)))
    end extension

end CAtomicInt
