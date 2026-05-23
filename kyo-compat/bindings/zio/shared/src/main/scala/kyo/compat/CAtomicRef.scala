package kyo.compat

import zio.*

/** Underlying carrier is `zio.Ref[A]`. Operations propagate ZIO `Trace` through `(using inline trace: Trace)` on every entry point. `lift`
  * and `lower` are identity since the carrier is already a native ZIO ref. `compareAndSet` is composed via `Ref.modify` since `zio.Ref`
  * exposes no native CAS primitive.
  */
opaque type CAtomicRef[A] = Ref[A]

object CAtomicRef:

    /** Allocates a fresh atomic reference initialized to `a`. */
    inline def init[A](inline a: A)(using inline trace: Trace): CIO[CAtomicRef[A]] =
        CIO.lift(Ref.make[A](a))

    /** Wraps a native `zio.Ref` as a `CAtomicRef`. Identity on the carrier. */
    inline def lift[A](inline u: Ref[A]): CAtomicRef[A] = u

    extension [A](inline self: CAtomicRef[A])

        /** Unwraps to the native `zio.Ref`. Identity on the carrier. */
        inline def lower: Ref[A] = self

        /** Reads the current value. */
        inline def get(using inline trace: Trace): CIO[A] =
            CIO.lift(self.get)

        /** Atomically sets the value to `a`. */
        inline def set(inline a: A)(using inline trace: Trace): CIO[Unit] =
            CIO.lift(self.set(a))

        /** Atomically sets the value to `a` and returns the previous value. */
        inline def getAndSet(inline a: A)(using inline trace: Trace): CIO[A] =
            CIO.lift(self.getAndSet(a))

        /** Atomically applies `f` to the current value and returns the new value. */
        inline def updateAndGet(inline f: A => A)(using inline trace: Trace): CIO[A] =
            CIO.lift(self.updateAndGet(f))

        /** Atomically applies `f` to the current value and returns the previous value. */
        inline def getAndUpdate(inline f: A => A)(using inline trace: Trace): CIO[A] =
            CIO.lift(self.getAndUpdate(f))

        /** Atomic compare-and-set: replaces `expected` with `updated` iff the current value equals `expected`. */
        inline def compareAndSet(inline expected: A, inline updated: A)(
            using inline trace: Trace
        ): CIO[Boolean] =
            CIO.lift(self.modify(cur =>
                if (cur: Any).equals(expected) then (true, updated) else (false, cur)
            ))
    end extension

end CAtomicRef
