package kyo

import kyo.kernel.*
import kyo.scheduler.IOPromise
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import zio.Exit
import zio.Runtime
import zio.Unsafe
import zio.ZIO

object ZIOs:

    /** Lifts a zio.ZIO into a Kyo effect.
      *
      * @param zio
      *   The zio.ZIO to lift
      * @return
      *   A Kyo effect that, when run, will execute the zio.ZIO
      */
    def get[E, A](v: => ZIO[Any, E, A])(using Frame): A < (Abort[E] & Async) =
        IO {
            val p = new IOPromise[E, A]
            val result =
                Unsafe.unsafe { implicit unsafe =>
                    Runtime.default.unsafe.runToFuture(v.either)
                }
            result.onComplete { r =>
                p.complete(r.fold(ex => Result.panic(ex), e => Result.fromEither(e)))
            }(ExecutionContext.parasitic)
            p.onInterrupt(_ => discard(result.cancel()))
            Async.get(p)
        }
    end get

    /** Placeholder for ZIO effects with environments (currently not supported).
      *
      * @tparam R
      *   The environment type
      * @tparam E
      *   The error type
      * @tparam A
      *   The result type
      */
    inline def get[R: zio.Tag, E, A](v: ZIO[R, E, A])(using Tag[Env[R]], Frame): A < (Env[R] & Abort[E] & Async) =
        compiletime.error("ZIO environments are not supported yet. Please handle them before calling this method.")

    /** Interprets a Kyo computation to ZIO. Note that this method only accepts Abort[E] and Async pending effects. Plase handle any other
      * effects before calling this method.
      *
      * @param v
      *   The Kyo effect to run
      * @return
      *   A zio.ZIO that, when run, will execute the Kyo effect
      */
    def run[A: Flat, E](v: => A < (Abort[E] & Async))(using frame: Frame): ZIO[Any, E, A] =
        ZIO.suspendSucceed {
            Async.run(v).map { fiber =>
                ZIO.asyncInterrupt[Any, E, A] { cb =>
                    fiber.unsafe.onComplete {
                        case Result.Success(a) => cb(ZIO.succeed(a))
                        case Result.Fail(e)    => cb(ZIO.fail(e))
                        case Result.Panic(e)   => cb(ZIO.die(e))
                    }
                    Left(ZIO.succeed {
                        fiber.unsafe.interrupt(Result.Panic(Fiber.Interrupted(frame)))
                    })
                }
            }.pipe(IO.run).eval
        }
    end run

end ZIOs
