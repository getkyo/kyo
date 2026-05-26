package kyo.compat

import com.twitter.util.Future
import com.twitter.util.Promise
import com.twitter.util.Return
import com.twitter.util.Throw
import scala.reflect.ClassTag

/** Underlying carrier is `com.twitter.util.Promise[A]`, a single-shot completable cell. There is no `Frame` / `Trace` to propagate. `lift`
  * and `lower` are identity since the carrier is already a native Twitter Promise. `succeed` and `fail` use `updateIfEmpty`, returning
  * `true` on first completion and `false` on subsequent attempts (first-wins). `poll` maps Twitter's `Try` (`Return` / `Throw`) into
  * `Option[scala.util.Try[A]]`.
  */
opaque type CPromise[A] = Promise[A]

object CPromise:

    /** Allocates a fresh single-shot promise. */
    inline def init[A]: CIO[CPromise[A]] =
        CIO.defer(new Promise[A]())

    /** Wraps a native `com.twitter.util.Promise` as a `CPromise`. Identity on the carrier. */
    inline def lift[A](inline u: Promise[A]): CPromise[A] = u

    extension [A](self: CPromise[A])

        /** Unwraps to the native `com.twitter.util.Promise`. Identity on the carrier. */
        inline def lower: Promise[A] = self

        /** Attempts to complete the promise with `a`; returns `true` if this is the first completion. */
        inline def succeed(inline a: A): CIO[Boolean] =
            CIO.defer(self.updateIfEmpty(Return(a)))

        /** Attempts to complete the promise with failure `e`; returns `true` if this is the first completion. */
        inline def fail(inline e: Throwable): CIO[Boolean] =
            CIO.defer(self.updateIfEmpty(Throw(e)))

        /** Suspends until the promise is completed and returns its value. */
        inline def get: CIO[A] =
            CIO.lift(self)

        /** Returns the current state without blocking: `None` if pending, `Some(Try)` if completed. */
        inline def poll: CIO[Option[scala.util.Try[A]]] =
            CIO.defer(self.poll match
                case None            => None
                case Some(Return(a)) => Some(scala.util.Success(a))
                case Some(Throw(t))  => Some(scala.util.Failure(t)))

        /** `true` if the promise has been completed. */
        inline def done: CIO[Boolean] =
            CIO.defer(self.isDefined)

    end extension

end CPromise
