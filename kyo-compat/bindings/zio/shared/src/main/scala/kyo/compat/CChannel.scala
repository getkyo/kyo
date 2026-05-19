package kyo.compat

import zio.*

/** A bounded async channel backed by `zio.Queue.bounded[A]`.
  *
  * `close`/`shutdown` are not exposed; the channel lives until the owning fiber terminates.
  */
opaque type CChannel[A] = Queue[A]

object CChannel:

    inline def init[A](inline capacity: Int)(using inline trace: Trace): CIO[CChannel[A]] =
        CIO.lift(Queue.bounded[A](capacity))

    inline def lift[A](inline u: Queue[A]): CChannel[A] = u

    extension [A](inline self: CChannel[A])

        inline def lower: Queue[A] = self

        inline def put(inline v: A)(using inline trace: Trace): CIO[Unit] =
            CIO.lift(self.offer(v).unit)

        inline def take(using inline trace: Trace): CIO[A] =
            CIO.lift(self.take)

        inline def poll(using inline trace: Trace): CIO[Option[A]] =
            CIO.lift(self.poll)
    end extension

end CChannel
