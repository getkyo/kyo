package kyo

import izumi.reflect.{Tag => ITag}
import zio.IO
import zio.RIO
import zio.Task
import zio.UIO
import zio.URIO
import zio.ZEnvironment
import zio.ZIO

import java.io.IOException
import scala.util.control.NonFatal

import core._
import core.internal._
import envs._
import aborts._
import zios._
import ios._
import concurrent.fibers._

object zios {

  final class ZIOs private[zios] () extends Effect[Task, ZIOs] {

    def run[T](v: T > ZIOs)(implicit ng: kyo.NotGiven[(Nothing > Any) => T]): Task[T] = {
      implicit val handler: DeepHandler[Task, ZIOs] =
        new DeepHandler[Task, ZIOs] {
          def pure[T](v: T): Task[T] =
            ZIO.succeed(v)
          def apply[T, U](m: Task[T], f: T => Task[U]): Task[U] =
            m.flatMap(f)
        }
      deepHandle[Task, ZIOs, T](ZIOs)(v)
    }

    def fromZIO[R: ITag, E: ITag, A, S](v: ZIO[R, E, A] > S)
        : A > (S with Envs[R] with Aborts[E] with ZIOs) =
      for {
        urio <- v.map(_.fold[A > Aborts[E]](Aborts(_), v => v))
        task <- Envs[R].get.map(r => urio.provideEnvironment(ZEnvironment(r)(zioTag[R])))
        r    <- suspend(task)
      } yield r

    def fromIO[E: ITag, A, S](v: IO[E, A] > S): A > (S with Aborts[E] with ZIOs) =
      for {
        task <- v.map(_.fold[A > Aborts[E]](Aborts(_), v => v))
        r    <- suspend(task)
      } yield r

    def fromTask[T, S](v: Task[T] > S): T > (S with ZIOs) =
      suspend(v)
  }
  val ZIOs = new ZIOs

  private[kyo] implicit val injection: Injection[Fiber, Fibers, Task, ZIOs] =
    new Injection[Fiber, Fibers, Task, ZIOs] {
      def apply[T](m: Fiber[T]) =
        ZIO.asyncInterrupt[Any, Throwable, T] { callback =>
          IOs.run {
            m.onComplete { io =>
              callback {
                try {
                  ZIO.succeed(IOs.run(io))
                } catch {
                  case ex if NonFatal(ex) =>
                    ZIO.fail(ex)
                }
              }
            }
          }
          Left {
            ZIO.succeedUnsafe { u =>
              IOs.run(m.interrupt)
            }
          }
        }
    }

  private def zioTag[T](implicit t: ITag[T]): zio.Tag[T] =
    new zio.Tag[T] {
      def tag: zio.LightTypeTag  = t.tag
      def closestClass: Class[?] = t.closestClass
    }
}
