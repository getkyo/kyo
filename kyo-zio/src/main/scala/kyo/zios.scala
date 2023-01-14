package kyo

import zio.ZIO
import core._
import envs._
import aborts._
import izumi.reflect.{Tag => ITag}
import zio._
import java.io.IOException
import scala.annotation.targetName
import zios._

object zios {

  final class ZIOs private[zios] () extends Effect[Task] {
    @targetName("zio")
    inline def apply[R: ITag, E: ITag, A](v: ZIO[R, E, A]): A > (Envs[R] | Aborts[E] | ZIOs) =
      val a: URIO[R, A > Aborts[E]]           = v.fold[A > Aborts[E]](Aborts(_), v => v)
      val b: Task[A > Aborts[E]] > Envs[R]    = Envs[R](r => a.provideEnvironment(ZEnvironment(r)))
      val c: A > (Aborts[E] | Envs[R] | ZIOs) = (b > this)()
      c
  }
  val ZIOs = new ZIOs

  inline given [T]: DeepHandler[Task, ZIOs] with {
    override def pure[T](v: T): Task[T] =
      ZIO.succeed(v)
    override def flatMap[T, U](m: Task[T], f: T => Task[U]): Task[U] =
      m.flatMap(f)
  }

  private given zioTag[T](using t: ITag[T]): zio.Tag[T] =
    new zio.Tag[T] {
      override def tag: zio.LightTypeTag  = t.tag
      override def closestClass: Class[?] = t.closestClass
    }
}
