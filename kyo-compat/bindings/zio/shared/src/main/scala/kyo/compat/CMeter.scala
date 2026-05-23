package kyo.compat

import zio.*

/** Underlying carrier is `zio.Semaphore`, a counting semaphore. Operations propagate ZIO `Trace` through `(using inline trace: Trace)` on
  * every entry point. `lift` and `lower` are identity since the carrier is already a native ZIO semaphore. Permit counts are `Long` on the
  * ZIO side; the compat surface converts to/from `Int` on `init` and `availablePermits`.
  */
opaque type CMeter = Semaphore

object CMeter:

    /** Allocates a counting semaphore with `permits` permits. */
    inline def init(inline permits: Int)(using inline trace: Trace): CIO[CMeter] =
        CIO.lift(Semaphore.make(permits.toLong))

    /** Wraps a native `zio.Semaphore` as a `CMeter`. Identity on the carrier. */
    inline def lift(inline u: Semaphore): CMeter = u

    extension (inline self: CMeter)

        /** Unwraps to the native `zio.Semaphore`. Identity on the carrier. */
        inline def lower: Semaphore = self

        /** Acquires one permit, runs `c`, and releases on completion (success or failure). */
        inline def run[A](inline c: CIO[A])(
            using inline trace: Trace
        ): CIO[A] =
            CIO.lift(self.withPermit(c.lower))

        /** Attempts to acquire a permit without blocking; runs `c` if successful, otherwise returns `None`. */
        inline def tryRun[A](inline c: CIO[A])(
            using inline trace: Trace
        ): CIO[Option[A]] =
            CIO.lift(self.tryWithPermits(1L)(c.lower))

        /** Current count of available permits. */
        inline def availablePermits(using inline trace: Trace): CIO[Int] =
            CIO.lift(self.available.map(_.toInt))
    end extension

end CMeter
