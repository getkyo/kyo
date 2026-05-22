package kyo.compat

import zio.*
import zio.concurrent.CountdownLatch

/** Backed by `zio.concurrent.CountdownLatch`. `init(0)` produces an already-released latch (`make(1)` pre-counted). */
opaque type CLatch = CountdownLatch

object CLatch:

    inline def init(inline n: Int)(using inline trace: Trace): CIO[CLatch] =
        CIO.lift(initImpl(n))

    inline def lift(inline u: CountdownLatch): CLatch = u

    private inline def initImpl(inline n: Int)(using inline trace: Trace): UIO[CountdownLatch] =
        if n <= 0 then CountdownLatch.make(1).flatMap(l => l.countDown.as(l))
        else CountdownLatch.make(n)

    extension (inline self: CLatch)

        inline def lower: CountdownLatch = self

        inline def await(using inline trace: Trace): CIO[Unit] =
            CIO.lift(self.await: UIO[Unit])

        inline def release(using inline trace: Trace): CIO[Unit] =
            CIO.lift(self.countDown: UIO[Unit])
    end extension

end CLatch
