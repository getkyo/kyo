package kyo

import ZIOs.GetZIO
import kyo.kernel.*
import scala.util.control.NonFatal
import zio.Exit
import zio.ZIO

/** Effect to integrate ZIO with Kyo */
opaque type ZIOs <: Async = GetZIO & Async

object ZIOs:

    /** Executes a ZIO effect and returns its result within the Kyo effect system.
      *
      * @param v
      *   The ZIO effect to execute
      * @param ev
      *   Evidence that E is a subtype of Nothing
      * @param tag
      *   Tag for E
      * @param frame
      *   Implicit Frame
      * @tparam E
      *   The error type (must be a subtype of Nothing)
      * @tparam A
      *   The result type
      * @return
      *   A Kyo effect that will produce A or abort with E
      */
    def get[E >: Nothing: Tag, A](v: ZIO[Any, E, A])(using Frame): A < (Abort[E] & ZIOs) =
        val task = v.fold(Result.error, Result.succeed)
        ArrowEffect.suspendMap(Tag[GetZIO], task)(Abort.get(_))

    /** Executes a ZIO effect that cannot fail and returns its result within the Kyo effect system.
      *
      * @param v
      *   The ZIO effect to execute
      * @param frame
      *   Implicit Frame
      * @tparam A
      *   The result type
      * @return
      *   A Kyo effect that will produce A
      */
    def get[A](v: ZIO[Any, Nothing, A])(using Frame): A < ZIOs =
        ArrowEffect.suspend(Tag[GetZIO], v)

    /** Placeholder for ZIO effects with environments (currently not supported).
      *
      * @tparam R
      *   The environment type
      * @tparam E
      *   The error type
      * @tparam A
      *   The result type
      */
    inline def get[R: zio.Tag, E, A](v: ZIO[R, E, A])(using Tag[Env[R]], Frame): A < (Env[R] & ZIOs) =
        compiletime.error("ZIO environments are not supported yet. Please handle them before calling this method.")

    /** Runs a Kyo effect within the ZIO runtime.
      *
      * @param v
      *   The Kyo effect to run
      * @param frame
      *   Implicit Frame
      * @tparam E
      *   The error type
      * @tparam A
      *   The result type
      * @return
      *   A ZIO effect that will produce A or fail with E
      */
    def run[E, A](v: => A < (Abort[E] & ZIOs))(using frame: Frame): ZIO[Any, E, A] =
        ZIO.suspendSucceed {
            try
                ArrowEffect.handle(Tag[GetZIO], v.map(r => ZIO.succeed(r): ZIO[Any, E, A]))(
                    [C] => (input, cont) => input.flatMap(r => run(cont(r)).flatten)
                ).pipe(Async.run).map { fiber =>
                    ZIO.asyncInterrupt[Any, E, A] { cb =>
                        fiber.unsafe.onComplete {
                            case Result.Error(ex)  => cb(Exit.fail(ex))
                            case Result.Panic(ex)  => cb(Exit.die(ex))
                            case Result.Success(v) => cb(v)
                        }
                        Left(ZIO.succeed {
                            fiber.unsafe.interrupt(Result.Panic(Fiber.Interrupted(frame)))
                        })
                    }
                }.pipe(IO.run).eval
            catch
                case ex =>
                    ZIO.isFatalWith { isFatal =>
                        if !isFatal(ex) then Exit.die(ex)
                        else throw ex
                    }
        }
    end run

    sealed private[kyo] trait GetZIO extends ArrowEffect[ZIO[Any, Nothing, *], Id]
end ZIOs
