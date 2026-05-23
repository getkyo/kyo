package kyo.compat

import kyo.compat.internal.LocalCtx
import scala.concurrent.Future
import scala.util.Try

/** Underlying carrier is `scala.concurrent.Future[A]`. The Future ecosystem has no `Frame` / `Trace` to propagate. `lift` and `lower` are
  * identity since the carrier is already a native `Future`. `init` evaluates the body eagerly under the current `LocalCtx` and returns the
  * resulting `Future` as the fiber handle. `onComplete` registers a callback on the underlying `Future` and fires when it completes
  * naturally (success or failure); cancellation isn't part of the surface — Future has no interrupt.
  */
opaque type CFiber[+A] = Future[A]

object CFiber:

    /** Forks `c` as a new fiber and returns a handle backed by the running `Future`. */
    inline def init[A](inline c: CIO[A]): CIO[CFiber[A]] =
        CIO.deferLift(Future.successful(c.lower))

    /** Wraps a native `scala.concurrent.Future` as a `CFiber`. Identity on the carrier. */
    inline def lift[A](inline u: Future[A]): CFiber[A] = u

    extension [A](inline self: CFiber[A])

        /** Unwraps to the native `scala.concurrent.Future`. Identity on the carrier. */
        inline def lower: Future[A] = self

        /** Joins the fiber and returns its result. */
        inline def get: CIO[A] = CIO.lift(self.lower)

        /** Registers `cb` to fire when the underlying `Future` completes naturally; success and failure are reified as `scala.util.Try`. */
        inline def onComplete(inline cb: Try[A] => CIO[Unit]): CIO[Unit] =
            CIO.defer {
                self.lower.onComplete { t =>
                    val _ = cb(t).unsafeRun
                    ()
                }(scala.concurrent.ExecutionContext.parasitic)
            }
    end extension

end CFiber
