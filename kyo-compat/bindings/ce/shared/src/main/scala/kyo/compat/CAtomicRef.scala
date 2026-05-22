package kyo.compat

import cats.effect.IO
import cats.effect.kernel.Ref

/** Backed by `cats.effect.kernel.Ref[IO, A]`. `compareAndSet` is composed via `Ref.modify` since CE has no native CAS.
  */
opaque type CAtomicRef[A] = Ref[IO, A]

object CAtomicRef:

    inline def init[A](inline a: A): CIO[CAtomicRef[A]] =
        CIO.lift(Ref.of[IO, A](a))

    inline def lift[A](inline u: Ref[IO, A]): CAtomicRef[A] = u

    extension [A](inline self: CAtomicRef[A])

        inline def lower: Ref[IO, A] = self

        inline def get: CIO[A]                            = CIO.lift(self.get)
        inline def set(inline a: A): CIO[Unit]            = CIO.lift(self.set(a))
        inline def getAndSet(inline a: A): CIO[A]         = CIO.lift(self.getAndSet(a))
        inline def updateAndGet(inline f: A => A): CIO[A] = CIO.lift(self.updateAndGet(f))
        inline def getAndUpdate(inline f: A => A): CIO[A] = CIO.lift(self.getAndUpdate(f))

        inline def compareAndSet(inline expected: A, inline updated: A): CIO[Boolean] =
            CIO.lift(self.modify(cur =>
                given CanEqual[A, A] = CanEqual.derived
                if cur == expected then (updated, true) else (cur, false)
            ))

    end extension

end CAtomicRef
