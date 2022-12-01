package kyo

import kyo.lists.Lists

import java.util.concurrent.TimeoutException
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._

import core._

object futures {

  final class Futures private[futures] () extends Effect[Future] {

    inline def fork[T, S](inline v: T > S)(using ExecutionContext): T > (S | Futures) =
      Future(v) >> Futures

    inline def block[T, S](v: T > (S | Futures), timeout: Duration): T > S =
      val blocker = Blocker(timeout)
      given blockingHandler: ShallowHandler[Future, Futures] =
        new ShallowHandler[Future, Futures] {
          def pure[T](v: T) = Future.successful(v)
          override def handle[T, S](ex: Throwable): T > (S | Futures) =
            Future.failed(ex) > Futures
          def apply[T, U, S](m: Future[T], f: T => U > (S | Futures)) =
            f(blocker(m))
        }
      (v < Futures) { fut =>
        val r = blocker(fut)
        r
      }
  }

  val Futures: Futures = new Futures

  inline given futuresHandler(using ExecutionContext): DeepHandler[Future, Futures] =
    new DeepHandler[Future, Futures] {
      def pure[T](v: T): Future[T] =
        Future.successful(v)
      override def handle[T, S](ex: Throwable): T > (S | Futures) =
        Future.failed(ex) > Futures
      def flatMap[T, U](m: Future[T], f: T => Future[U]): Future[U] =
        m.flatMap(f)
    }

  private sealed trait Blocker {
    def apply[T](fut: Future[T]): T
  }

  private object Blocker {
    private val noTimeout =
      new Blocker {
        def apply[T](fut: Future[T]): T =
          Await.result(fut, Duration.Inf)
      }
    def apply(timeout: Duration): Blocker =
      if (timeout eq Duration.Inf)
        noTimeout
      else
        new Blocker {
          var budgetMs = timeout.toMillis
          def apply[T](fut: Future[T]): T =
            val start = System.currentTimeMillis()
            try Await.result(fut, budgetMs.millis)
            finally budgetMs -= System.currentTimeMillis() - start
        }
  }
}
