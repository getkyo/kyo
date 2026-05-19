package kyo.compat

import ox.ForkLocal
import ox.Ox

/** Backed by `ox.ForkLocal[A]` (JDK `ScopedValue` under the hood). `let(v)(c)` opens a fresh `scopedWhere` scope around the body, so any
  * forks inside the let-body bind to that inner scope -- the local value is inherited by all forks within the scope. `init` is deferred so
  * each call to `CLocal.init` constructs a fresh local at effect-evaluation time.
  */
opaque type CLocal[A] = ForkLocal[A]

object CLocal:

    inline def init[A](inline default: A): CIO[CLocal[A]] =
        CIO.defer(ForkLocal(default))

    inline def lift[A](inline u: ForkLocal[A]): CLocal[A] = u

    extension [A](inline self: CLocal[A])

        inline def lower: ForkLocal[A] = self

        inline def get: CIO[A] =
            CIO.defer(self.get())

        inline def let[B](inline v: A)(inline c: CIO[B]): CIO[B] =
            CIO.deferLift {
                self.supervisedWhere(v)(CIO.unsafeRun(c))
            }

        inline def update[B](inline f: A => A)(inline c: CIO[B]): CIO[B] =
            CIO.deferLift {
                self.supervisedWhere(f(self.get()))(CIO.unsafeRun(c))
            }
    end extension
end CLocal
