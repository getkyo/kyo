package kyo.compat

import kyo.*

/** Backed by kyo.Local[A]. Propagation is automatic across Kyo async boundaries. `init` is deferred so each call to `CLocal.init`
  * constructs a fresh Local at effect-evaluation time.
  */
opaque type CLocal[A] = Local[A]

object CLocal:

    inline def init[A](inline default: A)(using inline frame: Frame): CIO[CLocal[A]] =
        CIO.defer(Local.init[A](default))

    inline def lift[A](inline u: Local[A]): CLocal[A] = u

    extension [A](inline self: CLocal[A])

        inline def lower: Local[A] = self

        inline def get(using inline frame: Frame): CIO[A] =
            CIO.lift(self.get)

        inline def let[B](inline v: A)(inline c: CIO[B])(
            using inline frame: Frame
        ): CIO[B] =
            CIO.lift(self.let(v)(c.lower))

        inline def update[B](inline f: A => A)(inline c: CIO[B])(
            using inline frame: Frame
        ): CIO[B] =
            CIO.lift(self.update(f)(c.lower))
    end extension
end CLocal
