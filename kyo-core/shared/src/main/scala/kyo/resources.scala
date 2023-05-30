package kyo

import java.io.Closeable
import scala.util.Try
import scala.util.control.NonFatal

import ios._
import envs._
import sums._

object resources {

  type Resource[T] = Sum[Finalizer]#Value[T]

  type Resources = Sums[Finalizer]

  object Resources {
    def ensure[T](f: => T > IOs): Unit > Resources =
      Sums[Finalizer].add(() => IOs.run(f)).unit

    def acquire[T <: Closeable](resource: => T): T > Resources = {
      lazy val v = resource
      Sums[Finalizer].add(() => v.close()).map(_ => v)
    }

    def run[T, S](v: T > (Resources with S)): T > (IOs with S) =
      Sums[Finalizer].run(v)
  }

  abstract class Finalizer {
    def run(): Unit
  }

  private object Finalizer {
    val noop = new Finalizer {
      def run(): Unit = ()
    }
    implicit val summer: Summer[Finalizer] =
      new Summer[Finalizer] {
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
