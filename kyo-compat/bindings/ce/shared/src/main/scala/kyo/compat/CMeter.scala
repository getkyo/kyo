package kyo.compat

import cats.effect.IO
import cats.effect.std.Semaphore

/** Underlying carrier is `cats.effect.std.Semaphore[IO]`, a counting semaphore. cats-effect has no `Frame` / `Trace` to propagate. `lift`
  * and `lower` are identity since the carrier is already a native CE semaphore. Permit counts are `Long` on the CE side; the compat surface
  * converts to/from `Int` on `init` and `availablePermits`. `tryRun` uses `tryPermit` (`Resource[IO, Boolean]`).
  */
opaque type CMeter = Semaphore[IO]

object CMeter:

    /** Allocates a counting semaphore with `permits` permits. */
    inline def init(inline permits: Int): CIO[CMeter] =
        CIO.lift(Semaphore[IO](permits.toLong))

    /** Wraps a native `cats.effect.std.Semaphore` as a `CMeter`. Identity on the carrier. */
    inline def lift(inline u: Semaphore[IO]): CMeter = u

    extension (inline self: CMeter)

        /** Unwraps to the native `cats.effect.std.Semaphore`. Identity on the carrier. */
        inline def lower: Semaphore[IO] = self

        /** Acquires one permit, runs `c`, and releases on completion (success or failure). */
        inline def run[A](inline c: CIO[A]): CIO[A] =
            CIO.lift(self.permit.use(_ => c.lower))

        /** Attempts to acquire a permit without blocking; runs `c` if successful, otherwise returns `None`. */
        inline def tryRun[A](inline c: CIO[A]): CIO[Option[A]] =
            CIO.lift(self.tryPermit.use {
                case true  => c.lower.map(Some(_))
                case false => IO.none[A]
            })

        /** Current count of available permits. */
        inline def availablePermits: CIO[Int] =
            CIO.lift(self.available.map(_.toInt))

    end extension

end CMeter
