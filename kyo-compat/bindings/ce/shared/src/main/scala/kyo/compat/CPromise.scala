package kyo.compat

import cats.effect.Deferred
import cats.effect.IO
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** Backed by `cats.effect.Deferred[IO, Try[A]]`. `Deferred` is a single-assignment cell of a plain value — it has no error channel
  * (`complete` takes an `A`, `get` returns an `A`). A `CPromise` must carry failures, so the cell stores a `Try[A]` and failure is encoded
  * as `Failure(t)`. `poll` returns the stored `Try` directly.
  */
opaque type CPromise[A] = Deferred[IO, Try[A]]

object CPromise:

    inline def init[A]: CIO[CPromise[A]] =
        CIO.lift(Deferred[IO, Try[A]])

    inline def lift[A](inline u: Deferred[IO, Try[A]]): CPromise[A] = u

    extension [A](inline self: CPromise[A])

        inline def lower: Deferred[IO, Try[A]] = self

        inline def succeed(inline a: A): CIO[Boolean] =
            CIO.lift(self.complete(Success(a)))

        inline def fail(inline e: Throwable): CIO[Boolean] =
            CIO.lift(self.complete(Failure(e)))

        inline def get: CIO[A] =
            CIO.lift(self.get.flatMap {
                case Success(a) => IO.pure(a)
                case Failure(e) => IO.raiseError(e)
            })

        inline def poll: CIO[Option[Try[A]]] =
            CIO.lift(self.tryGet)

        inline def done: CIO[Boolean] =
            CIO.lift(self.tryGet.map(_.isDefined))

    end extension

end CPromise
