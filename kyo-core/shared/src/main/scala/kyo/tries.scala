package kyo

import scala.util._
import scala.util.control._

import core._

object tries {

  final class Tries private[tries] extends Effect[Try] {

    /*inline(2)*/
    def run[T, S](v: => T > (Tries & S)): Try[T] > S =
      Tries(v) < Tries

    def fail[T](ex: Throwable): T > Tries =
      Failure(ex) > Tries

    def fail[T](msg: String): T > Tries =
      Failure(new Exception(msg)) > Tries

    /*inline(2)*/
    def apply[T, S]( /*inline(2)*/ v: => T > S): T > (Tries & S) =
      val a: Try[Try[T] > S]      = Try(v < Tries)
      val b: Try[T] > (Tries & S) = (a > Tries).flatten
      val c: T > (Tries & S)      = b > Tries
      c

    /*inline(2)*/
    def get[T, S](v: Try[T] > S): T > (Tries & S) =
      v.map {
        case Success(v) =>
          v
        case _ =>
          v > Tries
      }
  }
  val Tries = new Tries

  /*inline(2)*/
  given Handler[Try, Tries] with {
    def pure[T](v: T) =
      Success(v)
    override def handle[T](ex: Throwable) =
      Failure(ex) > Tries
    def apply[T, U, S](m: Try[T], f: T => U > (Tries & S)): U > (Tries & S) =
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
