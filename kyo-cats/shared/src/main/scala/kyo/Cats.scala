package kyo

import Cats.GetCatsIO
import cats.effect.IO as CatsIO
import kyo.kernel.*
import scala.util.control.NonFatal

opaque type Cats <: (Async & Abort[Throwable]) = GetCatsIO & Async & Abort[Throwable]

object Cats:

    /** Lifts a cats.effect.IO into a Kyo effect.
      *
      * @param io
      *   The cats.effect.IO to lift
      * @return
      *   A Kyo effect that, when run, will execute the cats.effect.IO
      */
    def get[A](io: CatsIO[A])(using Frame): A < Cats =
        ArrowEffect.suspendMap(Tag[GetCatsIO], io)(Abort.get(_))

    /** Runs a Kyo effect that uses Cats and converts it to a cats.effect.IO.
      *
      * @param v
      *   The Kyo effect to run
      * @return
      *   A cats.effect.IO that, when run, will execute the Kyo effect
      */
    def run[A](v: => A < Cats)(using frame: Frame): CatsIO[A] =
        CatsIO.defer {
            ArrowEffect.handle(Tag[GetCatsIO], v.map(CatsIO.pure))(
                [C] => (input, cont) => input.attempt.flatMap(r => run(cont(r)).flatten)
            ).pipe(Async.run)
                .map { fiber =>
                    CatsIO.async[CatsIO[A]] { cb =>
                        CatsIO {
                            fiber.unsafe.onResult(r => cb(r.toEither))
                            Some(CatsIO(fiber.unsafe.interrupt(Result.Panic(Fiber.Interrupted(frame)))).void)
                        }
                    }
                }.pipe(IO.run).eval.flatten
        }
    end run

    sealed private[kyo] trait GetCatsIO extends ArrowEffect[CatsIO, Either[Throwable, *]]
end Cats
