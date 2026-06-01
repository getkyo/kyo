package kyo.compat

import cats.effect.IO
import cats.effect.kernel.Ref

/** Underlying carrier is `cats.effect.kernel.Ref[IO, Long]`. cats-effect has no `Frame` / `Trace` to propagate. `lift` and `lower` are
  * identity since the carrier is already a native CE ref. CE has no specialised atomic-long; arithmetic operations compose via `Ref[Long]`
  * updates, and `compareAndSet` is composed via `Ref.modify` since CE has no native CAS primitive.
  */
opaque type CAtomicLong = Ref[IO, Long]

object CAtomicLong:

    /** Allocates a fresh atomic long initialized to `v`. */
    inline def init(inline v: Long): CIO[CAtomicLong] =
        CIO.lift(Ref.of[IO, Long](v))

    /** Wraps a native `cats.effect.kernel.Ref[IO, Long]` as a `CAtomicLong`. Identity on the carrier. */
    inline def lift(inline u: Ref[IO, Long]): CAtomicLong = u

    extension (inline self: CAtomicLong)

        /** Unwraps to the native `cats.effect.kernel.Ref[IO, Long]`. Identity on the carrier. */
        inline def lower: Ref[IO, Long] = self

        /** Reads the current value. */
        inline def get: CIO[Long] = CIO.lift(self.get)

        /** Atomically sets the value to `v`. */
        inline def set(inline v: Long): CIO[Unit] = CIO.lift(self.set(v))

        /** Atomically sets the value to `v` and returns the previous value. */
        inline def getAndSet(inline v: Long): CIO[Long] = CIO.lift(self.getAndSet(v))

        /** Atomically increments by 1 and returns the new value. */
        inline def incrementAndGet: CIO[Long] = CIO.lift(self.updateAndGet(_ + 1L))

        /** Atomically increments by 1 and returns the previous value. */
        inline def getAndIncrement: CIO[Long] = CIO.lift(self.getAndUpdate(_ + 1L))

        /** Atomically decrements by 1 and returns the new value. */
        inline def decrementAndGet: CIO[Long] = CIO.lift(self.updateAndGet(_ - 1L))

        /** Atomically decrements by 1 and returns the previous value. */
        inline def getAndDecrement: CIO[Long] = CIO.lift(self.getAndUpdate(_ - 1L))

        /** Atomically adds `delta` and returns the new value. */
        inline def addAndGet(inline delta: Long): CIO[Long] = CIO.lift(self.updateAndGet(_ + delta))

        /** Atomically adds `delta` and returns the previous value. */
        inline def getAndAdd(inline delta: Long): CIO[Long] = CIO.lift(self.getAndUpdate(_ + delta))

        /** Atomic compare-and-set: replaces `expected` with `updated` iff the current value equals `expected`. */
        inline def compareAndSet(inline expected: Long, inline updated: Long): CIO[Boolean] =
            CIO.lift(self.modify(cur => if cur == expected then (updated, true) else (cur, false)))

    end extension

end CAtomicLong
