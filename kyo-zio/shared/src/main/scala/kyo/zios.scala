package kyo

import core.*
import core.internal.*
import kyo.fibersInternal.*
import scala.annotation.targetName
import scala.util.control.NonFatal
import zio.IO
import zio.Task
import zio.UIO
import zio.URIO
import zio.ZEnvironment
import zio.ZIO

class ZIOs extends Effect[ZIOs]:
    type Command[T] = Task[T]

object ZIOs extends ZIOs:

    def run[T: Flat](v: T < (Fibers & ZIOs)): Task[T] =
        def loop(v: T < (Fibers & ZIOs)): Task[T] =
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
                        else if kyo.tag == Tag[ZIOs] then
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

    def get[R: zio.Tag, E: Tag, A](v: ZIO[R, E, A])(using tag: Tag[Envs[R]]): A < (Envs[R] & Aborts[E] & ZIOs) =
        Envs.get[R](using tag).map(r => get(v.provideEnvironment(ZEnvironment(r))))

    def get[R: zio.Tag, A](v: URIO[R, A])(using tag: Tag[Envs[R]]): A < (Envs[R] & ZIOs) =
        Envs.get[R](using tag).map(r => get(v.provideEnvironment(ZEnvironment(r))))

    def get[E: Tag, T](v: IO[E, T]): T < (Aborts[E] & ZIOs) =
        val task = v.fold[T < Aborts[E]](Aborts.fail(_), v => v)
        this.suspend(task, identity)

    def get[T](v: UIO[T]): T < ZIOs =
        this.suspend(v)

    @targetName("getTask")
    def get[T](v: Task[T]): T < ZIOs =
        this.suspend[T](v)
end ZIOs
