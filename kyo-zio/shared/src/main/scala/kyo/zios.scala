package kyo

import ZIOs.internal.*
import core.*
import core.internal.*
import kyo.fibersInternal.*
import kyo.internal.Trace
import scala.util.control.NonFatal
import zio.Task
import zio.ZIO

opaque type ZIOs <: Fibers = Tasks & Fibers

object ZIOs:

    def get[E >: Nothing: Tag, A](v: ZIO[Any, E, A])(using Trace): A < (Aborts[E] & ZIOs) =
        val task = v.fold[A < Aborts[E]](e => Aborts.fail(e), a => a)
        Tasks.suspend[A < Aborts[E], A, Aborts[E]](task, identity)

    def get[A](v: ZIO[Any, Nothing, A])(using Trace): A < ZIOs =
        Tasks.suspend[A](v)

    inline def get[R: zio.Tag, E, A](v: ZIO[R, E, A])(using Tag[Envs[R]], Trace): A < (Envs[R] & ZIOs) =
        compiletime.error("ZIO environments are not supported yet. Please handle them before calling this method.")

    def run[T: Flat](v: T < (Aborts[Throwable] & ZIOs))(using Trace): Task[T] =
        def loop[U](v: U < ZIOs): Task[U] =
            v match
                case kyo: Suspend[?, ?, ?, ?] =>
                    try
                        if kyo.tag =:= Tag[IOs] then
                            val k = kyo.asInstanceOf[Suspend[?, Unit, U, Fibers]]
                            ZIO.suspend(loop(k(())))
                        else if kyo.tag =:= Tag[FiberGets] then
                            val k = kyo.asInstanceOf[Suspend[Fiber, Any, U, FiberGets]]
                            k.command match
                                case Done(v) =>
                                    ZIO.suspend(loop(k(v)))
                                case Promise(p) =>
                                    ZIO.asyncInterrupt[Any, Throwable, U] { cb =>
                                        p.onComplete(v => cb(ZIO.suspend(loop(v.map(k)))))
                                        Left(ZIO.succeed(p.interrupt()))
                                    }
                            end match
                        else if kyo.tag =:= Tag[Tasks] then
                            val k = kyo.asInstanceOf[Suspend[Task, Any, U, ZIOs]]
                            k.command.flatMap(v => loop(k(v)))
                        else
                            bug.failTag(kyo, Tag.Intersection[FiberGets & Tasks & IOs])
                    catch
                        case ex if NonFatal(ex) =>
                            ZIO.fail(ex)
                case v =>
                    ZIO.succeed(v.asInstanceOf[U])
            end match
        end loop

        loop(Aborts.run(v)).flatMap {
            case Left(ex)     => ZIO.fail(ex)
            case Right(value) => ZIO.succeed(value)
        }
    end run

    private[kyo] object internal:
        class Tasks extends Effect[Tasks]:
            type Command[T] = Task[T]
        object Tasks extends Tasks
    end internal
end ZIOs
