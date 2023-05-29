package kyo

import scala.util._
import scala.util.control._

import core._

object tries {

  final class Tries private[tries] extends Effect[Try, Tries] {

    /*inline(2)*/
    def run[T, S](v: => T > (Tries & S)): Try[T] > S =
      handle(Tries(v))

    def fail[T](ex: Throwable): T > Tries =
      suspend(Failure(ex))

    def fail[T](msg: String): T > Tries =
      suspend(Failure(new Exception(msg)))

    /*inline(2)*/
    def apply[T, S]( /*inline(2)*/ v: => T > S): T > (Tries & S) =
      val a: Try[Try[T] > S]      = Try(handle(v))
      val b: Try[T] > (Tries & S) = suspend(a).flatten
      val c: T > (Tries & S)      = suspend(b)
      c

    /*inline(2)*/
    def get[T, S](v: Try[T] > S): T > (Tries & S) =
      v.map {
        case Success(v) =>
          v
        case _ =>
          suspend(v)
      }
  }
  val Tries = new Tries

  /*inline(2)*/
  given Handler[Try, Tries] with {
    def pure[T](v: T) =
      Success(v)
    override def handle[T](ex: Throwable) =
      Tries.get(Failure(ex))
    def apply[T, U, S](m: Try[T], f: T => U > (Tries & S)): U > (Tries & S) =
      m match {
        case m: Failure[T] =>
          Tries.get(m.asInstanceOf[Failure[U]])
        case _ =>
          try f(m.asInstanceOf[Success[T]].value)
          catch {
            case ex if (NonFatal(ex)) =>
              Tries.get(Failure(ex))
          }
      }
  }
}
