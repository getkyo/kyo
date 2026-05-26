package kyo.compat

import zio.*
import zio.concurrent.CountdownLatch

/** Underlying carrier is `zio.concurrent.CountdownLatch`, a one-shot count-down latch. Operations propagate ZIO `Trace` through
  * `(using inline trace: Trace)` on every entry point. `lift` and `lower` are identity since the carrier is already a native ZIO latch.
  * `init(n)` with `n <= 0` is normalized to "already released" by allocating `make(1)` and pre-counting down so `await` returns
  * immediately.
  */
opaque type CLatch = CountdownLatch

object CLatch:

    /** Allocates a latch with counter `n`; `n <= 0` is normalized to "already released". */
    inline def init(inline n: Int)(using inline trace: Trace): CIO[CLatch] =
        CIO.lift(initImpl(n))

    /** Wraps a native `zio.concurrent.CountdownLatch` as a `CLatch`. Identity on the carrier. */
    inline def lift(inline u: CountdownLatch): CLatch = u

    private inline def initImpl(inline n: Int)(using inline trace: Trace): UIO[CountdownLatch] =
        if n <= 0 then CountdownLatch.make(1).flatMap(l => l.countDown.as(l))
        else CountdownLatch.make(n)

    extension (inline self: CLatch)

        /** Unwraps to the native `zio.concurrent.CountdownLatch`. Identity on the carrier. */
        inline def lower: CountdownLatch = self

        /** Suspends until the counter reaches zero. */
        inline def await(using inline trace: Trace): CIO[Unit] =
            CIO.lift(self.await: UIO[Unit])

        /** Decrements the counter by one; unblocks all `await`s when it reaches zero. */
        inline def release(using inline trace: Trace): CIO[Unit] =
            CIO.lift(self.countDown: UIO[Unit])
    end extension

end CLatch
