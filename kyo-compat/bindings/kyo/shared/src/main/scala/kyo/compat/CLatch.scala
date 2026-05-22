package kyo.compat

import kyo.*

/** Backed by kyo.Latch. */
opaque type CLatch = Latch

object CLatch:

    inline def init(inline n: Int)(using inline frame: Frame): CIO[CLatch] =
        CIO.lift(Latch.init(n))

    inline def lift(inline u: Latch): CLatch = u

    extension (inline self: CLatch)

        inline def lower: Latch = self

        inline def await(using inline frame: Frame): CIO[Unit]   = CIO.lift(self.await)
        inline def release(using inline frame: Frame): CIO[Unit] = CIO.lift(self.release)

    end extension

end CLatch
