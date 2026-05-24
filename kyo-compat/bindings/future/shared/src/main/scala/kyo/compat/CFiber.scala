package kyo.compat

import kyo.compat.internal.LocalCtx
import scala.concurrent.Future
import scala.util.Try

/** Backed by `scala.concurrent.Future[A]`. `init` spawns the body eagerly under the current `LocalCtx`; the returned `Future[A]` is the
  * fiber handle. `lift` wraps an existing `Future[A]` directly. `onComplete` registers a callback on the underlying `Future`.
  */
opaque type CFiber[+A] = Future[A]

object CFiber:

    inline def init[A](inline c: CIO[A]): CIO[CFiber[A]] =
        CIO.deferLift(Future.successful(c.lower))

    inline def lift[A](inline u: Future[A]): CFiber[A] = u

    extension [A](inline self: CFiber[A])

        inline def lower: Future[A] = self

        inline def get: CIO[A] = CIO.lift(self.lower)

        inline def onComplete(inline cb: Try[A] => CIO[Unit]): CIO[Unit] =
            CIO.defer {
                self.lower.onComplete { t =>
                    val _ = cb(t).unsafeRun
                    ()
                }(using scala.concurrent.ExecutionContext.parasitic)
            }
    end extension

end CFiber
