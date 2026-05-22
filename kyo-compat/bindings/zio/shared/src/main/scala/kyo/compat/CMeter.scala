package kyo.compat

import zio.*

/** Backed by `zio.Semaphore`. Permit counts are `Long` on the ZIO side; the surface converts to/from `Int`. */
opaque type CMeter = Semaphore

object CMeter:

    inline def init(inline permits: Int)(using inline trace: Trace): CIO[CMeter] =
        CIO.lift(Semaphore.make(permits.toLong))

    inline def lift(inline u: Semaphore): CMeter = u

    extension (inline self: CMeter)

        inline def lower: Semaphore = self

        inline def run[A](inline c: CIO[A])(
            using inline trace: Trace
        ): CIO[A] =
            CIO.lift(self.withPermit(c.lower))

        inline def tryRun[A](inline c: CIO[A])(
            using inline trace: Trace
        ): CIO[Option[A]] =
            CIO.lift(self.tryWithPermits(1L)(c.lower))

        inline def availablePermits(using inline trace: Trace): CIO[Int] =
            CIO.lift(self.available.map(_.toInt))
    end extension

end CMeter
