package kyoTest

import kyo.concurrent.fibers._
import kyo._
import kyo.ios._
import zio.Trace
import zio.prelude.{Equal}
import zio.prelude.coherent.CovariantDeriveEqual
import zio.prelude.coherent.CovariantDeriveEqualIdentityFlatten
import zio.prelude.laws._
import zio.test._
import zio.test.laws._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

object MonadLawsTest extends ZIOSpecDefault {

  case class Myo[+T](v: T > (IOs with Fibers))

  val listGenF: GenF[Any, Myo] =
    new GenF[Any, Myo] {
      def apply[R, A](gen: Gen[R, A])(implicit trace: Trace) =
        Gen.oneOf(
            gen.map(v => (v: A > (IOs with Fibers))),
            gen.map(v => IOs(v)),
            gen.map(v => Fibers.fork(v)),
            gen.map(v => IOs(Fibers.fork(v))),
            gen.map(v => Fibers.fork(IOs(v)))
        ).map(Myo(_))
    }

  implicit val cdeif: CovariantDeriveEqualIdentityFlatten[Myo] =
    new CovariantDeriveEqualIdentityFlatten[Myo] {
      override def flatten[A](ffa: Myo[Myo[A]]): Myo[A] =
        Myo(ffa.v.flatMap(_.v))
      override def any: Myo[Any] =
        Myo(())
      override def map[A, B](f: A => B): Myo[A] => Myo[B] =
        m => Myo[B](m.v.map(f))
      override def derive[A: Equal]: Equal[Myo[A]] =
        new Equal[Myo[A]] {
          protected def checkEqual(l: Myo[A], r: Myo[A]): Boolean = {
            def run(m: Myo[A]): A = IOs.run(Fibers.block(m.v))
            run(l) == run(r)
          }
        }
    }

  def spec = suite("MonadLawsTest")(
      test("covariant")(checkAllLaws(CovariantLaws)(listGenF, Gen.int)),
      test("identityFlatten")(
          checkAllLaws(IdentityFlattenLaws)(listGenF, Gen.int)
      )
  )
}
