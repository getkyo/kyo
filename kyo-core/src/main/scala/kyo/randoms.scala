package kyo

import scala.Console

import core._
import ios._
import envs._

object randoms {

  trait Random {
    def nextInt: Int > IOs
    def nextInt(n: Int): Int > IOs
    def nextLong: Long > IOs
    def nextDouble: Double > IOs
    def nextBoolean: Boolean > IOs
    def nextFloat: Float > IOs
    def nextGaussian: Double > IOs
  }

  object Random {
    given default: Random with {
      val random          = new java.util.Random
      def nextInt         = IOs(random.nextInt())
      def nextInt(n: Int) = IOs(random.nextInt(n))
      def nextLong        = IOs(random.nextLong())
      def nextDouble      = IOs(random.nextDouble())
      def nextBoolean     = IOs(random.nextBoolean())
      def nextFloat       = IOs(random.nextFloat())
      def nextGaussian    = IOs(random.nextGaussian())
    }
  }

  type Randoms = Envs[Random] | IOs

  object Randoms {
    def run[T, S](r: Random)(f: => T > (S | Randoms)): T > (S | IOs) =
      Envs[Random].let(r)(f)
    def run[T, S](f: => T > (S | Randoms))(using c: Random): T > (S | IOs) =
      Envs[Random].let(c)(f)

    def nextInt: Int > Randoms         = Envs[Random].get(_.nextInt)
    def nextInt(n: Int): Int > Randoms = Envs[Random].get(_.nextInt(n))
    def nextLong: Long > Randoms       = Envs[Random].get(_.nextLong)
    def nextDouble: Double > Randoms   = Envs[Random].get(_.nextDouble)
    def nextBoolean: Boolean > Randoms = Envs[Random].get(_.nextBoolean)
    def nextFloat: Float > Randoms     = Envs[Random].get(_.nextFloat)
    def nextGaussian: Double > Randoms = Envs[Random].get(_.nextGaussian)
  }
}
