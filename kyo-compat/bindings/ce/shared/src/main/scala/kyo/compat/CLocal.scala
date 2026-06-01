package kyo.compat

import cats.effect.IO
import cats.effect.IOLocal

/** Underlying carrier is `cats.effect.IOLocal[A]`, a fiber-scoped value. cats-effect has no `Frame` / `Trace` to propagate. `lift` and
  * `lower` are identity since the carrier is already a native CE local. `init` allocates a fresh `IOLocal` inside `IO`, so the allocation
  * is deferred to effect-evaluation time. `let` and `update` use `IO.bracket` to install the new value and revert the prior one on every
  * exit path.
  */
opaque type CLocal[A] = IOLocal[A]

object CLocal:

    /** Allocates a fresh fiber-local seeded with `default`. */
    inline def init[A](inline default: A): CIO[CLocal[A]] =
        CIO.lift(IOLocal[A](default))

    /** Wraps a native `cats.effect.IOLocal` as a `CLocal`. Identity on the carrier. */
    inline def lift[A](inline u: IOLocal[A]): CLocal[A] = u

    extension [A](inline self: CLocal[A])

        /** Unwraps to the native `cats.effect.IOLocal`. Identity on the carrier. */
        inline def lower: IOLocal[A] = self

        /** Reads the current fiber's value. */
        inline def get: CIO[A] = CIO.lift(self.get)

        /** Installs `v` for the duration of `c`, then reverts. */
        inline def let[B](inline v: A)(inline c: CIO[B]): CIO[B] =
            CIO.lift(self.getAndSet(v).bracket(_ => c.lower)(prior => self.set(prior)))

        /** Reads the current value, applies `f`, installs the result for the duration of `c`, then reverts. */
        inline def update[B](inline f: A => A)(inline c: CIO[B]): CIO[B] =
            CIO.lift(self.get.flatMap { cur =>
                self.set(f(cur)).bracket(_ => c.lower)(_ => self.set(cur))
            })

    end extension
end CLocal
