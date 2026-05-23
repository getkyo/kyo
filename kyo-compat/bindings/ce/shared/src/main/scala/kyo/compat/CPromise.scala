package kyo.compat

import cats.effect.Deferred
import cats.effect.IO
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** Underlying carrier is `cats.effect.Deferred[IO, Try[A]]`. cats-effect has no `Frame` / `Trace` to propagate. `Deferred` is a
  * single-assignment cell of a plain value — it has no error channel (`complete` takes an `A`, `get` returns an `A`). A `CPromise` must
  * carry failures, so the cell stores a `Try[A]` and failure is encoded as `Failure(t)`. `succeed` and `fail` return `true` on first
  * completion and `false` on subsequent attempts (first-wins). `poll` returns the stored `Try` directly.
  */
opaque type CPromise[A] = Deferred[IO, Try[A]]

object CPromise:

    /** Allocates a fresh single-shot promise. */
    inline def init[A]: CIO[CPromise[A]] =
        CIO.lift(Deferred[IO, Try[A]])

    /** Wraps a native `cats.effect.Deferred` as a `CPromise`. Identity on the carrier. */
    inline def lift[A](inline u: Deferred[IO, Try[A]]): CPromise[A] = u

    extension [A](inline self: CPromise[A])

        /** Unwraps to the native `cats.effect.Deferred`. Identity on the carrier. */
        inline def lower: Deferred[IO, Try[A]] = self

        /** Attempts to complete the promise with `a`; returns `true` if this is the first completion. */
        inline def succeed(inline a: A): CIO[Boolean] =
            CIO.lift(self.complete(Success(a)))

        /** Attempts to complete the promise with failure `e`; returns `true` if this is the first completion. */
        inline def fail(inline e: Throwable): CIO[Boolean] =
            CIO.lift(self.complete(Failure(e)))

        /** Suspends until the promise is completed and returns its value. */
        inline def get: CIO[A] =
            CIO.lift(self.get.flatMap {
                case Success(a) => IO.pure(a)
                case Failure(e) => IO.raiseError(e)
            })

        /** Returns the current state without blocking: `None` if pending, `Some(Try)` if completed. */
        inline def poll: CIO[Option[Try[A]]] =
            CIO.lift(self.tryGet)

        /** `true` if the promise has been completed. */
        inline def done: CIO[Boolean] =
            CIO.lift(self.tryGet.map(_.isDefined))

    end extension

end CPromise
