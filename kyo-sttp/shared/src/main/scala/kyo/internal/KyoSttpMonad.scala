package kyo.internal

import KyoSttpMonad.M
import kyo.*
import kyo.core.internal.Kyo
import sttp.monad.Canceler
import sttp.monad.MonadAsyncError

class KyoSttpMonad extends MonadAsyncError[M]:

    def map[T, T2](fa: T < Fibers)(f: T => T2): T2 < Fibers =
        fa.map(f)

    def flatMap[T, T2](fa: T < Fibers)(
        f: T => T2 < Fibers
    ): T2 < Fibers =
        fa.flatMap(f)

    protected def handleWrappedError[T](rt: T < Fibers)(
        h: PartialFunction[Throwable, T < Fibers]
    ) =
        IOs.catching(rt) {
            case ex: Fibers.Interrupted =>
                IOs.fail(ex)
            case ex if h.isDefinedAt(ex) =>
                h(ex)
        }

    override def handleError[T](rt: => T < Fibers)(h: PartialFunction[Throwable, T < Fibers]) =
        handleWrappedError(rt)(h)

    def ensure[T](f: T < Fibers, e: => Unit < Fibers) =
        Fibers.initPromise[Unit].map { p =>
            def run =
                Fibers.run(e).map(p.become).unit
            IOs.ensure(run)(f).map(r => p.get.andThen(r))
        }

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
            p.onComplete {
                case r: Fibers.Interrupted =>
                    canceller.cancel()
                case _ =>
            }.andThen(p.get)
        }
end KyoSttpMonad

object KyoSttpMonad extends KyoSttpMonad:
    type M[T] = T < Fibers

    inline given KyoSttpMonad = this
end KyoSttpMonad
