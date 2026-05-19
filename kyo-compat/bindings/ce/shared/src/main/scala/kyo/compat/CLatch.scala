package kyo.compat

import cats.effect.IO
import cats.effect.std.CountDownLatch

/** Backed by `cats.effect.std.CountDownLatch[IO]`. `init(n <= 0)` produces an already-released latch (`CountDownLatch(1)` pre-released)
  * since CE requires n >= 1.
  */
opaque type CLatch = CountDownLatch[IO]

object CLatch:

    inline def init(inline n: Int): CIO[CLatch] =
        CIO.lift(initImpl(n))

    inline def lift(inline u: CountDownLatch[IO]): CLatch = u

    private inline def initImpl(inline n: Int): IO[CountDownLatch[IO]] =
        if n <= 0 then CountDownLatch[IO](1).flatMap(l => l.release.as(l))
        else CountDownLatch[IO](n)

    extension (inline self: CLatch)

        inline def lower: CountDownLatch[IO] = self

        inline def await: CIO[Unit]   = CIO.lift(self.await)
        inline def release: CIO[Unit] = CIO.lift(self.release)

    end extension

end CLatch
