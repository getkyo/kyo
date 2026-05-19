package kyo.compat

import cats.effect.IO
import cats.effect.kernel.Ref

/** Backed by `cats.effect.kernel.Ref[IO, Int]`. */
opaque type CAtomicInt = Ref[IO, Int]

object CAtomicInt:

    inline def init(inline v: Int): CIO[CAtomicInt] =
        CIO.lift(Ref.of[IO, Int](v))

    inline def lift(inline u: Ref[IO, Int]): CAtomicInt = u

    extension (inline self: CAtomicInt)

        inline def lower: Ref[IO, Int] = self

        inline def get: CIO[Int]                          = CIO.lift(self.get)
        inline def set(inline v: Int): CIO[Unit]          = CIO.lift(self.set(v))
        inline def getAndSet(inline v: Int): CIO[Int]     = CIO.lift(self.getAndSet(v))
        inline def incrementAndGet: CIO[Int]              = CIO.lift(self.updateAndGet(_ + 1))
        inline def getAndIncrement: CIO[Int]              = CIO.lift(self.getAndUpdate(_ + 1))
        inline def decrementAndGet: CIO[Int]              = CIO.lift(self.updateAndGet(_ - 1))
        inline def getAndDecrement: CIO[Int]              = CIO.lift(self.getAndUpdate(_ - 1))
        inline def addAndGet(inline delta: Int): CIO[Int] = CIO.lift(self.updateAndGet(_ + delta))
        inline def getAndAdd(inline delta: Int): CIO[Int] = CIO.lift(self.getAndUpdate(_ + delta))

        inline def compareAndSet(inline expected: Int, inline updated: Int): CIO[Boolean] =
            CIO.lift(self.modify(cur => if cur == expected then (updated, true) else (cur, false)))

    end extension

end CAtomicInt
