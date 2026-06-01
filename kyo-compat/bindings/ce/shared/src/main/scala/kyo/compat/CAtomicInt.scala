package kyo.compat

import cats.effect.IO
import cats.effect.kernel.Ref

/** Underlying carrier is `cats.effect.kernel.Ref[IO, Int]`. cats-effect has no `Frame` / `Trace` to propagate. `lift` and `lower` are
  * identity since the carrier is already a native CE ref. CE has no specialised atomic-int; arithmetic operations compose via `Ref[Int]`
  * updates, and `compareAndSet` is composed via `Ref.modify` since CE has no native CAS primitive.
  */
opaque type CAtomicInt = Ref[IO, Int]

object CAtomicInt:

    /** Allocates a fresh atomic int initialized to `v`. */
    inline def init(inline v: Int): CIO[CAtomicInt] =
        CIO.lift(Ref.of[IO, Int](v))

    /** Wraps a native `cats.effect.kernel.Ref[IO, Int]` as a `CAtomicInt`. Identity on the carrier. */
    inline def lift(inline u: Ref[IO, Int]): CAtomicInt = u

    extension (inline self: CAtomicInt)

        /** Unwraps to the native `cats.effect.kernel.Ref[IO, Int]`. Identity on the carrier. */
        inline def lower: Ref[IO, Int] = self

        /** Reads the current value. */
        inline def get: CIO[Int] = CIO.lift(self.get)

        /** Atomically sets the value to `v`. */
        inline def set(inline v: Int): CIO[Unit] = CIO.lift(self.set(v))

        /** Atomically sets the value to `v` and returns the previous value. */
        inline def getAndSet(inline v: Int): CIO[Int] = CIO.lift(self.getAndSet(v))

        /** Atomically increments by 1 and returns the new value. */
        inline def incrementAndGet: CIO[Int] = CIO.lift(self.updateAndGet(_ + 1))

        /** Atomically increments by 1 and returns the previous value. */
        inline def getAndIncrement: CIO[Int] = CIO.lift(self.getAndUpdate(_ + 1))

        /** Atomically decrements by 1 and returns the new value. */
        inline def decrementAndGet: CIO[Int] = CIO.lift(self.updateAndGet(_ - 1))

        /** Atomically decrements by 1 and returns the previous value. */
        inline def getAndDecrement: CIO[Int] = CIO.lift(self.getAndUpdate(_ - 1))

        /** Atomically adds `delta` and returns the new value. */
        inline def addAndGet(inline delta: Int): CIO[Int] = CIO.lift(self.updateAndGet(_ + delta))

        /** Atomically adds `delta` and returns the previous value. */
        inline def getAndAdd(inline delta: Int): CIO[Int] = CIO.lift(self.getAndUpdate(_ + delta))

        /** Atomic compare-and-set: replaces `expected` with `updated` iff the current value equals `expected`. */
        inline def compareAndSet(inline expected: Int, inline updated: Int): CIO[Boolean] =
            CIO.lift(self.modify(cur => if cur == expected then (updated, true) else (cur, false)))

    end extension

end CAtomicInt
