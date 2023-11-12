package kyoTest

import kyo._
import kyo.ios._
import kyo.randoms._

class randomsTest extends KyoTest {

  implicit def testRandom: Random = new Random {
    def nextInt         = 10
    def nextInt(n: Int) = Math.min(55, n - 1)
    def nextLong        = 20L
    def nextBoolean     = true
    def nextDouble      = 30d
    def nextFloat       = 40f
    def nextGaussian    = 50d
  }

  "nextInt" in {
    val v = IOs.run(Randoms.run(Randoms.nextInt))
    assert(v == 10)
  }
  "nextInt(n)" in {
    val v = IOs.run(Randoms.run(Randoms.nextInt(42)))
    assert(v == 41)
  }
  "nextLong" in {
    val v = IOs.run(Randoms.run(Randoms.nextLong))
    assert(v == 20L)
  }
  "nextBoolean" in {
    val v = IOs.run(Randoms.run(Randoms.nextBoolean))
    assert(v == true)
  }
  "nextDouble" in {
    val v = IOs.run(Randoms.run(Randoms.nextDouble))
    assert(v == 30d)
  }
  "nextFloat" in {
    val v = IOs.run(Randoms.run(Randoms.nextFloat))
    assert(v == 40f)
  }
  "nextGaussian" in {
    val v = IOs.run(Randoms.run(Randoms.nextGaussian))
    assert(v == 50d)
  }
  "nextValue" in {
    val v = IOs.run(Randoms.run(Randoms.nextValue(List(1, 2))))
    assert(v == 2)
  }
}
