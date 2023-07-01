package kyo

import scala.util._
import scala.util.control._

import kyo.core._
import kyo.scopes._

object tries {

  final class Tries private[tries] extends Effect[Try, Tries] {

    def run[T, S](v: => T > (Tries with S)): Try[T] > S = {
      implicit def handler: Handler[Try, Tries] =
        new Handler[Try, Tries] {
          def pure[T](v: T) =
            Success(v)
          override def handle[T](ex: Throwable) =
            Tries.fail(ex)
          def apply[T, U, S](m: Try[T], f: T => U > (Tries with S)): U > (Tries with S) =
            m match {
              case m: Failure[T] =>
                Tries.get(m.asInstanceOf[Failure[U]])
              case _ =>
                try f(m.asInstanceOf[Success[T]].value)
                catch {
                  case ex if (NonFatal(ex)) =>
                    Tries.fail(ex)
                }
            }
        }
      handle[T, S](Tries(v))
    }

    def fail[T](ex: Throwable): T > Tries =
      suspend(Failure(ex))

    def fail[T](msg: String): T > Tries =
      suspend(Failure(new Exception(msg)))

    def apply[T, S](v: => T > S): T > (Tries with S) = {
      val a: Try[T > S]         = Try(v)
      val b: T > S > Tries      = Tries.get(a)
      val c: T > (Tries with S) = b.flatten
      c
    }

    def get[T, S](v: Try[T] > S): T > (Tries with S) =
      v.map {
        case Success(v) =>
          v
        case _ =>
          suspend(v)
      }
  }
  val Tries = new Tries

  implicit val triesScope: Scopes[Tries] =
    new Scopes[Tries] {
      def sandbox[S1, S2](f: Scopes.Op[S1, S2]) =
        new Scopes.Op[Tries with S1, Tries with (S1 with S2)] {
          def apply[T](v: T > (Tries with S1)) =
            Tries.get(f(Tries.run[T, S1](v)))
        }
    }
}
