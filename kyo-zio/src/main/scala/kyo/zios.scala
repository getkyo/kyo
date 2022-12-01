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

object ziosTest extends ZIOAppDefault {
  def test(i: Int): Either[IllegalStateException, String] > ZIOs =
    val a = ZIO.succeed(i).flatMap {
      case 0 => ZIO.fail(new IllegalStateException)
      case 1 => ZIO.service[Int].map(_ + 1)
      case i => ZIO.succeed(10 / i)
    }
    val b: Int > (Envs[Int] | Aborts[IllegalStateException] | ZIOs) =
      ZIOs(a)
    val c: Int > (Aborts[IllegalStateException] | ZIOs) =
      Envs.let(1)(b)
    val d: String > (Aborts[IllegalStateException] | ZIOs) =
      c(_ + 1)(i => "i" + i)
    val e: Abort[IllegalStateException, String] > ZIOs =
      (d < Aborts[IllegalStateException])
    e(_.toEither) 

  def run =
    val a =
      for {
        v1 <- test(0)
        v2 <- test(1)
        v3 <- test(2)
      } yield (v1, v2, v3)
    (a << ZIOs).flatMap(Console.printLine(_))
}

object zios {

  final class ZIOs private[zios] () extends Effect[Task] {
    @targetName("task")
    inline def apply[A](v: Task[A]): A > ZIOs =
      (v > this)
    @targetName("urio")
    inline def apply[R: ITag, A](v: URIO[R, A]): A > (Envs[R] | ZIOs) =
      val a: Task[A] > Envs[R] = Envs[R](r => v.provideEnvironment(ZEnvironment(r)))
      a(apply(_))
    @targetName("io")
    inline def apply[E: ITag, A](v: IO[E, A]): A > (Aborts[E] | ZIOs) =
      val a: Task[A > Aborts[E]]    = v.fold[A > Aborts[E]](Aborts(_), v => v)
      val c: A > (Aborts[E] | ZIOs) = (a > this)()
      c
    @targetName("zio")
    inline def apply[R: ITag, E: ITag, A](v: ZIO[R, E, A]): A > (Envs[R] | Aborts[E] | ZIOs) =
      val a: URIO[R, A > Aborts[E]]           = v.fold[A > Aborts[E]](Aborts(_), v => v)
      val b: Task[A > Aborts[E]] > Envs[R]    = Envs[R](r => a.provideEnvironment(ZEnvironment(r)))
      val c: A > (Aborts[E] | Envs[R] | ZIOs) = (b > this)()
      c
  }
  val ZIOs = new ZIOs

  inline given [T]: DeepHandler[Task, ZIOs] =
    new DeepHandler[Task, ZIOs] {
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
