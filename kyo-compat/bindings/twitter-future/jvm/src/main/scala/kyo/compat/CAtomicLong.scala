package kyo.compat

import java.util.concurrent.atomic.AtomicLong

/** Backed by java.util.concurrent.atomic.AtomicLong. */
opaque type CAtomicLong = AtomicLong

object CAtomicLong:

    inline def init(inline v: Long): CIO[CAtomicLong] =
        CIO.defer(new AtomicLong(v))

    inline def lift(inline u: AtomicLong): CAtomicLong = u

    extension (inline self: CAtomicLong)

        inline def lower: AtomicLong = self

        inline def get: CIO[Long] =
            CIO.defer(self.get())

        inline def set(inline v: Long): CIO[Unit] =
            CIO.defer(self.set(v))

        inline def getAndSet(inline v: Long): CIO[Long] =
            CIO.defer(self.getAndSet(v))

        inline def incrementAndGet: CIO[Long] =
            CIO.defer(self.incrementAndGet())

        inline def getAndIncrement: CIO[Long] =
            CIO.defer(self.getAndIncrement())

        inline def decrementAndGet: CIO[Long] =
            CIO.defer(self.decrementAndGet())

        inline def getAndDecrement: CIO[Long] =
            CIO.defer(self.getAndDecrement())

        inline def addAndGet(inline delta: Long): CIO[Long] =
            CIO.defer(self.addAndGet(delta))

        inline def getAndAdd(inline delta: Long): CIO[Long] =
            CIO.defer(self.getAndAdd(delta))

        inline def compareAndSet(inline expected: Long, inline updated: Long): CIO[Boolean] =
            CIO.defer(self.compareAndSet(expected, updated))

    end extension

end CAtomicLong
