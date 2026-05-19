package kyo.compat

import com.twitter.util.Future
import com.twitter.util.Local as TLocal

/** Backed by com.twitter.util.Local[A]. Twitter Local is propagated automatically across async boundaries by the Twitter scheduler — no
  * ContextPropagatingEC wrapper needed. `init` is deferred so each call to `CLocal.init` constructs a fresh local at effect-evaluation
  * time. let restores the previous value on scope exit across continuations.
  */
opaque type CLocal[A] = (TLocal[A], A)

object CLocal:

    inline def init[A](inline default: A): CIO[CLocal[A]] =
        CIO.defer((new TLocal[A](), default))

    inline def lift[A](inline u: (TLocal[A], A)): CLocal[A] = u

    extension [A](inline self: CLocal[A])

        inline def lower: (TLocal[A], A) = self

        private inline def tlocal: TLocal[A] = self._1
        private inline def tdefault: A       = self._2

        inline def get: CIO[A] =
            CIO.defer(tlocal().getOrElse(tdefault))

        inline def let[B](inline value: A)(inline c: CIO[B]): CIO[B] =
            CIO.deferLift {
                tlocal.let(value) { c.lower() }
            }

        inline def update[B](inline f: A => A)(inline c: CIO[B]): CIO[B] =
            CIO.deferLift {
                val cur = tlocal().getOrElse(tdefault)
                tlocal.let(f(cur)) { c.lower() }
            }

    end extension

end CLocal
