package kyo.compat

import scala.concurrent.Promise
import scala.util.Try

/** Underlying carrier is `scala.concurrent.Promise[A]`, a one-shot single-assignment cell with a typed `Throwable` failure channel. The
  * Future ecosystem has no `Frame` / `Trace` to propagate. `lift` and `lower` are identity since the carrier is already a native `Promise`.
  * `succeed` and `fail` use `trySuccess`/`tryFailure` so they return `true` on first completion and `false` on subsequent attempts
  * (first-wins).
  */
opaque type CPromise[A] = Promise[A]

object CPromise:

    /** Allocates a fresh single-shot promise. */
    inline def init[A]: CIO[CPromise[A]] =
        CIO.defer(Promise[A]())

    /** Wraps a native `scala.concurrent.Promise` as a `CPromise`. Identity on the carrier. */
    inline def lift[A](inline u: Promise[A]): CPromise[A] = u

    extension [A](inline self: CPromise[A])

        /** Unwraps to the native `scala.concurrent.Promise`. Identity on the carrier. */
        inline def lower: Promise[A] = self

        /** Attempts to complete the promise with `a`; returns `true` if this is the first completion. */
        inline def succeed(inline a: A): CIO[Boolean] = CIO.defer(self.trySuccess(a))

        /** Attempts to complete the promise with failure `e`; returns `true` if this is the first completion. */
        inline def fail(inline e: Throwable): CIO[Boolean] = CIO.defer(self.tryFailure(e))

        /** Suspends until the promise is completed and returns its value. */
        inline def get: CIO[A] = CIO.lift(self.future)

        /** Returns the current state without blocking: `None` if pending, `Some(Try)` if completed. */
        inline def poll: CIO[Option[Try[A]]] = CIO.defer(self.future.value)

        /** `true` if the promise has been completed. */
        inline def done: CIO[Boolean] = CIO.defer(self.future.isCompleted)

    end extension

end CPromise
