package kyo.internal

import kyo._
import kyo.ios._
import kyo.concurrent.fibers._
import sttp.monad.MonadAsyncError
import KyoSttpMonad.M
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import sttp.monad.Canceler
import kyo.tries.Tries

object KyoSttpMonad {
  type M[T] = T > Fibers with IOs

  implicit val kyoSttpMonad: MonadAsyncError[M] =
    new MonadAsyncError[M] {

      def map[T, T2](fa: T > (Fibers with IOs))(f: T => T2): T2 > (Fibers with IOs) =
        fa.map(f)

      def flatMap[T, T2](fa: T > Fibers with IOs)(
          f: T => T2 > Fibers with IOs
      ): T2 > Fibers with IOs =
        fa.flatMap(f)

      protected def handleWrappedError[T](rt: T > (Fibers with IOs))(
          h: PartialFunction[Throwable, T > Fibers with IOs]
      ) =
        Tries.handle(rt)(h)

      def ensure[T](f: T > (Fibers with IOs), e: => Unit > (Fibers with IOs)) =
        IOs.ensure(Fibers.run(IOs.runLazy(e)).unit)(f)

      def error[T](t: Throwable) =
        IOs.fail(t)

      def unit[T](t: T) =
        t

      override def eval[T](t: => T) =
        IOs[T, Fibers](t)

      override def suspend[T](t: => M[T]) =
        IOs[T, Fibers](t)

      def async[T](register: (Either[Throwable, T] => Unit) => Canceler): M[T] = {
        Fibers.initPromise[T].map { p =>
          val canceller =
            register {
              case Left(t)  => p.unsafeComplete(IOs.fail(t))
              case Right(t) => p.unsafeComplete(t)
            }
          p.onComplete(_ => canceller.cancel())
            .andThen(p.get)
        }
      }
    }

}
