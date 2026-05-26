package kyo.compat

import ox.ForkLocal
import ox.Ox

/** Underlying carrier is `ox.ForkLocal[A]` (JDK `ScopedValue` under the hood). `lift` and `lower` are identity since the carrier is already
  * a native `ForkLocal`. `let(v)(c)` opens a fresh `supervisedWhere` scope around the body, so any forks inside the let-body bind to that
  * inner scope -- the local value is inherited by all forks within the scope. `init` is deferred so each call constructs a fresh local at
  * effect-evaluation time.
  */
opaque type CLocal[A] = ForkLocal[A]

object CLocal:

    /** Allocates a fresh fiber-local seeded with `default`. */
    inline def init[A](inline default: A): CIO[CLocal[A]] =
        CIO.defer(ForkLocal(default))

    /** Wraps a native `ox.ForkLocal` as a `CLocal`. Identity on the carrier. */
    inline def lift[A](inline u: ForkLocal[A]): CLocal[A] = u

    extension [A](inline self: CLocal[A])

        /** Unwraps to the native `ox.ForkLocal`. Identity on the carrier. */
        inline def lower: ForkLocal[A] = self

        /** Reads the current fiber's value. */
        inline def get: CIO[A] =
            CIO.defer(self.get())

        /** Installs `v` for the duration of `c` inside a fresh `supervisedWhere` scope, then reverts. */
        inline def let[B](inline v: A)(inline c: CIO[B]): CIO[B] =
            CIO.deferLift {
                self.supervisedWhere(v)(c.lower)
            }

        /** Reads the current value, applies `f`, installs the result for the duration of `c` inside a fresh `supervisedWhere` scope, then
          * reverts.
          */
        inline def update[B](inline f: A => A)(inline c: CIO[B]): CIO[B] =
            CIO.deferLift {
                self.supervisedWhere(f(self.get()))(c.lower)
            }
    end extension
end CLocal
