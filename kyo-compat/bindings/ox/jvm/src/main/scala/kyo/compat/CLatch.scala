package kyo.compat

import java.util.concurrent.CountDownLatch

/** Underlying carrier is `java.util.concurrent.CountDownLatch`, a one-shot count-down latch. `lift` and `lower` are identity since the
  * carrier is already a native `CountDownLatch`. `init(n)` with `n <= 0` is normalized to "already released" and `await` returns
  * immediately. `await` blocks the calling thread (the native Ox idiom).
  */
opaque type CLatch = CountDownLatch

object CLatch:

    /** Allocates a latch with counter `n`; `n <= 0` is normalized to "already released". */
    inline def init(inline n: Int): CIO[CLatch] =
        CIO.defer(new CountDownLatch(if n <= 0 then 0 else n))

    /** Wraps a native `CountDownLatch` as a `CLatch`. Identity on the carrier. */
    inline def lift(inline u: CountDownLatch): CLatch = u

    extension (inline self: CLatch)

        /** Unwraps to the native `CountDownLatch`. Identity on the carrier. */
        inline def lower: CountDownLatch = self

        /** Suspends until the counter reaches zero (blocks the calling thread). */
        inline def await: CIO[Unit] = CIO.defer(self.await())

        /** Decrements the counter by one; unblocks all `await`s when it reaches zero. */
        inline def release: CIO[Unit] = CIO.defer(self.countDown())

    end extension

end CLatch
