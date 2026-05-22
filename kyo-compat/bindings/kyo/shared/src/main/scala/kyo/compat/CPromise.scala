package kyo.compat

import kyo.*

/** Backed by kyo.Promise[A, Abort[Throwable]]. poll maps Result to Option[Try[A]]. */
opaque type CPromise[A] = Promise[A, Abort[Throwable]]

object CPromise:

    inline def init[A](using inline frame: Frame): CIO[CPromise[A]] =
        CIO.lift(Promise.init[A, Abort[Throwable]])

    inline def lift[A](inline u: Promise[A, Abort[Throwable]]): CPromise[A] = u

    extension [A](inline self: CPromise[A])

        inline def lower: Promise[A, Abort[Throwable]] = self

        inline def succeed(inline a: A)(using inline frame: Frame): CIO[Boolean] =
            CIO.lift(Promise.complete(self.lower)(Result.succeed(a)))

        inline def fail(inline e: Throwable)(using inline frame: Frame): CIO[Boolean] =
            CIO.lift(Promise.complete(self.lower)(Result.fail(e)))

        inline def get(using inline frame: Frame): CIO[A] =
            CIO.lift(Fiber.mask(self.lower).map(masked => Fiber.get(masked)))

        inline def poll(using inline frame: Frame): CIO[Option[scala.util.Try[A]]] =
            CIO.lift(
                Fiber.poll(self.lower).map {
                    case Absent => Option.empty
                    case Present(s: Result.Success[A < Any] @unchecked) =>
                        s.successValue.map(a => Option(scala.util.Success(a)))
                    case Present(f: Result.Failure[Throwable] @unchecked) =>
                        (Option(scala.util.Failure(f.failure)))
                    case Present(p: Result.Panic) =>
                        (Option(scala.util.Failure(p.exception)))
                }
            )

        inline def done(using inline frame: Frame): CIO[Boolean] =
            CIO.lift(Fiber.done(self.lower))

    end extension

end CPromise
