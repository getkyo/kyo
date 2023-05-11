package kyoTest

import kyo._
import kyo.aspects._
import kyo.ios._
import org.scalatest.compatible.Assertion
import scala.concurrent.Future

class aspectsTest extends KyoTest {

  private def run(v: Assertion > (Aspects | IOs)) = Future.successful(IOs.run(Aspects.run(v)))

  "one aspect" - {
    val aspect       = Aspects.init[Int, Int, IOs]
    def test(v: Int) = aspect(v)(_ + 1)

    "default" in run {
      test(1).map(v => assert(v == 2))
    }

    "with cut" in run {
      val cut = new Cut[Int, Int, IOs] {
        def apply[S1, S2](v: Int > S1)(f: Int => Int > (S2 | Aspects)) =
          v.map(v => f(v + 1))
      }
      aspect.let(cut) {
        test(1).map(v => assert(v == 3))
      }
    }

    "nested cuts" in run {
      val cut1 = new Cut[Int, Int, IOs] {
        def apply[S1, S2](v: Int > S1)(f: Int => Int > (S2 | Aspects)) =
          v.map(v => f(v * 3))
      }
      val cut2 = new Cut[Int, Int, IOs] {
        def apply[S1, S2](v: Int > S1)(f: Int => Int > (S2 | Aspects)) =
          v.map(v => f(v + 5))
      }
      aspect.let(cut1) {
        aspect.let(cut2) {
          test(2).map(v => assert(v == 2 * 3 + 5 + 1))
        }
      }
    }
  }

  "multiple aspects" in run {
    val aspect1 = Aspects.init[Int, Int, IOs]
    val aspect2 = Aspects.init[Int, Int, IOs]

    def test(v: Int) =
      for {
        v1 <- aspect1(v)(_ + 1)
        v2 <- aspect2(v)(_ + 1)
      } yield (v1, v2)

    val cut1 = new Cut[Int, Int, IOs] {
      def apply[S1, S2](v: Int > S1)(f: Int => Int > (S2 | Aspects)) =
        v.map(v => f(v * 3))
    }
    val cut2 = new Cut[Int, Int, IOs] {
      def apply[S1, S2](v: Int > S1)(f: Int => Int > (S2 | Aspects)) =
        v.map(v => f(v + 5))
    }
    aspect1.let(cut1) {
      aspect2.let(cut2) {
        test(2).map(v => assert(v == (2 * 3 + 1, 2 + 5 + 1)))
      }
    }
  }

  "use aspect as a cut" in run {
    val aspect1 = Aspects.init[Int, Int, IOs]
    val aspect2 = Aspects.init[Int, Int, IOs]

    def test(v: Int) =
      for {
        v1 <- aspect1(v)(_ + 1)
        v2 <- aspect2(v)(_ + 1)
      } yield (v1, v2)

    val cut = new Cut[Int, Int, IOs] {
      def apply[S1, S2](v: Int > S1)(f: Int => Int > (S2 | Aspects)) =
        v.map(v => f(v * 3))
    }
    aspect1.let(cut) {
      aspect2.let(aspect1) {
        test(2).map(v => assert(v == (2 * 3 + 1, 2 * 3 + 1)))
      }
    }
  }
}
