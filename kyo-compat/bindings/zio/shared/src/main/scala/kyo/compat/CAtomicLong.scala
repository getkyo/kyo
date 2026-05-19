package kyo.compat

import zio.*

/** Backed by `zio.Ref[Long]`. ZIO has no specialised AtomicLong; arithmetic operations compose via `Ref[Long]` updates. */
opaque type CAtomicLong = Ref[Long]

object CAtomicLong:

    inline def init(inline v: Long)(using inline trace: Trace): CIO[CAtomicLong] =
        CIO.lift(Ref.make[Long](v))

    inline def lift(inline u: Ref[Long]): CAtomicLong = u

    extension (inline self: CAtomicLong)

        inline def lower: Ref[Long] = self

        inline def get(using inline trace: Trace): CIO[Long] =
            CIO.lift(self.get)

        inline def set(inline v: Long)(using inline trace: Trace): CIO[Unit] =
            CIO.lift(self.set(v))

        inline def getAndSet(inline v: Long)(using inline trace: Trace): CIO[Long] =
            CIO.lift(self.getAndSet(v))

        inline def incrementAndGet(using inline trace: Trace): CIO[Long] =
            CIO.lift(self.updateAndGet(_ + 1L))

        inline def getAndIncrement(using inline trace: Trace): CIO[Long] =
            CIO.lift(self.getAndUpdate(_ + 1L))

        inline def decrementAndGet(using inline trace: Trace): CIO[Long] =
            CIO.lift(self.updateAndGet(_ - 1L))

        inline def getAndDecrement(using inline trace: Trace): CIO[Long] =
            CIO.lift(self.getAndUpdate(_ - 1L))

        inline def addAndGet(inline delta: Long)(using inline trace: Trace): CIO[Long] =
            CIO.lift(self.updateAndGet(_ + delta))

        inline def getAndAdd(inline delta: Long)(using inline trace: Trace): CIO[Long] =
            CIO.lift(self.getAndUpdate(_ + delta))

        inline def compareAndSet(inline expected: Long, inline updated: Long)(
            using inline trace: Trace
        ): CIO[Boolean] =
            CIO.lift(self.modify(cur => if cur == expected then (true, updated) else (false, cur)))
    end extension

end CAtomicLong
