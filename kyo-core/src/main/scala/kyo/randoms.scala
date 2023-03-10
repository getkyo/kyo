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

  type Randoms = Envs[Random]

  object Randoms {
    def run[T, S](r: Random)(f: => T > (S | Randoms)): T > S =
      Envs.let(r)(f)
    def run[T, S](f: => T > (S | Randoms))(using c: Random): T > S =
      Envs.let(c)(f)

    def nextInt: Int > (Randoms | IOs)         = Envs[Random](_.nextInt)
    def nextInt(n: Int): Int > (Randoms | IOs) = Envs[Random](_.nextInt(n))
    def nextLong: Long > (Randoms | IOs)       = Envs[Random](_.nextLong)
    def nextDouble: Double > (Randoms | IOs)   = Envs[Random](_.nextDouble)
    def nextBoolean: Boolean > (Randoms | IOs) = Envs[Random](_.nextBoolean)
    def nextFloat: Float > (Randoms | IOs)     = Envs[Random](_.nextFloat)
    def nextGaussian: Double > (Randoms | IOs) = Envs[Random](_.nextGaussian)
  }
}
