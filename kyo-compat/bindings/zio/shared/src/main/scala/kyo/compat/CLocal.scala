package kyo.compat

import zio.*

/** Backed by `zio.FiberRef[A]`. `init` allocates a fresh `FiberRef` via `Scope.global.extend(FiberRef.make[A](default))` inside `CIO`,
  * deferring construction to effect-evaluation time. Extension methods operate directly on the `FiberRef` — no wrapper or lazy-CAS dance
  * required.
  */
opaque type CLocal[A] = FiberRef[A]

object CLocal:

    inline def init[A](inline default: A): CIO[CLocal[A]] =
        CIO.lift(Scope.global.extend(FiberRef.make[A](default)))

    inline def lift[A](inline u: FiberRef[A]): CLocal[A] = u

    extension [A](inline self: CLocal[A])

        inline def lower: FiberRef[A] = self

        inline def get(using inline trace: Trace): CIO[A] =
            CIO.lift(self.get)

        inline def let[B](inline v: A)(inline c: CIO[B])(
            using inline trace: Trace
        ): CIO[B] =
            CIO.lift(self.locally(v)((c.lower: ZIO[Any, Throwable, B])))

        inline def update[B](inline f: A => A)(inline c: CIO[B])(
            using inline trace: Trace
        ): CIO[B] =
            CIO.lift(self.locallyWith(f)((c.lower: ZIO[Any, Throwable, B])))
    end extension
end CLocal
