package kyo.compat

import zio.*

/** Backed by `zio.Promise[Throwable, A]`.
  *
  * E is invariant in `zio.Promise[Throwable, A]`; the surface declares `+E` for uniformity across backends. The covariant view is sound
  * because CPromise never widens E observably (opacity hides the invariance).
  *
  * `poll` performs a non-trivial adaptation: ZIO's `Promise.poll` returns `UIO[Option[IO[E, A]]]`. The inner `IO[E, A]` is flattened to
  * `Try[A]` via `.foldZIO` before wrapping in `Some`, so the returned value is `Option[Try[A]]` as the surface promises.
  */
opaque type CPromise[A] = Promise[Throwable, A]

object CPromise:

    inline def init[A](using inline trace: Trace): CIO[CPromise[A]] =
        CIO.lift(Promise.make[Throwable, A])

    inline def lift[A](inline u: Promise[Throwable, A]): CPromise[A] = u

    extension [A](inline self: CPromise[A])

        inline def lower: Promise[Throwable, A] = self

        inline def succeed(inline a: A)(using inline trace: Trace): CIO[Boolean] =
            CIO.lift(self.succeed(a))

        inline def fail(inline e: Throwable)(using inline trace: Trace): CIO[Boolean] =
            CIO.lift(self.fail(e))

        inline def get(using inline trace: Trace): CIO[A] =
            CIO.lift(self.await)

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

        inline def done(using inline trace: Trace): CIO[Boolean] =
            CIO.lift(self.isDone)

    end extension

end CPromise
