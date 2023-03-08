package kyo

import zio.ZIO
import core._
import envs._
import aborts._
import izumi.reflect.{Tag => ITag}
import zio.{URIO, RIO, IO, Task, ZIO, ZEnvironment, UIO}
import java.io.IOException
import scala.annotation.targetName
import zios._
import ios._
import concurrent.fibers._
import scala.util.control.NonFatal

object zios {

  final class ZIOs private[zios] () extends Effect[Task] {

    @targetName("fromZIO")
    def apply[R: ITag, E: ITag, A](v: ZIO[R, E, A]): A > (Envs[R] | Aborts[E] | ZIOs) =
      val a: URIO[R, A > Aborts[E]]           = v.fold[A > Aborts[E]](Aborts(_), v => v)
      val b: Task[A > Aborts[E]] > Envs[R]    = Envs[R](r => a.provideEnvironment(ZEnvironment(r)))
      val c: A > (Aborts[E] | Envs[R] | ZIOs) = (b > this).flatten
      c

    @targetName("fromIO")
    def apply[E: ITag, A](v: IO[E, A]): A > (Aborts[E] | ZIOs) =
      val a: Task[A > Aborts[E]]    = v.fold[A > Aborts[E]](Aborts(_), v => v)
      val b: A > (Aborts[E] | ZIOs) = (a > this).flatten
      b

    @targetName("fromTask")
    def apply[T](v: Task[T]): T > ZIOs =
      v > this
  }
  val ZIOs = new ZIOs

  private[kyo] given [T]: DeepHandler[Task, ZIOs] with {
    override def pure[T](v: T): Task[T] =
      ZIO.succeed(v)
    override def apply[T, U](m: Task[T], f: T => Task[U]): Task[U] =
      m.flatMap(f)
  }

  private[kyo] given Injection[Fiber, Fibers, Task, ZIOs, Nothing] with {
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

  private given zioTag[T](using t: ITag[T]): zio.Tag[T] =
    new zio.Tag[T] {
      override def tag: zio.LightTypeTag  = t.tag
      override def closestClass: Class[?] = t.closestClass
    }
}
