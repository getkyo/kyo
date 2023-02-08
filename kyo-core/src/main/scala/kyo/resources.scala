package kyo

import core._
import ios._
import frames._
import envs._
import sums._
import java.io.Closeable
import scala.util.control.NonFatal
import scala.util.Try

object resources {

  opaque type Resource[T] = Sum[Finalizer, T]

  opaque type Resources = Sums[Finalizer]

  object Resources {
    def ensure[T](f: => T > IOs): Unit > Resources =
      Sums.add[Finalizer](() => IOs.run(f)).unit

    def acquire[T <: Closeable](resource: => T): T > Resources =
      lazy val v = resource
      Sums.add[Finalizer](() => v.close()).map(_ => v)

    def close[T, S](v: T > (S | Resources)): T > (S | IOs) =
      Sums.drop[Finalizer](v)
  }

  private abstract class Finalizer {
    def run(): Unit
  }

  private object Finalizer {
    val noop = new Finalizer {
      def run(): Unit = ()
    }
    given Summer[Finalizer] with {
      def init = noop
      def add(a: Finalizer, b: Finalizer) =
        () => {
          b.run()
          a.run()
        }
      override def drop(v: Finalizer): Unit > IOs =
        IOs(v.run())
    }
  }
}
