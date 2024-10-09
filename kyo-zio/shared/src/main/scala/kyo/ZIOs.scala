package kyo

import kyo.kernel.*
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
    def get[E, A](v: => ZIO[Any, E, A])(using Frame, zio.Trace): A < (Abort[E] & Async) =
        IO.Unsafe {
            val p      = Promise.Unsafe.init[E, A]()
            val future = Unsafe.unsafely(Runtime.default.unsafe.runToFuture(v.either))
            future.onComplete { t =>
                p.complete(t.fold(ex => Result.panic(ex), Result.fromEither))
            }(ExecutionContext.parasitic)
            p.onInterrupt(_ => discard(future.cancel()))
            p.safe.get
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
    inline def get[R: zio.Tag, E, A](v: ZIO[R, E, A])(using Tag[Env[R]], Frame, zio.Trace): A < (Env[R] & Abort[E] & Async) =
        compiletime.error("ZIO environments are not supported yet. Please handle them before calling this method.")

    /** Interprets a Kyo computation to ZIO. Note that this method only accepts Abort[E] and Async pending effects. Plase handle any other
      * effects before calling this method.
      *
      * @param v
      *   The Kyo effect to run
      * @return
      *   A zio.ZIO that, when run, will execute the Kyo effect
      */
    def run[A: Flat, E](v: => A < (Abort[E] & Async))(using frame: Frame, trace: zio.Trace): ZIO[Any, E, A] =
        ZIO.suspendSucceed {
            import AllowUnsafe.embrace.danger
            Async.run(v).map { fiber =>
                ZIO.asyncInterrupt[Any, E, A] { cb =>
                    fiber.unsafe.onComplete {
                        case Result.Success(a) => cb(Exit.succeed(a))
                        case Result.Fail(e)    => cb(Exit.fail(e))
                        case Result.Panic(e)   => cb(Exit.die(e))
                    }
                    Left(ZIO.succeed {
                        fiber.unsafe.interrupt(Result.Panic(Fiber.Interrupted(frame)))
                    })
                }
            }.pipe(IO.Unsafe.run).eval
        }
    end run

end ZIOs
