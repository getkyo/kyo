package kyo.compat

import kyo.*

/** Underlying carrier is `kyo.Promise[A, Abort[Throwable]]`, a single-shot completable cell. `lift` and `lower` are identity since the
  * carrier is already a kyo-native promise. `succeed` and `fail` return `true` on first completion and `false` on subsequent attempts
  * (first-wins). `poll` maps kyo's `Result` (success / failure / panic) into `Option[scala.util.Try[A]]`.
  */
opaque type CPromise[A] = Promise[A, Abort[Throwable]]

object CPromise:

    /** Allocates a fresh single-shot promise. */
    inline def init[A](using inline frame: Frame): CIO[CPromise[A]] =
        CIO.lift(Promise.init[A, Abort[Throwable]])

    /** Wraps a native `kyo.Promise` as a `CPromise`. Identity on the carrier. */
    inline def lift[A](inline u: Promise[A, Abort[Throwable]]): CPromise[A] = u

    extension [A](inline self: CPromise[A])

        /** Unwraps to the native `kyo.Promise`. Identity on the carrier. */
        inline def lower: Promise[A, Abort[Throwable]] = self

        /** Attempts to complete the promise with `a`; returns `true` if this is the first completion. */
        inline def succeed(inline a: A)(using inline frame: Frame): CIO[Boolean] =
            CIO.lift(Promise.complete(self.lower)(Result.succeed(a)))

        /** Attempts to complete the promise with failure `e`; returns `true` if this is the first completion. */
        inline def fail(inline e: Throwable)(using inline frame: Frame): CIO[Boolean] =
            CIO.lift(Promise.complete(self.lower)(Result.fail(e)))

        /** Suspends until the promise is completed and returns its value. */
        inline def get(using inline frame: Frame): CIO[A] =
            CIO.lift(Fiber.mask(self.lower).map(masked => Fiber.get(masked)))

        /** Returns the current state without blocking: `None` if pending, `Some(Try)` if completed. */
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

        /** `true` if the promise has been completed. */
        inline def done(using inline frame: Frame): CIO[Boolean] =
            CIO.lift(Fiber.done(self.lower))

    end extension

end CPromise
