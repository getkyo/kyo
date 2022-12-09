package kyo

import scala.util._
import scala.util.control._

import core._

object tries {

  final class Tries private[tries] extends Effect[Try] {
    inline def apply[T, S](inline v: => T > S): T > (S | Tries) =
      try v
      catch {
        case ex if(NonFatal(ex)) =>
          Failure(ex) > Tries
      }
  }
  val Tries = new Tries

  inline given ShallowHandler[Try, Tries] =
    new ShallowHandler[Try, Tries] {
      def pure[T](v: T) = Success(v)
      override def handle[T, S](ex: Throwable) = Failure(ex) > Tries
      def apply[T, U, S](m: Try[T], f: T => U > (S | Tries)): U > (S | Tries) =
        m match {
          case m: Failure[T] =>
            m.asInstanceOf[Failure[U]] > Tries
          case _ =>
            f(m.asInstanceOf[Success[T]].value)
        }
    }
}
