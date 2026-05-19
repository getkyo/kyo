package kyo.compat

import zio.*

/** Backed by `zio.Ref[Int]`. ZIO has no specialised AtomicInt; arithmetic operations compose via `Ref[Int]` updates. */
opaque type CAtomicInt = Ref[Int]

object CAtomicInt:

    inline def init(inline v: Int)(using inline trace: Trace): CIO[CAtomicInt] =
        CIO.lift(Ref.make[Int](v))

    inline def lift(inline u: Ref[Int]): CAtomicInt = u

    extension (inline self: CAtomicInt)

        inline def lower: Ref[Int] = self

        inline def get(using inline trace: Trace): CIO[Int] =
            CIO.lift(self.get)

        inline def set(inline v: Int)(using inline trace: Trace): CIO[Unit] =
            CIO.lift(self.set(v))

        inline def getAndSet(inline v: Int)(using inline trace: Trace): CIO[Int] =
            CIO.lift(self.getAndSet(v))

        inline def incrementAndGet(using inline trace: Trace): CIO[Int] =
            CIO.lift(self.updateAndGet(_ + 1))

        inline def getAndIncrement(using inline trace: Trace): CIO[Int] =
            CIO.lift(self.getAndUpdate(_ + 1))

        inline def decrementAndGet(using inline trace: Trace): CIO[Int] =
            CIO.lift(self.updateAndGet(_ - 1))

        inline def getAndDecrement(using inline trace: Trace): CIO[Int] =
            CIO.lift(self.getAndUpdate(_ - 1))

        inline def addAndGet(inline delta: Int)(using inline trace: Trace): CIO[Int] =
            CIO.lift(self.updateAndGet(_ + delta))

        inline def getAndAdd(inline delta: Int)(using inline trace: Trace): CIO[Int] =
            CIO.lift(self.getAndUpdate(_ + delta))

        inline def compareAndSet(inline expected: Int, inline updated: Int)(
            using inline trace: Trace
        ): CIO[Boolean] =
            CIO.lift(self.modify(cur => if cur == expected then (true, updated) else (false, cur)))
    end extension

end CAtomicInt
