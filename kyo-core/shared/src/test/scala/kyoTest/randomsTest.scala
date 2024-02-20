package kyoTest

import kyo._

class randomsTest extends KyoTest {

  given testRandom: Random = new Random {
    def nextInt         = 10
    def nextInt(n: Int) = Math.min(55, n - 1)
    def nextLong        = 20L
    def nextBoolean     = true
    def nextDouble      = 30d
    def nextFloat       = 40f
    def nextGaussian    = 50d
  }

  "nextInt" in {
    val v = IOs.run(Randoms.let(testRandom)(Randoms.nextInt))
    assert(v == 10)
  }
  "nextInt(n)" in {
    val v = IOs.run(Randoms.let(testRandom)(Randoms.nextInt(42)))
    assert(v == 41)
  }
  "nextLong" in {
    val v = IOs.run(Randoms.let(testRandom)(Randoms.nextLong))
    assert(v == 20L)
  }
  "nextBoolean" in {
    val v = IOs.run(Randoms.let(testRandom)(Randoms.nextBoolean))
    assert(v == true)
  }
  "nextDouble" in {
    val v = IOs.run(Randoms.let(testRandom)(Randoms.nextDouble))
    assert(v == 30d)
  }
  "nextFloat" in {
    val v = IOs.run(Randoms.let(testRandom)(Randoms.nextFloat))
    assert(v == 40f)
  }
  "nextGaussian" in {
    val v = IOs.run(Randoms.let(testRandom)(Randoms.nextGaussian))
    assert(v == 50d)
  }
  "nextValue" in {
    val v = IOs.run(Randoms.let(testRandom)(Randoms.nextValue(List(1, 2))))
    assert(v == 2)
  }
}
