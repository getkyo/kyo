package kyo.compat

import kyo.*

/** Underlying carrier is `kyo.Fiber[A, Abort[Throwable]]`. `init` uses `Fiber.initUnscoped`, so the fork has no parent scope and the caller
  * is responsible for its lifetime. `lift` and `lower` are identity since the carrier is already a kyo-native fiber. `onComplete` adapts
  * kyo's `Result` (success / failure / panic) into a `scala.util.Try[A]` for the callback.
  */
opaque type CFiber[+A] = Fiber[A, Abort[Throwable]]

object CFiber:

    /** Forks `c` as a new fiber and returns a handle; the fork has no parent scope. */
    inline def init[A](inline c: CIO[A])(using inline frame: Frame): CIO[CFiber[A]] =
        CIO.lift(Fiber.initUnscoped(c.lower))

    /** Wraps a native `kyo.Fiber` as a `CFiber`. Identity on the carrier. */
    inline def lift[A](inline u: Fiber[A, Abort[Throwable]]): CFiber[A] = u

    extension [A](inline self: CFiber[A])

        /** Unwraps to the native `kyo.Fiber`. Identity on the carrier. */
        inline def lower: Fiber[A, Abort[Throwable]] = self

        /** Joins the fiber and returns its result. */
        inline def get(using inline frame: Frame): CIO[A] =
            CIO.lift(Fiber.get(self.lower))

        /** Registers `cb` to fire when the fiber completes naturally; success and failure are reified as `scala.util.Try`. */
        inline def onComplete(inline cb: scala.util.Try[A] => CIO[Unit])(
            using inline frame: Frame
        ): CIO[Unit] =
            CIO.lift {
                Fiber.onComplete(self.lower) { (r: Result[Throwable, A < Any]) =>
                    val tr: scala.util.Try[A] = r match
                        case Result.Failure(e) => scala.util.Failure(e)
                        case Result.Panic(t)   => scala.util.Failure(t)
                        case Result.Success(a) => scala.util.Success(a.eval)
                    Fiber.initUnscoped(cb(tr).lower)
                }
            }

    end extension
end CFiber
