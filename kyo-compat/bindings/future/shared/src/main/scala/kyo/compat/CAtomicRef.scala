package kyo.compat

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future

/** Underlying carrier is `java.util.concurrent.atomic.AtomicReference[A]`. The Future ecosystem has no `Frame` / `Trace` to propagate.
  * `lift` and `lower` are identity since the carrier is already a native JDK atomic.
  */
opaque type CAtomicRef[A] = AtomicReference[A]

object CAtomicRef:

    /** Allocates a fresh atomic reference initialized to `a`. */
    inline def init[A](inline a: A): CIO[CAtomicRef[A]] =
        CIO.defer(new AtomicReference[A](a))

    /** Wraps a native `java.util.concurrent.atomic.AtomicReference` as a `CAtomicRef`. Identity on the carrier. */
    inline def lift[A](inline u: AtomicReference[A]): CAtomicRef[A] = u

    extension [A](inline self: CAtomicRef[A])

        /** Unwraps to the native `java.util.concurrent.atomic.AtomicReference`. Identity on the carrier. */
        inline def lower: AtomicReference[A] = self

        /** Reads the current value. */
        inline def get: CIO[A] = CIO.defer(self.get())

        /** Atomically sets the value to `a`. */
        inline def set(inline a: A): CIO[Unit] = CIO.defer(self.set(a))

        /** Atomically sets the value to `a` and returns the previous value. */
        inline def getAndSet(inline a: A): CIO[A] = CIO.defer(self.getAndSet(a))

        /** Atomically applies `f` and returns the new value. */
        inline def updateAndGet(inline f: A => A): CIO[A] = CIO.defer(self.updateAndGet(a => f(a)))

        /** Atomically applies `f` and returns the previous value. */
        inline def getAndUpdate(inline f: A => A): CIO[A] = CIO.defer(self.getAndUpdate(a => f(a)))

        /** Atomic compare-and-set: replaces `expected` with `updated` iff the current value equals `expected`. */
        inline def compareAndSet(inline expected: A, inline updated: A): CIO[Boolean] =
            CIO.defer(self.compareAndSet(expected, updated))

    end extension

end CAtomicRef
