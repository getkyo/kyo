package kyo.compat

import kyo.*

/** Backed by kyo.AtomicInt. */
opaque type CAtomicInt = AtomicInt

object CAtomicInt:

    inline def init(inline v: Int)(using inline frame: Frame): CIO[CAtomicInt] =
        CIO.lift(AtomicInt.init(v))

    inline def lift(inline u: AtomicInt): CAtomicInt = u

    extension (inline self: CAtomicInt)

        inline def lower: AtomicInt = self

        inline def get(using inline frame: Frame): CIO[Int]                          = CIO.lift(self.get)
        inline def set(inline v: Int)(using inline frame: Frame): CIO[Unit]          = CIO.lift(self.set(v))
        inline def getAndSet(inline v: Int)(using inline frame: Frame): CIO[Int]     = CIO.lift(self.getAndSet(v))
        inline def incrementAndGet(using inline frame: Frame): CIO[Int]              = CIO.lift(self.incrementAndGet)
        inline def getAndIncrement(using inline frame: Frame): CIO[Int]              = CIO.lift(self.getAndIncrement)
        inline def decrementAndGet(using inline frame: Frame): CIO[Int]              = CIO.lift(self.decrementAndGet)
        inline def getAndDecrement(using inline frame: Frame): CIO[Int]              = CIO.lift(self.getAndDecrement)
        inline def addAndGet(inline delta: Int)(using inline frame: Frame): CIO[Int] = CIO.lift(self.addAndGet(delta))
        inline def getAndAdd(inline delta: Int)(using inline frame: Frame): CIO[Int] = CIO.lift(self.getAndAdd(delta))

        inline def compareAndSet(inline expected: Int, inline updated: Int)(
            using inline frame: Frame
        ): CIO[Boolean] =
            CIO.lift(self.compareAndSet(expected, updated))

    end extension

end CAtomicInt
