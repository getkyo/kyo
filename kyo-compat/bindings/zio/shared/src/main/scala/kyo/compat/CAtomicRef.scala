package kyo.compat

import zio.*

/** Backed by `zio.Ref[A]`. `compareAndSet` is composed via `Ref.modify` since ZIO's `Ref` has no native CAS. */
opaque type CAtomicRef[A] = Ref[A]

object CAtomicRef:

    inline def init[A](inline a: A)(using inline trace: Trace): CIO[CAtomicRef[A]] =
        CIO.lift(Ref.make[A](a))

    inline def lift[A](inline u: Ref[A]): CAtomicRef[A] = u

    extension [A](inline self: CAtomicRef[A])

        inline def lower: Ref[A] = self

        inline def get(using inline trace: Trace): CIO[A] =
            CIO.lift(self.get)

        inline def set(inline a: A)(using inline trace: Trace): CIO[Unit] =
            CIO.lift(self.set(a))

        inline def getAndSet(inline a: A)(using inline trace: Trace): CIO[A] =
            CIO.lift(self.getAndSet(a))

        inline def updateAndGet(inline f: A => A)(using inline trace: Trace): CIO[A] =
            CIO.lift(self.updateAndGet(f))

        inline def getAndUpdate(inline f: A => A)(using inline trace: Trace): CIO[A] =
            CIO.lift(self.getAndUpdate(f))

        inline def compareAndSet(inline expected: A, inline updated: A)(
            using inline trace: Trace
        ): CIO[Boolean] =
            CIO.lift(self.modify(cur =>
                if (cur: Any).equals(expected) then (true, updated) else (false, cur)
            ))
    end extension

end CAtomicRef
