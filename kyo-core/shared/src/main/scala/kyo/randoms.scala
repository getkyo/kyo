package kyo

import scala.Console

import kyo.locals.Locals

object randoms {

  abstract class Random {
    def nextInt: Int < IOs
    def nextInt(n: Int): Int < IOs
    def nextLong: Long < IOs
    def nextDouble: Double < IOs
    def nextBoolean: Boolean < IOs
    def nextFloat: Float < IOs
    def nextGaussian: Double < IOs
  }

  object Random {
    val default: Random =
      new Random {

        val random          = new java.util.Random
        val nextInt         = IOs(random.nextInt())
        def nextInt(n: Int) = IOs(random.nextInt(n))
        val nextLong        = IOs(random.nextLong())
        val nextDouble      = IOs(random.nextDouble())
        val nextBoolean     = IOs(random.nextBoolean())
        val nextFloat       = IOs(random.nextFloat())
        val nextGaussian    = IOs(random.nextGaussian())
      }
  }

  object Randoms {

    private val local = Locals.init(Random.default)

    def let[T, S](r: Random)(v: T < S): T < (S with IOs) =
      local.let(r)(v)

    val nextInt: Int < IOs =
      local.get.map(_.nextInt)

    def nextInt[S](n: Int < S): Int < (S with IOs) =
      n.map(n => local.get.map(_.nextInt(n)))

    val nextLong: Long < IOs =
      local.get.map(_.nextLong)

    val nextDouble: Double < IOs =
      local.get.map(_.nextDouble)

    val nextBoolean: Boolean < IOs =
      local.get.map(_.nextBoolean)

    val nextFloat: Float < IOs =
      local.get.map(_.nextFloat)

    val nextGaussian: Double < IOs =
      local.get.map(_.nextGaussian)

    def nextValue[T, S](seq: Seq[T] < S): T < (S with IOs) =
      seq.map(s => nextInt(s.size).map(idx => s(idx)))
  }
}
