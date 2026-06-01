package kyo.compat

import com.twitter.util.Future
import com.twitter.util.Local as TLocal

/** Underlying carrier is a `(com.twitter.util.Local[A], A)` pair — the Twitter Local plus its configured default value. Twitter Local is
  * propagated automatically across async boundaries by the Twitter scheduler, so no `ContextPropagatingEC` wrapper is needed. There is no
  * `Frame` / `Trace` to propagate. `lift` wraps an existing `(Local, default)` pair and `lower` exposes it. `init` is deferred so each call
  * constructs a fresh `Local` at effect-evaluation time; `let` restores the previous value on scope exit across continuations.
  */
opaque type CLocal[A] = (TLocal[A], A)

object CLocal:

    /** Allocates a fresh fiber-local seeded with `default`. */
    inline def init[A](inline default: A): CIO[CLocal[A]] =
        CIO.defer((new TLocal[A](), default))

    /** Wraps an existing `(com.twitter.util.Local[A], A)` pair as a `CLocal`. Identity on the carrier. */
    inline def lift[A](inline u: (TLocal[A], A)): CLocal[A] = u

    extension [A](inline self: CLocal[A])

        /** Unwraps to the native `(com.twitter.util.Local[A], A)` pair. Identity on the carrier. */
        inline def lower: (TLocal[A], A) = self

        private inline def tlocal: TLocal[A] = self._1
        private inline def tdefault: A       = self._2

        /** Reads the current fiber's value, falling back to the configured default. */
        inline def get: CIO[A] =
            CIO.defer(tlocal().getOrElse(tdefault))

        /** Installs `value` for the duration of `c`, then reverts. */
        inline def let[B](inline value: A)(inline c: CIO[B]): CIO[B] =
            CIO.deferLift {
                tlocal.let(value) { c.lower() }
            }

        /** Reads the current value, applies `f`, installs the result for the duration of `c`, then reverts. */
        inline def update[B](inline f: A => A)(inline c: CIO[B]): CIO[B] =
            CIO.deferLift {
                val cur = tlocal().getOrElse(tdefault)
                tlocal.let(f(cur)) { c.lower() }
            }

    end extension

end CLocal
