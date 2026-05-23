package kyo.compat

import zio.*

/** Underlying carrier is `zio.Promise[Throwable, A]`, a single-shot completable cell. Operations propagate ZIO `Trace` through
  * `(using inline trace: Trace)` on every entry point. `lift` and `lower` are identity since the carrier is already a native ZIO promise.
  * `succeed` and `fail` return `true` on first completion and `false` on subsequent attempts (first-wins). `poll` flattens ZIO's
  * `Option[IO[Throwable, A]]` into `Option[scala.util.Try[A]]`.
  */
opaque type CPromise[A] = Promise[Throwable, A]

object CPromise:

    /** Allocates a fresh single-shot promise. */
    inline def init[A](using inline trace: Trace): CIO[CPromise[A]] =
        CIO.lift(Promise.make[Throwable, A])

    /** Wraps a native `zio.Promise` as a `CPromise`. Identity on the carrier. */
    inline def lift[A](inline u: Promise[Throwable, A]): CPromise[A] = u

    extension [A](inline self: CPromise[A])

        /** Unwraps to the native `zio.Promise`. Identity on the carrier. */
        inline def lower: Promise[Throwable, A] = self

        /** Attempts to complete the promise with `a`; returns `true` if this is the first completion. */
        inline def succeed(inline a: A)(using inline trace: Trace): CIO[Boolean] =
            CIO.lift(self.succeed(a))

        /** Attempts to complete the promise with failure `e`; returns `true` if this is the first completion. */
        inline def fail(inline e: Throwable)(using inline trace: Trace): CIO[Boolean] =
            CIO.lift(self.fail(e))

        /** Suspends until the promise is completed and returns its value. */
        inline def get(using inline trace: Trace): CIO[A] =
            CIO.lift(self.await)

        /** Returns the current state without blocking: `None` if pending, `Some(Try)` if completed. */
        inline def poll(using inline trace: Trace): CIO[Option[scala.util.Try[A]]] =
            CIO.lift(
                self.poll.flatMap {
                    case Some(io) => io.foldZIO(
                            e => ZIO.succeed(Option(scala.util.Failure(e))),
                            a => ZIO.succeed(Option(scala.util.Success(a)))
                        )
                    case None => ZIO.succeed(None: Option[scala.util.Try[A]])
                }
            )

        /** `true` if the promise has been completed. */
        inline def done(using inline trace: Trace): CIO[Boolean] =
            CIO.lift(self.isDone)

    end extension

end CPromise
