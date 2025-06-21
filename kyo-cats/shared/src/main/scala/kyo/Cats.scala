package kyo

import cats.effect.IO as CatsIO
import kyo.kernel.*
import scala.concurrent.ExecutionContext
import scala.util.Failure
import scala.util.Success

object Cats:

    /** Lifts a cats.effect.IO into a Kyo effect.
      *
      * @param io
      *   The cats.effect.IO to lift
      * @return
      *   A Kyo effect that, when run, will execute the cats.effect.IO
      */
    def get[A](io: => CatsIO[A])(using Frame): A < (Abort[Nothing] & Async) =
        Sync.Unsafe {
            import cats.effect.unsafe.implicits.global
            val p                = Promise.Unsafe.init[Nothing, A]()
            val (future, cancel) = io.unsafeToFutureCancelable()
            future.onComplete {
                case Success(v)  => p.complete(Result.succeed(v))
                case Failure(ex) => p.complete(Result.panic(ex))
            }(using ExecutionContext.parasitic)
            p.onInterrupt(_ => discard(cancel()))
            p.safe.get
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
    def run[A](v: => A < (Abort[Throwable] & Async))(using frame: Frame): CatsIO[A] =
        CatsIO.defer {
            import AllowUnsafe.embrace.danger
            Async.run(v).map { fiber =>
                CatsIO.async[A] { cb =>
                    CatsIO {
                        fiber.unsafe.onComplete(r => cb(r.toEither))
                        Some(CatsIO(fiber.unsafe.interrupt(Result.Panic(Fiber.Interrupted(frame)))).void)
                    }
                }
            }.handle(Sync.Unsafe.evalOrThrow)
        }
    end run
end Cats
