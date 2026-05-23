package kyo.compat

import zio.*

/** Underlying carrier is `zio.Ref[Boolean]`. Operations propagate ZIO `Trace` through `(using inline trace: Trace)` on every entry point.
  * `lift` and `lower` are identity since the carrier is already a native ZIO ref. `compareAndSet` is composed via `Ref.modify` since
  * `zio.Ref` exposes no native CAS primitive.
  */
opaque type CAtomicBoolean = Ref[Boolean]

object CAtomicBoolean:

    /** Allocates a fresh atomic boolean initialized to `v`. */
    inline def init(inline v: Boolean)(using inline trace: Trace): CIO[CAtomicBoolean] =
        CIO.lift(Ref.make[Boolean](v))

    /** Wraps a native `zio.Ref[Boolean]` as a `CAtomicBoolean`. Identity on the carrier. */
    inline def lift(inline u: Ref[Boolean]): CAtomicBoolean = u

    extension (inline self: CAtomicBoolean)

        /** Unwraps to the native `zio.Ref[Boolean]`. Identity on the carrier. */
        inline def lower: Ref[Boolean] = self

        /** Reads the current value. */
        inline def get(using inline trace: Trace): CIO[Boolean] =
            CIO.lift(self.get)

        /** Atomically sets the value to `v`. */
        inline def set(inline v: Boolean)(using inline trace: Trace): CIO[Unit] =
            CIO.lift(self.set(v))

        /** Atomically sets the value to `v` and returns the previous value. */
        inline def getAndSet(inline v: Boolean)(using inline trace: Trace): CIO[Boolean] =
            CIO.lift(self.getAndSet(v))

        /** Atomic compare-and-set: replaces `expected` with `updated` iff the current value equals `expected`. */
        inline def compareAndSet(inline expected: Boolean, inline updated: Boolean)(
            using inline trace: Trace
        ): CIO[Boolean] =
            CIO.lift(self.modify(cur => if cur == expected then (true, updated) else (false, cur)))
    end extension

end CAtomicBoolean
