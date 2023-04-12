package kyoTest

import kyo.core._
import kyo.aspects._
import kyo.ios._

class aspectsTest extends KyoTest {

  private def run[T](v: T > (Aspects | IOs)) = IOs.run(Aspects.run(v))

  "one aspect" - {
    val aspect       = Aspects.init[Int, Int, IOs]
    def test(v: Int) = aspect(v)(_ + 1)

    "default" in run {
      test(1)(v => assert(v == 2))
    }

    "with cut" in run {
      val cut = new Cut[Int, Int, IOs] {
        def apply[S1, S2](v: Int > S1)(f: Int => Int > (S2 | Aspects)) =
          v(v => f(v + 1))
      }
      aspect.let(cut) {
        test(1)(v => assert(v == 3))
      }
    }

    "nested cuts" in run {
      val cut1 = new Cut[Int, Int, IOs] {
        def apply[S1, S2](v: Int > S1)(f: Int => Int > (S2 | Aspects)) =
          v(v => f(v * 3))
      }
      val cut2 = new Cut[Int, Int, IOs] {
        def apply[S1, S2](v: Int > S1)(f: Int => Int > (S2 | Aspects)) =
          v(v => f(v + 5))
      }
      aspect.let(cut1) {
        aspect.let(cut2) {
          test(2)(v => assert(v == 2 * 3 + 5 + 1))
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
        v(v => f(v * 3))
    }
    val cut2 = new Cut[Int, Int, IOs] {
      def apply[S1, S2](v: Int > S1)(f: Int => Int > (S2 | Aspects)) =
        v(v => f(v + 5))
    }
    aspect1.let(cut1) {
      aspect2.let(cut2) {
        test(2)(v => assert(v == (2 * 3 + 1, 2 + 5 + 1)))
      }
    }
  }
}
