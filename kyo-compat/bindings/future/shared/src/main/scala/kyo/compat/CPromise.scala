package kyo.compat

import scala.concurrent.Promise
import scala.util.Try

/** Backed by `scala.concurrent.Promise[A]`. */
opaque type CPromise[A] = Promise[A]

object CPromise:

    inline def init[A]: CIO[CPromise[A]] =
        CIO.defer(Promise[A]())

    inline def lift[A](inline u: Promise[A]): CPromise[A] = u

    extension [A](inline self: CPromise[A])

        inline def lower: Promise[A] = self

        inline def succeed(inline a: A): CIO[Boolean]      = CIO.defer(self.trySuccess(a))
        inline def fail(inline e: Throwable): CIO[Boolean] = CIO.defer(self.tryFailure(e))
        inline def get: CIO[A]                             = CIO.lift(self.future)
        inline def poll: CIO[Option[Try[A]]]               = CIO.defer(self.future.value)
        inline def done: CIO[Boolean]                      = CIO.defer(self.future.isCompleted)

    end extension

end CPromise
