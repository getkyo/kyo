package kyo.compat

import cats.effect.FiberIO
import cats.effect.IO
import java.util.concurrent.CancellationException

/** Backed by `cats.effect.FiberIO[A]`. `init` uses `.start`. `get` uses `joinWithNever`. `onComplete` starts a listener fiber; on
  * `Outcome.Canceled` the callback fires with `Failure(CancellationException)`. CE's `IORuntime` reports any unhandled callback failures
  * via its default error handler.
  */

opaque type CFiber[+A] = FiberIO[? <: A]

object CFiber:

    inline def init[A](inline c: CIO[A]): CIO[CFiber[A]] =
        CIO.lift(c.lower.start)

    inline def lift[A](inline u: FiberIO[A]): CFiber[A] = u

    extension [A](self: CFiber[A])

        inline def lower: FiberIO[? <: A] = self

        inline def get: CIO[A] =
            CIO.lift(
                self.join.flatMap {
                    case cats.effect.Outcome.Succeeded(ioa) => ioa
                    case cats.effect.Outcome.Errored(t)     => IO.raiseError(t)
                    case cats.effect.Outcome.Canceled()     => IO.raiseError(new CancellationException("CFiber.interrupt"))
                }
            )

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
