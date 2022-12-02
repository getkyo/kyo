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

  val v0: ZIO[Service1, IllegalStateException, Int] =
    Random.nextInt.flatMap {
      case 0 => ZIO.fail(new IllegalStateException)
      case 1 => ZIO.service[Service1].map(_.get(1))
      case i => ZIO.succeed(10 / i)
    }

  val v1: Int > (Envs[Service1] | Aborts[IllegalStateException] | ZIOs) =
    ZIOs(v0)

  val v3: Int > (Aborts[IllegalStateException] | ZIOs) =
    Envs.let(sertvice1)(v1(_ + 1))

  val v4: Either[IllegalStateException, Int] > ZIOs =
    (v3 < Aborts[IllegalStateException])(_.toEither)

  val v5: Task[Either[IllegalStateException, Int]] > Nothing =
    v4 << ZIOs

  val v6: Task[Either[IllegalStateException, Int]] =
    v5
  def run =
    v6.flatMap(Console.printLine(_))

  trait Service1 {
    def get(i: Int): Int
  }
  lazy val sertvice1: Service1 = _ + 1
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
