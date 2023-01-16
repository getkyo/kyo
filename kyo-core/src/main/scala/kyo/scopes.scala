package kyo

import core._
import ios._
import java.io.Closeable
import scala.util.control.NonFatal

object scopes {

  private[kyo] sealed trait Thunk[+T] {
    private[scopes] def value(): T
    private[scopes] def close(): Unit > IOs
    def apply(): T > IOs =
      IOs {
        try value()
        finally close()
      }
  }

  opaque type Scope[+T] = T | Thunk[T]

  extension [T](s: Scope[T]) {
    inline def run: T > IOs = s match {
      case t: Thunk[T] @unchecked =>
        t()
      case v =>
        v.asInstanceOf[T > IOs]
    }
    private[kyo] inline def value(): T =
      s match {
        case t: Thunk[T] @unchecked =>
          t.value()
        case v =>
          v.asInstanceOf[T]
      }
    private[kyo] inline def close(): Unit =
      s match {
        case t: Thunk[T] @unchecked =>
          t.close()
        case v =>
          ()
      }
  }

  final class Scopes extends Effect[Scope] {

    inline def ensure[S](inline v: => Unit): Unit > Scopes =
      new Thunk[Unit] {
        def value() = ()
        def close() = v
      } > Scopes

    inline def acquire[T <: Closeable, S](inline resource: => T): T > Scopes =
      new Thunk[T] {
        lazy val r  = resource
        def value() = r
        def close() = r.close()
      } > Scopes

    inline def close[T, S](v: T > (S | Scopes)): T > (S | IOs) =
      (v < Scopes)(_.run)
  }
  val Scopes = new Scopes

  inline given handler: ShallowHandler[Scope, Scopes] with {
    def pure[T](v: T) = v
    override def handle[T](ex: Throwable): T > Scopes =
      new Thunk[T] {
        def value() = throw ex
        def close() = {}
      } > Scopes
    def apply[T, U, S](m: Scope[T], f: T => U > (S | Scopes)): U > (S | Scopes) =
      m match {
        case m: Thunk[T] @unchecked =>
          val v =
            try f(m.value())
            catch {
              case ex if (NonFatal(ex)) =>
                m.close()
                throw ex
            }
          (v < Scopes) { (s: Scope[U]) =>
            new Thunk[U] {
              def value() =
                s.value()
              def close() =
                s.close()
                m.close()
            } > Scopes
          }
        case _ =>
          f(m.asInstanceOf[T])
      }
  }
}
