package kyo.compat

import kyo.*

/** Backed by kyo.AtomicLong. */
opaque type CAtomicLong = AtomicLong

object CAtomicLong:

    inline def init(inline v: Long)(using inline frame: Frame): CIO[CAtomicLong] =
        CIO.lift(AtomicLong.init(v))

    inline def lift(inline u: AtomicLong): CAtomicLong = u

    extension (inline self: CAtomicLong)

        inline def lower: AtomicLong = self

        inline def get(using inline frame: Frame): CIO[Long]                           = CIO.lift(self.get)
        inline def set(inline v: Long)(using inline frame: Frame): CIO[Unit]           = CIO.lift(self.set(v))
        inline def getAndSet(inline v: Long)(using inline frame: Frame): CIO[Long]     = CIO.lift(self.getAndSet(v))
        inline def incrementAndGet(using inline frame: Frame): CIO[Long]               = CIO.lift(self.incrementAndGet)
        inline def getAndIncrement(using inline frame: Frame): CIO[Long]               = CIO.lift(self.getAndIncrement)
        inline def decrementAndGet(using inline frame: Frame): CIO[Long]               = CIO.lift(self.decrementAndGet)
        inline def getAndDecrement(using inline frame: Frame): CIO[Long]               = CIO.lift(self.getAndDecrement)
        inline def addAndGet(inline delta: Long)(using inline frame: Frame): CIO[Long] = CIO.lift(self.addAndGet(delta))
        inline def getAndAdd(inline delta: Long)(using inline frame: Frame): CIO[Long] = CIO.lift(self.getAndAdd(delta))

        inline def compareAndSet(inline expected: Long, inline updated: Long)(
            using inline frame: Frame
        ): CIO[Boolean] =
            CIO.lift(self.compareAndSet(expected, updated))

    end extension

end CAtomicLong
