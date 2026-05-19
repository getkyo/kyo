package kyo.compat

import java.util.concurrent.atomic.AtomicInteger

/** Backed by java.util.concurrent.atomic.AtomicInteger. */
opaque type CAtomicInt = AtomicInteger

object CAtomicInt:

    inline def init(inline v: Int): CIO[CAtomicInt] =
        CIO.defer(new AtomicInteger(v))

    inline def lift(inline u: AtomicInteger): CAtomicInt = u

    extension (inline self: CAtomicInt)

        inline def lower: AtomicInteger = self

        inline def get: CIO[Int] =
            CIO.defer(self.get())

        inline def set(inline v: Int): CIO[Unit] =
            CIO.defer(self.set(v))

        inline def getAndSet(inline v: Int): CIO[Int] =
            CIO.defer(self.getAndSet(v))

        inline def incrementAndGet: CIO[Int] =
            CIO.defer(self.incrementAndGet())

        inline def getAndIncrement: CIO[Int] =
            CIO.defer(self.getAndIncrement())

        inline def decrementAndGet: CIO[Int] =
            CIO.defer(self.decrementAndGet())

        inline def getAndDecrement: CIO[Int] =
            CIO.defer(self.getAndDecrement())

        inline def addAndGet(inline delta: Int): CIO[Int] =
            CIO.defer(self.addAndGet(delta))

        inline def getAndAdd(inline delta: Int): CIO[Int] =
            CIO.defer(self.getAndAdd(delta))

        inline def compareAndSet(inline expected: Int, inline updated: Int): CIO[Boolean] =
            CIO.defer(self.compareAndSet(expected, updated))

    end extension

end CAtomicInt
