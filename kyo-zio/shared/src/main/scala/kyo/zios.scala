package kyo

import ZIOs.internal.*
import core.*
import core.internal.*
import kyo.fibersInternal.*
import scala.util.control.NonFatal
import zio.Task
import zio.ZIO

opaque type ZIOs <: Fibers = Tasks & Fibers

object ZIOs:

    def get[E >: Nothing: Tag, A](v: ZIO[Any, E, A]): A < (Aborts[E] & ZIOs) =
        val task = v.fold[A < Aborts[E]](e => Aborts.fail(e), a => a)
        Tasks.suspend[A < Aborts[E], A, Aborts[E]](task, identity)

    def get[A](v: ZIO[Any, Nothing, A]): A < ZIOs =
        Tasks.suspend[A](v)

    inline def get[R: zio.Tag, A](v: ZIO[R, ?, A])(using tag: Tag[Envs[R]]): A < (Envs[R] & ZIOs) =
        compiletime.error("ZIO environments are not supported yet. Please handle them before calling this method.")

    def run[T: Flat](v: T < ZIOs): Task[T] =
        def loop(v: T < ZIOs): Task[T] =
            v match
                case kyo: Suspend[?, ?, ?, ?] =>
                    try
                        if kyo.tag == Tag[IOs] then
                            val k = kyo.asInstanceOf[Suspend[?, Unit, T, Fibers]]
                            ZIO.suspend(loop(k(())))
                        else if kyo.tag == Tag[FiberGets] then
                            val k = kyo.asInstanceOf[Suspend[Fiber, Any, T, FiberGets]]
                            k.command match
                                case Done(v) =>
                                    ZIO.suspend(loop(k(v)))
                                case Promise(p) =>
                                    ZIO.asyncInterrupt[Any, Throwable, T] { cb =>
                                        p.onComplete(v => cb(ZIO.suspend(loop(v.map(k)))))
                                        Left(ZIO.succeed(p.interrupt()))
                                    }
                            end match
                        else if kyo.tag == Tag[Tasks] then
                            val k = kyo.asInstanceOf[Suspend[Task, Any, T, ZIOs]]
                            k.command.flatMap(v => loop(k(v)))
                        else
                            bug.failTag(kyo.tag, Tag[Fibers & ZIOs])
                    catch
                        case ex if NonFatal(ex) =>
                            ZIO.fail(ex)
                case v =>
                    ZIO.succeed(v.asInstanceOf[T])
            end match
        end loop

        loop(v)
    end run

    private[kyo] object internal:
        class Tasks extends Effect[Tasks]:
            type Command[T] = Task[T]
        object Tasks extends Tasks
    end internal
end ZIOs
