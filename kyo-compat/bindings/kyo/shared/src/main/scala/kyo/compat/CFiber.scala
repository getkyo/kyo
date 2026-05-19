package kyo.compat

import kyo.*

opaque type CFiber[+A] = Fiber[A, Abort[Throwable]]

object CFiber:

    inline def init[A](inline c: CIO[A])(using inline frame: Frame): CIO[CFiber[A]] =
        CIO.lift(Fiber.initUnscoped(c.lower))

    inline def lift[A](inline u: Fiber[A, Abort[Throwable]]): CFiber[A] = u

    extension [A](inline self: CFiber[A])

        inline def lower: Fiber[A, Abort[Throwable]] = self

        inline def get(using inline frame: Frame): CIO[A] =
            CIO.lift(Fiber.get(self.lower))

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
