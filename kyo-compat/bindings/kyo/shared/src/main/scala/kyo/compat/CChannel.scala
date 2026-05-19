package kyo.compat

import kyo.*

/** Backed by kyo.Channel[A]. put/take/poll discharge Abort[Closed] as panic (surface never closes the channel). poll returns Option[A].
  * `kyo.Channel` rounds capacity up to the next power of two on the JVM; pass exact powers when the precise bound matters.
  */
opaque type CChannel[A] = Channel[A]

object CChannel:

    inline def init[A](inline capacity: Int)(using inline frame: Frame): CIO[CChannel[A]] =
        CIO.lift(Channel.initUnscoped[A](capacity))

    inline def lift[A](inline u: Channel[A]): CChannel[A] = u

    extension [A](inline self: CChannel[A])

        inline def lower: Channel[A] = self

        inline def put(inline v: A)(using inline frame: Frame): CIO[Unit] = CIO.lift(Channel.put(self.lower)(v))
        inline def take(using inline frame: Frame): CIO[A]                = CIO.lift(Channel.take(self.lower))
        inline def poll(using inline frame: Frame): CIO[Option[A]]        = CIO.lift(Channel.poll(self.lower).map(_.toOption))

    end extension

end CChannel
