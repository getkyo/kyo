package kyo.compat

import kyo.*

/** Underlying carrier is `kyo.Latch`, a one-shot count-down latch. `lift` and `lower` are identity since the carrier is already a
  * kyo-native latch. `init(n)` with `n <= 0` is normalized to "already released" and `await` returns immediately.
  */
opaque type CLatch = Latch

object CLatch:

    /** Allocates a latch with counter `n`; `n <= 0` is normalized to "already released". */
    inline def init(inline n: Int)(using inline frame: Frame): CIO[CLatch] =
        CIO.lift(Latch.init(n))

    /** Wraps a native `kyo.Latch` as a `CLatch`. Identity on the carrier. */
    inline def lift(inline u: Latch): CLatch = u

    extension (inline self: CLatch)

        /** Unwraps to the native `kyo.Latch`. Identity on the carrier. */
        inline def lower: Latch = self

        /** Suspends until the counter reaches zero. */
        inline def await(using inline frame: Frame): CIO[Unit] = CIO.lift(self.await)

        /** Decrements the counter by one; unblocks all `await`s when it reaches zero. */
        inline def release(using inline frame: Frame): CIO[Unit] = CIO.lift(self.release)

    end extension

end CLatch
