package kyo.compat

import zio.*

/** Underlying carrier is `zio.Fiber.Runtime[Throwable, A]`. Operations propagate ZIO `Trace` through `(using inline trace: Trace)` on every
  * entry point. `lift` and `lower` are identity since the carrier is already a native ZIO fiber. `init` uses `forkDaemon` to spawn the fork
  * outside the current scope, so the caller is responsible for its lifetime. `onComplete` fires with `Failure(cause.squash)` for
  * non-success exits, matching `Exit.Failure` against every non-success outcome.
  */
opaque type CFiber[+A] = Fiber.Runtime[Throwable, A]

object CFiber:

    /** Forks `c` as a daemon fiber and returns a handle; the fork has no parent scope. */
    inline def init[A](inline c: CIO[A])(using inline trace: Trace): CIO[CFiber[A]] =
        CIO.lift(c.lower.forkDaemon)

    /** Wraps a native `zio.Fiber.Runtime` as a `CFiber`. Identity on the carrier. */
    inline def lift[A](inline u: Fiber.Runtime[Throwable, A]): CFiber[A] = u

    extension [A](inline self: CFiber[A])

        /** Unwraps to the native `zio.Fiber.Runtime`. Identity on the carrier. */
        inline def lower: Fiber.Runtime[Throwable, A] = self

        /** Joins the fiber and returns its result. */
        inline def get(using inline trace: Trace): CIO[A] =
            CIO.lift(
                self.lower.await.flatMap {
                    case Exit.Success(a)     => ZIO.succeed(a)
                    case Exit.Failure(cause) => ZIO.fail(cause.squash)
                }
            )

        /** Registers `cb` to fire when the fiber completes; success and failure are reified as `scala.util.Try`, with non-success exits
          * surfaced as `Failure(cause.squash)`.
          */
        inline def onComplete(inline cb: scala.util.Try[A] => CIO[Unit])(
            using inline trace: Trace
        ): CIO[Unit] =
            CIO.lift(
                self.lower.await
                    .flatMap { exit =>
                        val t: scala.util.Try[A] = exit match
                            case Exit.Success(a)     => scala.util.Success(a)
                            case Exit.Failure(cause) => scala.util.Failure(cause.squash)
                        cb(t).lower
                    }
                    .forkDaemon
                    .unit
            )
    end extension
end CFiber
