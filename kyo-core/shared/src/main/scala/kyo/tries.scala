package kyo

import scala.util._
import scala.util.control._

import core._

object tries {

  final class Tries private[tries] extends Effect[Try, Tries] {

    /*inline(2)*/
    def run[T, S](v: => T > (Tries with S)): Try[T] > S = {
      implicit def handler: Handler[Try, Tries] =
        new Handler[Try, Tries] {
          def pure[T](v: T) =
            Success(v)
          override def handle[T](ex: Throwable) =
            Tries.get(Failure(ex))
          def apply[T, U, S](m: Try[T], f: T => U > (Tries with S)): U > (Tries with S) =
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
      handle[T, S](Tries(v))
    }

    def fail[T](ex: Throwable): T > Tries =
      suspend(Failure(ex))

    def fail[T](msg: String): T > Tries =
      suspend(Failure(new Exception(msg)))

    /*inline(2)*/
    def apply[T, S]( /*inline(2)*/ v: => T > S): T > (Tries with S) = {
      val a: Try[T > S]         = Try(v)
      val b: T > S > Tries      = Tries.get(a)
      val c: T > (Tries with S) = b.flatten
      c
    }

    /*inline(2)*/
    def get[T, S](v: Try[T] > S): T > (Tries with S) =
      v.map {
        case Success(v) =>
          v
        case _ =>
          suspend(v)
      }
  }
  val Tries = new Tries
}
