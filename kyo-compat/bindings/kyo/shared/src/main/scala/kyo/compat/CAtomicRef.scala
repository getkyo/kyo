package kyo.compat

import kyo.*

/** Underlying carrier is `kyo.AtomicRef[A]`, a lock-free atomic reference cell. `lift` and `lower` are identity since the carrier is
  * already a kyo-native atomic. Operations match the `java.util.concurrent.atomic` contract: reads, writes, compare-and-swap, and
  * functional updates all execute without holding a lock.
  */
opaque type CAtomicRef[A] = AtomicRef[A]

object CAtomicRef:

    /** Allocates a fresh atomic reference initialized to `a`. */
    inline def init[A](inline a: A)(using inline frame: Frame): CIO[CAtomicRef[A]] =
        CIO.lift(AtomicRef.init[A](a))

    /** Wraps a native `kyo.AtomicRef` as a `CAtomicRef`. Identity on the carrier. */
    inline def lift[A](inline u: AtomicRef[A]): CAtomicRef[A] = u

    extension [A](inline self: CAtomicRef[A])

        /** Unwraps to the native `kyo.AtomicRef`. Identity on the carrier. */
        inline def lower: AtomicRef[A] = self

        /** Reads the current value. */
        inline def get(using inline frame: Frame): CIO[A] = CIO.lift(self.lower.get)

        /** Atomically sets the value to `a`. */
        inline def set(inline a: A)(using inline frame: Frame): CIO[Unit] = CIO.lift(self.lower.set(a))

        /** Atomically sets the value to `a` and returns the previous value. */
        inline def getAndSet(inline a: A)(using inline frame: Frame): CIO[A] = CIO.lift(self.lower.getAndSet(a))

        /** Atomically applies `f` to the current value and returns the new value. */
        inline def updateAndGet(inline f: A => A)(using inline frame: Frame): CIO[A] =
            CIO.lift(self.lower.updateAndGet(f))

        /** Atomically applies `f` to the current value and returns the previous value. */
        inline def getAndUpdate(inline f: A => A)(using inline frame: Frame): CIO[A] =
            CIO.lift(self.lower.getAndUpdate(f))

        /** Atomic compare-and-set: replaces `expected` with `updated` iff the current value equals `expected`. */
        inline def compareAndSet(inline expected: A, inline updated: A)(
            using inline frame: Frame
        ): CIO[Boolean] =
            CIO.lift(self.lower.compareAndSet(expected, updated))

    end extension

end CAtomicRef
