package kyo.compat

import kyo.*

/** Underlying carrier is `kyo.Meter`, a counting semaphore. `lift` and `lower` are identity since the carrier is already a kyo-native
  * meter. `run` wraps the body in `Abort.run` so the permit is released even when the body fails. `Closed` is a `Throwable`, so `Meter`'s
  * `Abort[Closed]` widens into the CIO carrier's `Abort[Throwable]` channel with no explicit handling.
  */
opaque type CMeter = Meter

object CMeter:

    /** Allocates a counting semaphore with `permits` permits. */
    inline def init(inline permits: Int)(using inline frame: Frame): CIO[CMeter] =
        CIO.lift(Meter.initSemaphoreUnscoped(permits))

    /** Wraps a native `kyo.Meter` as a `CMeter`. Identity on the carrier. */
    inline def lift(inline u: Meter): CMeter = u

    extension (inline self: CMeter)

        /** Unwraps to the native `kyo.Meter`. Identity on the carrier. */
        inline def lower: Meter = self

        /** Acquires one permit, runs `c`, and releases on completion (success or failure). */
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

        /** Attempts to acquire a permit without blocking; runs `c` if successful, otherwise returns `None`. */
        inline def tryRun[A](inline c: CIO[A])(
            using inline frame: Frame
        ): CIO[Option[A]] =
            CIO.lift(self.tryRun(c.lower).map(_.toOption))

        /** Current count of available permits. */
        inline def availablePermits(using inline frame: Frame): CIO[Int] =
            CIO.lift(self.availablePermits)
    end extension

end CMeter
