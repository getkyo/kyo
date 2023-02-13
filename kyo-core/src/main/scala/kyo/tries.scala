package kyo

import scala.util._
import scala.util.control._

import core._

object tries {

  final class Tries private[tries] extends Effect[Try] {
    /*inline(2)*/
    def apply[T, S]( /*inline(2)*/ v: => T > S): T > (S | Tries) =
      val a: Try[Try[T] > S]      = Try(v < Tries)
      val b: Try[T] > (S | Tries) = a >> Tries
      val c: T > (S | Tries)      = b > Tries
      c
  }
  val Tries = new Tries

  /*inline(2)*/
  given Handler[Try, Tries] with {
    def pure[T](v: T) =
      Success(v)
    override def handle[T](ex: Throwable) =
      Failure(ex) > Tries
    def apply[T, U, S](m: Try[T], f: T => U > (S | Tries)): U > (S | Tries) =
      m match {
        case m: Failure[T] =>
          m.asInstanceOf[Failure[U]] > Tries
        case _ =>
          try f(m.asInstanceOf[Success[T]].value)
          catch {
            case ex if (NonFatal(ex)) =>
              Failure(ex) > Tries
          }
      }
  }
}
