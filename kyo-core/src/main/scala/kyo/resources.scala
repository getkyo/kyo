package kyo

import java.io.Closeable
import scala.util.Try
import scala.util.control.NonFatal

import core._
import ios._
import frames._
import envs._
import sums._

object resources {

  opaque type Resource[T] = Sum[Finalizer, T]

  opaque type Resources = Sums[Finalizer]

  object Resources {
    def ensure[T](f: => T > IOs): Unit > Resources =
      Sums[Finalizer].add(() => IOs.run(f)).unit

    def acquire[T <: Closeable](resource: => T): T > Resources =
      lazy val v = resource
      Sums[Finalizer].add(() => v.close()).map(_ => v)

    def run[T, S](v: T > (S | Resources)): T > (S | IOs) =
      Sums[Finalizer].drop(v)
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
