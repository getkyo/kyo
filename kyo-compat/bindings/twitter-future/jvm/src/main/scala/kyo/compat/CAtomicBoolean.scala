package kyo.compat

import java.util.concurrent.atomic.AtomicBoolean

/** Backed by java.util.concurrent.atomic.AtomicBoolean. */
opaque type CAtomicBoolean = AtomicBoolean

object CAtomicBoolean:

    inline def init(inline v: Boolean): CIO[CAtomicBoolean] =
        CIO.defer(new AtomicBoolean(v))

    inline def lift(inline u: AtomicBoolean): CAtomicBoolean = u

    extension (inline self: CAtomicBoolean)

        inline def lower: AtomicBoolean = self

        inline def get: CIO[Boolean] =
            CIO.defer(self.get())

        inline def set(inline v: Boolean): CIO[Unit] =
            CIO.defer(self.set(v))

        inline def getAndSet(inline v: Boolean): CIO[Boolean] =
            CIO.defer(self.getAndSet(v))

        inline def compareAndSet(inline expected: Boolean, inline updated: Boolean): CIO[Boolean] =
            CIO.defer(self.compareAndSet(expected, updated))

    end extension

end CAtomicBoolean
