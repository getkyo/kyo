package kyo.compat

import cats.effect.IO
import cats.effect.std.CountDownLatch

/** Underlying carrier is `cats.effect.std.CountDownLatch[IO]`, a one-shot count-down latch. cats-effect has no `Frame` / `Trace` to
  * propagate. `lift` and `lower` are identity since the carrier is already a native CE latch. `init(n)` with `n <= 0` is normalized to
  * "already released" by allocating `CountDownLatch[IO](1)` and pre-releasing it, since CE requires `n >= 1`.
  */
opaque type CLatch = CountDownLatch[IO]

object CLatch:

    /** Allocates a latch with counter `n`; `n <= 0` is normalized to "already released". */
    inline def init(inline n: Int): CIO[CLatch] =
        CIO.lift(initImpl(n))

    /** Wraps a native `cats.effect.std.CountDownLatch` as a `CLatch`. Identity on the carrier. */
    inline def lift(inline u: CountDownLatch[IO]): CLatch = u

    private inline def initImpl(inline n: Int): IO[CountDownLatch[IO]] =
        if n <= 0 then CountDownLatch[IO](1).flatMap(l => l.release.as(l))
        else CountDownLatch[IO](n)

    extension (inline self: CLatch)

        /** Unwraps to the native `cats.effect.std.CountDownLatch`. Identity on the carrier. */
        inline def lower: CountDownLatch[IO] = self

        /** Suspends until the counter reaches zero. */
        inline def await: CIO[Unit] = CIO.lift(self.await)

        /** Decrements the counter by one; unblocks all `await`s when it reaches zero. */
        inline def release: CIO[Unit] = CIO.lift(self.release)

    end extension

end CLatch
