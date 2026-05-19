package kyo.compat

import cats.effect.IO
import cats.effect.std.Semaphore

/** Backed by `cats.effect.std.Semaphore[IO]`. Permit counts are `Long` on the CE side; the surface converts to/from `Int`. `tryRun` uses
  * `tryPermit` (`Resource[IO, Boolean]`).
  */
opaque type CMeter = Semaphore[IO]

object CMeter:

    inline def init(inline permits: Int): CIO[CMeter] =
        CIO.lift(Semaphore[IO](permits.toLong))

    inline def lift(inline u: Semaphore[IO]): CMeter = u

    extension (inline self: CMeter)

        inline def lower: Semaphore[IO] = self

        inline def run[A](inline c: CIO[A]): CIO[A] =
            CIO.lift(self.permit.use(_ => c.lower))

        inline def tryRun[A](inline c: CIO[A]): CIO[Option[A]] =
            CIO.lift(self.tryPermit.use {
                case true  => c.lower.map(Some(_))
                case false => IO.none[A]
            })

        inline def availablePermits: CIO[Int] =
            CIO.lift(self.available.map(_.toInt))

    end extension

end CMeter
