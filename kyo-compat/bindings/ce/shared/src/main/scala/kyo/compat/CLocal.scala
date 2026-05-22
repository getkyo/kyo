package kyo.compat

import cats.effect.IO
import cats.effect.IOLocal

/** Backed by `cats.effect.IOLocal[A]`. `init` allocates a fresh `IOLocal` inside `IO`, so the allocation is deferred to effect-evaluation
  * time. Extension methods operate directly on the `IOLocal` — no wrapper or lazy-CAS dance required.
  */
opaque type CLocal[A] = IOLocal[A]

object CLocal:

    inline def init[A](inline default: A): CIO[CLocal[A]] =
        CIO.lift(IOLocal[A](default))

    inline def lift[A](inline u: IOLocal[A]): CLocal[A] = u

    extension [A](inline self: CLocal[A])

        inline def lower: IOLocal[A] = self

        inline def get: CIO[A] = CIO.lift(self.get)

        inline def let[B](inline v: A)(inline c: CIO[B]): CIO[B] =
            CIO.lift(self.getAndSet(v).bracket(_ => c.lower)(prior => self.set(prior)))

        inline def update[B](inline f: A => A)(inline c: CIO[B]): CIO[B] =
            CIO.lift(self.get.flatMap { cur =>
                self.set(f(cur)).bracket(_ => c.lower)(_ => self.set(cur))
            })

    end extension
end CLocal
