package kyo

import scala.Console

import ios._
import envs._

object randoms {

  abstract class Random {
    def nextInt: Int > IOs
    def nextInt(n: Int): Int > IOs
    def nextLong: Long > IOs
    def nextDouble: Double > IOs
    def nextBoolean: Boolean > IOs
    def nextFloat: Float > IOs
    def nextGaussian: Double > IOs
  }

  object Random {
    implicit val default: Random =
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

  type Randoms >: Randoms.Effects <: Randoms.Effects

  object Randoms {

    type Effects = Envs[Random] with IOs

    private val envs = Envs[Random]

    def run[T, S](r: Random)(f: => T > (Randoms with S)): T > (IOs with S) =
      envs.run[T, IOs with S](r)(f)

    def run[T, S](f: => T > (Randoms with S))(implicit r: Random): T > (IOs with S) =
      run[T, S](r)(f)

    val nextInt: Int > Randoms =
      envs.get.map(_.nextInt)

    def nextInt[S](n: Int > S): Int > (S with Randoms) =
      n.map(n => envs.get.map(_.nextInt(n)))

    val nextLong: Long > Randoms =
      envs.get.map(_.nextLong)

    val nextDouble: Double > Randoms =
      envs.get.map(_.nextDouble)

    val nextBoolean: Boolean > Randoms =
      envs.get.map(_.nextBoolean)

    val nextFloat: Float > Randoms =
      envs.get.map(_.nextFloat)

    val nextGaussian: Double > Randoms =
      envs.get.map(_.nextGaussian)

    def nextValue[T, S](seq: Seq[T] > S): T > (S with Randoms) =
      seq.map(s => nextInt(s.size).map(idx => s(idx)))
  }
}
