package kyo

import core.*
import core.internal.*
import kyo.fibersInternal.*
import scala.util.control.NonFatal
import zio.Task
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

    // TODO: fix Envs with type intersections
    private[kyo] def get[R: zio.Tag, E: Tag, A](v: ZIO[R, E, A])(using tag: Tag[Envs[R]]): A < (Envs[R] & Aborts[E] & ZIOs) =
        Envs.get[R](using tag).map(r => get(v.provideEnvironment(ZEnvironment(r))))

    def get[E >: Nothing: Tag, A](v: ZIO[Any, E, A]): A < (Aborts[E] & ZIOs) =
        val task = v.fold[A < Aborts[E]](e => Aborts.fail(e), a => a)
        this.suspend(task, identity)

    def get[A](v: ZIO[Any, Nothing, A]): A < ZIOs =
        this.suspend(v)
end ZIOs
