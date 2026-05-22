package kyo.compat

import kyo.*

/** Backed by kyo.AtomicBoolean. */
opaque type CAtomicBoolean = AtomicBoolean

object CAtomicBoolean:

    inline def init(inline v: Boolean)(using inline frame: Frame): CIO[CAtomicBoolean] =
        CIO.lift(AtomicBoolean.init(v))

    inline def lift(inline u: AtomicBoolean): CAtomicBoolean = u

    extension (inline self: CAtomicBoolean)

        inline def lower: AtomicBoolean = self

        inline def get(using inline frame: Frame): CIO[Boolean]                          = CIO.lift(self.get)
        inline def set(inline v: Boolean)(using inline frame: Frame): CIO[Unit]          = CIO.lift(self.set(v))
        inline def getAndSet(inline v: Boolean)(using inline frame: Frame): CIO[Boolean] = CIO.lift(self.getAndSet(v))

        inline def compareAndSet(inline expected: Boolean, inline updated: Boolean)(
            using inline frame: Frame
        ): CIO[Boolean] =
            CIO.lift(self.compareAndSet(expected, updated))

    end extension

end CAtomicBoolean
