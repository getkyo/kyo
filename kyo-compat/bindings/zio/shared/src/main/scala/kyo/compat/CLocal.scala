package kyo.compat

import zio.*

/** Underlying carrier is `zio.FiberRef[A]`, a fiber-scoped value. Operations propagate ZIO `Trace` through `(using inline trace: Trace)` on
  * every entry point. `lift` and `lower` are identity since the carrier is already a native ZIO fiber ref. `init` allocates a fresh
  * `FiberRef` via `Scope.global.extend(FiberRef.make[A](default))` so the ref is bound to the global scope and construction is deferred to
  * effect-evaluation time.
  */
opaque type CLocal[A] = FiberRef[A]

object CLocal:

    /** Allocates a fresh fiber-local seeded with `default`. */
    inline def init[A](inline default: A): CIO[CLocal[A]] =
        CIO.lift(Scope.global.extend(FiberRef.make[A](default)))

    /** Wraps a native `zio.FiberRef` as a `CLocal`. Identity on the carrier. */
    inline def lift[A](inline u: FiberRef[A]): CLocal[A] = u

    extension [A](inline self: CLocal[A])

        /** Unwraps to the native `zio.FiberRef`. Identity on the carrier. */
        inline def lower: FiberRef[A] = self

        /** Reads the current fiber's value. */
        inline def get(using inline trace: Trace): CIO[A] =
            CIO.lift(self.get)

        /** Installs `v` for the duration of `c`, then reverts. */
        inline def let[B](inline v: A)(inline c: CIO[B])(
            using inline trace: Trace
        ): CIO[B] =
            CIO.lift(self.locally(v)((c.lower: ZIO[Any, Throwable, B])))

        /** Reads the current value, applies `f`, installs the result for the duration of `c`, then reverts. */
        inline def update[B](inline f: A => A)(inline c: CIO[B])(
            using inline trace: Trace
        ): CIO[B] =
            CIO.lift(self.locallyWith(f)((c.lower: ZIO[Any, Throwable, B])))
    end extension
end CLocal
