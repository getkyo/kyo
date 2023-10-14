package kyo.server.internal

import kyo._
import kyo.server._
import kyo.ios._
import kyo.routes._
import kyo.concurrent.fibers._
import kyo.envs._
import kyo.tries._
import sttp.monad.MonadError

object KyoMonadError {
  implicit val monadError: MonadError[kyo.routes.internal.M] =
    new MonadError[kyo.routes.internal.M] {

      def map[T, T2](fa: T > Fibers with IOs)(f: T => T2): T2 > Fibers with IOs =
        fa.map(f)

      def flatMap[T, T2](fa: T > Fibers with IOs)(
          f: T => T2 > Fibers with IOs
      ): T2 > Fibers with IOs =
        fa.flatMap(f)

      protected def handleWrappedError[T](rt: T > Fibers with IOs)(
          h: PartialFunction[Throwable, T > Fibers with IOs]
      ) =
        Tries.handle(rt)(h)

      def ensure[T](f: T > Fibers with IOs, e: => Unit > Fibers with IOs) =
        IOs.ensure(Fibers.run(IOs.runLazy(e)).unit)(f)

      def error[T](t: Throwable) =
        IOs.fail(t)

      def unit[T](t: T) =
        t
    }
}
