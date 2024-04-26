package kyo.internal

import kyo.*
import sttp.monad.Canceler
import sttp.monad.MonadAsyncError

object KyoSttpMonad:
    type M[T] = T < Fibers

    given instance: MonadAsyncError[M] =
        new MonadAsyncError[M]:

            def map[T, T2](fa: T < Fibers)(f: T => T2): T2 < Fibers =
                fa.map(f)

            def flatMap[T, T2](fa: T < Fibers)(
                f: T => T2 < Fibers
            ): T2 < Fibers =
                fa.flatMap(f)

            protected def handleWrappedError[T](rt: T < Fibers)(
                h: PartialFunction[Throwable, T < Fibers]
            ) =
                IOs.catching(rt)(h)

            def ensure[T](f: T < Fibers, e: => Unit < Fibers) =
                IOs.ensure(Fibers.run(e).unit)(f)

            def error[T](t: Throwable) =
                IOs.fail(t)

            def unit[T](t: T) =
                t

            override def eval[T](t: => T) =
                IOs[T, Fibers](t)

            override def suspend[T](t: => M[T]) =
                IOs[T, Fibers](t)

            def async[T](register: (Either[Throwable, T] => Unit) => Canceler): M[T] =
                Fibers.initPromise[T].map { p =>
                    val canceller =
                        register {
                            case Left(t)  => discard(p.unsafeComplete(IOs.fail(t)))
                            case Right(t) => discard(p.unsafeComplete(t))
                        }
                    p.onComplete { r =>
                        if r.equals(Fibers.interrupted) then
                            canceller.cancel()
                    }.andThen(p.get)
                }
end KyoSttpMonad
