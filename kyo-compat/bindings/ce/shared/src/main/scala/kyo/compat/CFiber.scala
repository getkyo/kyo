package kyo.compat

import cats.effect.FiberIO
import cats.effect.IO
import java.util.concurrent.CancellationException

/** Underlying carrier is `cats.effect.FiberIO[A]`. cats-effect has no `Frame` / `Trace` to propagate. `lift` and `lower` are identity since
  * the carrier is already a native CE fiber. `init` uses `.start`, so the fork has no parent scope and the caller is responsible for its
  * lifetime. `onComplete` starts a listener fiber and translates `Outcome.Canceled` into `Failure(CancellationException)` before invoking
  * `cb`.
  */

opaque type CFiber[+A] = FiberIO[? <: A]

object CFiber:

    /** Forks `c` as a new fiber and returns a handle; the fork has no parent scope. */
    inline def init[A](inline c: CIO[A]): CIO[CFiber[A]] =
        CIO.lift(c.lower.start)

    /** Wraps a native `cats.effect.FiberIO` as a `CFiber`. Identity on the carrier. */
    inline def lift[A](inline u: FiberIO[A]): CFiber[A] = u

    extension [A](self: CFiber[A])

        /** Unwraps to the native `cats.effect.FiberIO`. Identity on the carrier. */
        inline def lower: FiberIO[? <: A] = self

        /** Joins the fiber and returns its result; cancellation surfaces as `CancellationException`. */
        inline def get: CIO[A] =
            CIO.lift(
                self.join.flatMap {
                    case cats.effect.Outcome.Succeeded(ioa) => ioa
                    case cats.effect.Outcome.Errored(t)     => IO.raiseError(t)
                    case cats.effect.Outcome.Canceled()     => IO.raiseError(new CancellationException("CFiber.interrupt"))
                }
            )

        /** Registers `cb` to fire when the fiber completes; success and failure are reified as `scala.util.Try`, and `Outcome.Canceled` is
          * translated to `Failure(CancellationException)` before the callback runs.
          */
        inline def onComplete(cb: scala.util.Try[A] => CIO[Unit]): CIO[Unit] =
            CIO.lift(
                self.join
                    .flatMap {
                        case cats.effect.Outcome.Succeeded(ioa) => ioa.flatMap(a => cb(scala.util.Success(a)).lower)
                        case cats.effect.Outcome.Errored(t)     => cb(scala.util.Failure(t)).lower
                        case cats.effect.Outcome.Canceled() => cb(scala.util.Failure(new CancellationException("CFiber.interrupt"))).lower
                    }
                    .start
                    .void
            )
    end extension
end CFiber
