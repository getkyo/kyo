package kyo.compat

import cats.effect.IO
import cats.effect.kernel.Ref

/** Backed by `cats.effect.kernel.Ref[IO, Long]`. */
opaque type CAtomicLong = Ref[IO, Long]

object CAtomicLong:

    inline def init(inline v: Long): CIO[CAtomicLong] =
        CIO.lift(Ref.of[IO, Long](v))

    inline def lift(inline u: Ref[IO, Long]): CAtomicLong = u

    extension (inline self: CAtomicLong)

        inline def lower: Ref[IO, Long] = self

        inline def get: CIO[Long]                           = CIO.lift(self.get)
        inline def set(inline v: Long): CIO[Unit]           = CIO.lift(self.set(v))
        inline def getAndSet(inline v: Long): CIO[Long]     = CIO.lift(self.getAndSet(v))
        inline def incrementAndGet: CIO[Long]               = CIO.lift(self.updateAndGet(_ + 1L))
        inline def getAndIncrement: CIO[Long]               = CIO.lift(self.getAndUpdate(_ + 1L))
        inline def decrementAndGet: CIO[Long]               = CIO.lift(self.updateAndGet(_ - 1L))
        inline def getAndDecrement: CIO[Long]               = CIO.lift(self.getAndUpdate(_ - 1L))
        inline def addAndGet(inline delta: Long): CIO[Long] = CIO.lift(self.updateAndGet(_ + delta))
        inline def getAndAdd(inline delta: Long): CIO[Long] = CIO.lift(self.getAndUpdate(_ + delta))

        inline def compareAndSet(inline expected: Long, inline updated: Long): CIO[Boolean] =
            CIO.lift(self.modify(cur => if cur == expected then (updated, true) else (cur, false)))

    end extension

end CAtomicLong
