package kyo.compat

import cats.effect.IO
import cats.effect.kernel.Ref

/** Underlying carrier is `cats.effect.kernel.Ref[IO, Boolean]`. cats-effect has no `Frame` / `Trace` to propagate. `lift` and `lower` are
  * identity since the carrier is already a native CE ref. `compareAndSet` is composed via `Ref.modify` since CE has no native CAS
  * primitive.
  */
opaque type CAtomicBoolean = Ref[IO, Boolean]

object CAtomicBoolean:

    /** Allocates a fresh atomic boolean initialized to `v`. */
    inline def init(inline v: Boolean): CIO[CAtomicBoolean] =
        CIO.lift(Ref.of[IO, Boolean](v))

    /** Wraps a native `cats.effect.kernel.Ref[IO, Boolean]` as a `CAtomicBoolean`. Identity on the carrier. */
    inline def lift(inline u: Ref[IO, Boolean]): CAtomicBoolean = u

    extension (inline self: CAtomicBoolean)

        /** Unwraps to the native `cats.effect.kernel.Ref[IO, Boolean]`. Identity on the carrier. */
        inline def lower: Ref[IO, Boolean] = self

        /** Reads the current value. */
        inline def get: CIO[Boolean] = CIO.lift(self.get)

        /** Atomically sets the value to `v`. */
        inline def set(inline v: Boolean): CIO[Unit] = CIO.lift(self.set(v))

        /** Atomically sets the value to `v` and returns the previous value. */
        inline def getAndSet(inline v: Boolean): CIO[Boolean] = CIO.lift(self.getAndSet(v))

        /** Atomic compare-and-set: replaces `expected` with `updated` iff the current value equals `expected`. */
        inline def compareAndSet(inline expected: Boolean, inline updated: Boolean): CIO[Boolean] =
            CIO.lift(self.modify(cur => if cur == expected then (updated, true) else (cur, false)))

    end extension

end CAtomicBoolean
