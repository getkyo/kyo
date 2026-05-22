package kyo.compat

import java.util.concurrent.CountDownLatch

/** Backed by `java.util.concurrent.CountDownLatch`. `init(n <= 0)` produces an already-released latch. */
opaque type CLatch = CountDownLatch

object CLatch:

    inline def init(inline n: Int): CIO[CLatch] =
        CIO.defer(new CountDownLatch(if n <= 0 then 0 else n))

    inline def lift(inline u: CountDownLatch): CLatch = u

    extension (inline self: CLatch)

        inline def lower: CountDownLatch = self

        inline def await: CIO[Unit]   = CIO.defer(self.await())
        inline def release: CIO[Unit] = CIO.defer(self.countDown())

    end extension

end CLatch
