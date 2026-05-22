package kyo.compat

import java.util.concurrent.LinkedBlockingQueue

/** Backed by `java.util.concurrent.LinkedBlockingQueue`. Blocking is the native Ox idiom. `poll` is non-blocking. */
opaque type CChannel[A] = LinkedBlockingQueue[A]

object CChannel:

    inline def init[A](inline capacity: Int): CIO[CChannel[A]] =
        CIO.defer(new LinkedBlockingQueue[A](capacity))

    inline def lift[A](inline u: LinkedBlockingQueue[A]): CChannel[A] = u

    extension [A](inline self: CChannel[A])

        inline def lower: LinkedBlockingQueue[A] = self

        inline def put(inline v: A): CIO[Unit] = CIO.defer(self.put(v))
        inline def take: CIO[A]                = CIO.defer(self.take())
        inline def poll: CIO[Option[A]]        = CIO.defer(Option(self.poll()))

    end extension

end CChannel
