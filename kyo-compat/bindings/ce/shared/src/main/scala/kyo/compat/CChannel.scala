package kyo.compat

import cats.effect.IO
import cats.effect.std.Queue

/** Backed by `cats.effect.std.Queue[IO, A]`. `put` uses `offer` (suspends when full). `poll` uses `tryTake` (non-blocking). No `close` is
  * exposed since CE `Queue` has no shutdown semantic.
  */
opaque type CChannel[A] = Queue[IO, A]

object CChannel:

    inline def init[A](inline capacity: Int): CIO[CChannel[A]] =
        CIO.lift(Queue.bounded[IO, A](capacity))

    inline def lift[A](inline u: Queue[IO, A]): CChannel[A] = u

    extension [A](inline self: CChannel[A])

        inline def lower: Queue[IO, A] = self

        inline def put(inline v: A): CIO[Unit] = CIO.lift(self.offer(v))
        inline def take: CIO[A]                = CIO.lift(self.take)
        inline def poll: CIO[Option[A]]        = CIO.lift(self.tryTake)

    end extension

end CChannel
