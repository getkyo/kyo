package kyo

import kyo.core._
import kyo.futures._
import kyo.ios._
import zio.Trace
import zio.prelude._
import zio.prelude.coherent.CovariantDeriveEqual
import zio.prelude.coherent.CovariantDeriveEqualIdentityFlatten
import zio.prelude.laws._
import zio.test._
import zio.test.laws._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

object MonadLawsTest extends ZIOSpecDefault {

  type Myo[+T] = T > (IOs | Futures)

  val listGenF: GenF[Any, Myo] =
    new GenF[Any, Myo] {
      def apply[R, A](gen: Gen[R, A])(implicit trace: Trace) =
        Gen.oneOf(
            gen.map(v => (v: A > (IOs | Futures))),
            gen.map(v => IOs(v)),
            gen.map(v => Futures.fork(v)),
            gen.map(v => IOs(Futures.fork(v))),
            gen.map(v => Futures.fork(IOs(v)))
        )
    }

  given CovariantDeriveEqualIdentityFlatten[Myo] =
    new CovariantDeriveEqualIdentityFlatten[Myo] {
      override def flatten[A](ffa: Myo[Myo[A]]): Myo[A] =
        ffa()
      override def any: Myo[Any] =
        ()
      override def map[A, B](f: A => B): Myo[A] => Myo[B] =
        _(f(_))
      override def derive[A: Equal]: Equal[Myo[A]] =
        new Equal[Myo[A]] {
          protected def checkEqual(l: Myo[A], r: Myo[A]): Boolean =
            def run(m: Myo[A]): A = Futures.block((m < IOs)(_.run()), Duration.Inf)
            run(l) == run(r)
        }
    }

  def spec = suite("MonadLawsTest")(
      test("covariant")(checkAllLaws(CovariantLaws)(listGenF, Gen.int)),
      test("identityFlatten")(
          checkAllLaws(IdentityFlattenLaws)(listGenF, Gen.int)
      )
  )
}
