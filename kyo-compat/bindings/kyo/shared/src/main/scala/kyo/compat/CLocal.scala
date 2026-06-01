package kyo.compat

import kyo.*

/** Underlying carrier is `kyo.Local[A]`, a fiber-scoped value. `lift` and `lower` are identity since the carrier is already a kyo-native
  * local. Propagation across kyo async boundaries is automatic. `init` is deferred so each call constructs a fresh `Local` at
  * effect-evaluation time.
  */
opaque type CLocal[A] = Local[A]

object CLocal:

    /** Allocates a fresh fiber-local seeded with `default`. */
    inline def init[A](inline default: A)(using inline frame: Frame): CIO[CLocal[A]] =
        CIO.defer(Local.init[A](default))

    /** Wraps a native `kyo.Local` as a `CLocal`. Identity on the carrier. */
    inline def lift[A](inline u: Local[A]): CLocal[A] = u

    extension [A](inline self: CLocal[A])

        /** Unwraps to the native `kyo.Local`. Identity on the carrier. */
        inline def lower: Local[A] = self

        /** Reads the current fiber's value. */
        inline def get(using inline frame: Frame): CIO[A] =
            CIO.lift(self.get)

        /** Installs `v` for the duration of `c`, then reverts. */
        inline def let[B](inline v: A)(inline c: CIO[B])(
            using inline frame: Frame
        ): CIO[B] =
            CIO.lift(self.let(v)(c.lower))

        /** Reads the current value, applies `f`, installs the result for the duration of `c`, then reverts. */
        inline def update[B](inline f: A => A)(inline c: CIO[B])(
            using inline frame: Frame
        ): CIO[B] =
            CIO.lift(self.update(f)(c.lower))
    end extension
end CLocal
