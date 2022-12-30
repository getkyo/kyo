package kyo

import core._
import ios._
import java.io.Closeable
import scala.util.control.NonFatal

object scopes {

  sealed trait Scope[T] {
    private[scopes] def value(): T
    private[scopes] def close(): Unit > IOs
    def apply(): T > IOs =
      IOs {
        try value()
        finally close()
      }
  }
  final class Scopes extends Effect[Scope] {

    inline def ensure[S](inline v: => Unit): Unit > Scopes =
      new Scope[Unit] {
        def value() = ()
        def close() = v
      } > Scopes

    inline def acquire[T <: Closeable, S](inline resource: => T): T > Scopes =
      new Scope[T] {
        lazy val r  = resource
        def value() = r
        def close() = r.close()
      } > Scopes

    inline def close[T, S](v: T > (S | Scopes)): T > (S | IOs) =
      (v < Scopes)(_())
  }
  val Scopes = new Scopes

  given handler: ShallowHandler[Scope, Scopes] =
    new ShallowHandler[Scope, Scopes] {
      def pure[T](v: T) =
        new Scope[T] {
          def value() = v
          def close() = {}
        }
      override def handle[T](ex: Throwable): T > Scopes =
        new Scope[T] {
          def value() = throw ex
          def close() = {}
        } > Scopes
      def apply[T, U, S](m: Scope[T], f: T => U > (S | Scopes)): U > (S | Scopes) =
        val v =
          try f(m.value())
          catch {
            case ex if (NonFatal(ex)) =>
              m.close()
              throw ex
          }
        (v < Scopes) { s =>
          new Scope[U] {
            def value() =
              s.value()
            def close() =
              s.close()
              m.close()
          } > Scopes
        }
    }
}
