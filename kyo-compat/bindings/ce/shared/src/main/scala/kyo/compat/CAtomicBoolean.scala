package kyo.compat

import cats.effect.IO
import cats.effect.kernel.Ref

/** Backed by `cats.effect.kernel.Ref[IO, Boolean]`. */
opaque type CAtomicBoolean = Ref[IO, Boolean]

object CAtomicBoolean:

    inline def init(inline v: Boolean): CIO[CAtomicBoolean] =
        CIO.lift(Ref.of[IO, Boolean](v))

    inline def lift(inline u: Ref[IO, Boolean]): CAtomicBoolean = u

    extension (inline self: CAtomicBoolean)

        inline def lower: Ref[IO, Boolean] = self

        inline def get: CIO[Boolean]                          = CIO.lift(self.get)
        inline def set(inline v: Boolean): CIO[Unit]          = CIO.lift(self.set(v))
        inline def getAndSet(inline v: Boolean): CIO[Boolean] = CIO.lift(self.getAndSet(v))

        inline def compareAndSet(inline expected: Boolean, inline updated: Boolean): CIO[Boolean] =
            CIO.lift(self.modify(cur => if cur == expected then (updated, true) else (cur, false)))

    end extension

end CAtomicBoolean
