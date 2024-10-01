package kyo

import cats.effect.IO as CatsIO
import kyo.kernel.*
import kyo.scheduler.IOPromise
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

object Cats:

    /** Lifts a cats.effect.IO into a Kyo effect.
      *
      * @param io
      *   The cats.effect.IO to lift
      * @return
      *   A Kyo effect that, when run, will execute the cats.effect.IO
      */
    def get[A](io: CatsIO[A])(using Frame): A < (Abort[Throwable] & Async) =
        IO {
            import cats.effect.unsafe.implicits.global
            val p                = new IOPromise[Throwable, A]
            val (result, cancel) = io.unsafeToFutureCancelable()
            result.onComplete(t => p.complete(Result.fromTry(t)))(ExecutionContext.parasitic)
            p.onInterrupt(_ => discard(cancel()))
            Async.get(p)
        }
    end get

    /** Interprets a Kyo computation to cats.effect.IO. Note that this method only accepts Abort[Throwable] and Async pending effects. Plase
      * handle any other effects before calling this method.
      *
      * @param v
      *   The Kyo effect to run
      * @return
      *   A cats.effect.IO that, when run, will execute the Kyo effect
      */
    def run[A: Flat](v: => A < (Abort[Throwable] & Async))(using frame: Frame): CatsIO[A] =
        CatsIO.defer {
            Async.run(v).map { fiber =>
                CatsIO.async[A] { cb =>
                    CatsIO {
                        fiber.unsafe.onComplete(r => cb(r.toEither))
                        Some(CatsIO(fiber.unsafe.interrupt(Result.Panic(Fiber.Interrupted(frame)))).void)
                    }
                }
            }.pipe(IO.run).eval
        }
    end run
end Cats
