package kyo.compat

import zio.*

/** Backed by `zio.Fiber.Runtime[Throwable, A]`. `init` uses `forkDaemon` to spawn a fiber outside the current scope. `get` awaits the fiber
  * and surfaces failures. `onComplete` forks a daemon watcher that fires the callback on resolution.
  */
opaque type CFiber[+A] = Fiber.Runtime[Throwable, A]

object CFiber:

    inline def init[A](inline c: CIO[A])(using inline trace: Trace): CIO[CFiber[A]] =
        CIO.lift(c.lower.forkDaemon)

    inline def lift[A](inline u: Fiber.Runtime[Throwable, A]): CFiber[A] = u

    extension [A](inline self: CFiber[A])

        inline def lower: Fiber.Runtime[Throwable, A] = self

        inline def get(using inline trace: Trace): CIO[A] =
            CIO.lift(
                self.lower.await.flatMap {
                    case Exit.Success(a)     => ZIO.succeed(a)
                    case Exit.Failure(cause) => ZIO.fail(cause.squash)
                }
            )

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
