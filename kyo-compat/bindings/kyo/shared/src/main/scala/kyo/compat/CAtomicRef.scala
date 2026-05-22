package kyo.compat

import kyo.*

/** Backed by kyo.AtomicRef[A]. */
opaque type CAtomicRef[A] = AtomicRef[A]

object CAtomicRef:

    inline def init[A](inline a: A)(using inline frame: Frame): CIO[CAtomicRef[A]] =
        CIO.lift(AtomicRef.init[A](a))

    inline def lift[A](inline u: AtomicRef[A]): CAtomicRef[A] = u

    extension [A](inline self: CAtomicRef[A])

        inline def lower: AtomicRef[A] = self

        inline def get(using inline frame: Frame): CIO[A]                    = CIO.lift(self.lower.get)
        inline def set(inline a: A)(using inline frame: Frame): CIO[Unit]    = CIO.lift(self.lower.set(a))
        inline def getAndSet(inline a: A)(using inline frame: Frame): CIO[A] = CIO.lift(self.lower.getAndSet(a))

        inline def updateAndGet(inline f: A => A)(using inline frame: Frame): CIO[A] =
            CIO.lift(self.lower.updateAndGet(f))

        inline def getAndUpdate(inline f: A => A)(using inline frame: Frame): CIO[A] =
            CIO.lift(self.lower.getAndUpdate(f))

        inline def compareAndSet(inline expected: A, inline updated: A)(
            using inline frame: Frame
        ): CIO[Boolean] =
            CIO.lift(self.lower.compareAndSet(expected, updated))

    end extension

end CAtomicRef
