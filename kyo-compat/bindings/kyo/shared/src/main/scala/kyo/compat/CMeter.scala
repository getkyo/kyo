package kyo.compat

import kyo.*

/** Backed by kyo.Meter (semaphore). `run` wraps the body in `Abort.run` so the permit is released even when the body fails. `Closed` is a
  * `Throwable`, so `Meter`'s `Abort[Closed]` widens into the CIO carrier's `Abort[Throwable]` channel with no explicit handling.
  */
opaque type CMeter = Meter

object CMeter:

    inline def init(inline permits: Int)(using inline frame: Frame): CIO[CMeter] =
        CIO.lift(Meter.initSemaphoreUnscoped(permits))

    inline def lift(inline u: Meter): CMeter = u

    extension (inline self: CMeter)

        inline def lower: Meter = self

        inline def run[A](inline c: CIO[A])(
            using inline frame: Frame
        ): CIO[A] =
            CIO.lift(
                self.run(Abort.run[Throwable](c.lower)).map {
                    case Result.Success(a) => a
                    case Result.Failure(t) => Abort.fail(t)
                    case Result.Panic(t)   => Abort.panic(t)
                }
            )

        inline def tryRun[A](inline c: CIO[A])(
            using inline frame: Frame
        ): CIO[Option[A]] =
            CIO.lift(self.tryRun(c.lower).map(_.toOption))

        inline def availablePermits(using inline frame: Frame): CIO[Int] =
            CIO.lift(self.availablePermits)
    end extension

end CMeter
