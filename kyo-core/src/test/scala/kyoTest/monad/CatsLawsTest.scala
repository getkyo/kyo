package kyo

import cats._
import cats.laws.discipline.MonadTests
import cats.syntax.all._
import kyo._
import kyo.core._
import kyo.futures._
import kyo.ios._
import org.scalacheck.Test.Parameters
import org.scalacheck._
import org.scalatest.Ignore
import org.scalatest.freespec.AnyFreeSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

// @Ignore // a bit of mystery for the monadic zealots ;)
class CatsLawsTest extends AnyFreeSpec {

  given [S]: Monad[[T] =>> T > (IOs | S)] =
    new Monad[[T] =>> T > (IOs | S)] {

      override def flatMap[A, B](fa: A > (IOs | S))(f: A => B > (IOs | S)) =
        fa(f)

      override def tailRecM[A, B](a: A)(
          f: A => Either[A, B] > (IOs | S)
      ): B > (IOs | S) =
        def loop(a: A): B > (IOs | S) =
          IOs {
            f(a) {
              case Left(a)  => loop(a)
              case Right(b) => b
            }
          }
        loop(a)

      override def pure[A](x: A): A > (IOs | S) =
        x
    }

  given [A: Eq]: Eq[A > (IOs | Futures)] =
    Eq.by(v => Futures.block((v < IOs)(_.run()), Duration.Inf): A)

  given [A: Arbitrary]: Arbitrary[A > (IOs | Futures)] =
    Arbitrary(
        Gen.oneOf(
            Arbitrary.arbitrary[A].map(v => (v: A > (IOs | Futures))),
            Arbitrary.arbitrary[A].map(v => IOs(v)),
            Arbitrary.arbitrary[A].map(v => Futures.fork(v)),
            Arbitrary.arbitrary[A].map(v => IOs(Futures.fork(v))),
            Arbitrary.arbitrary[A].map(v => Futures.fork(IOs(v)))
        )
    )

  val rs = MonadTests[[T] =>> T > (IOs | Futures)].monad[Int, Int, Int]

  Test
    .checkProperties(Parameters.default, rs.all)
    .foreach { case (name, result) =>
      name in {
        assert(result.passed)
      }
    }
}
