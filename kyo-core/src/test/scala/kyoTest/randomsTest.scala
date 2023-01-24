package kyoTest

import kyo.randoms._
import kyo.core._
import kyo.ios._

class randomsTest extends KyoTest {

  given testRandom: Random with {
    def nextInt         = 10
    def nextInt(n: Int) = 11 + n
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
    assert(v == 53)
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
}
