package kyo.internal

import KyoSttpMonad.M
import kyo.*
import kyo.kernel.Effect
import sttp.monad.Canceler
import sttp.monad.MonadAsyncError

class KyoSttpMonad extends MonadAsyncError[M]:

    def map[T, T2](fa: M[T])(f: T => T2): M[T2] =
        fa.map(v => f(v))

    def flatMap[T, T2](fa: M[T])(f: T => M[T2]): M[T2] =
        fa.map(v => f(v))

    protected def handleWrappedError[T](rt: M[T])(
        h: PartialFunction[Throwable, M[T]]
    ) =
        Effect.catching(rt) {
            case ex if h.isDefinedAt(ex) =>
                h(ex)
            case r =>
                throw r
        }

    override def handleError[T](rt: => M[T])(h: PartialFunction[Throwable, M[T]]) =
        handleWrappedError(rt)(h)

    def ensure[T](f: M[T], e: => M[Unit]) =
        Promise.init[Closed, Unit].map { p =>
            def run =
                Async.run(e).map(p.become).unit
            IO.ensure(run)(f).map(r => p.get.andThen(r))
        }

    def error[T](t: Throwable) =
        IO(throw t)

    def unit[T](t: T) =
        t

    override def eval[T](t: => T) =
        IO(t)

    override def suspend[T](t: => M[T]) =
        IO(t)

    def async[T](register: (Either[Throwable, T] => Unit) => Canceler): M[T] =
        Promise.init[Nothing, T].map { p =>
            val canceller =
                register {
                    case Left(t)  => discard(p.unsafe.complete(Result.panic(t)))
                    case Right(t) => discard(p.unsafe.complete(Result.success(t)))
                }
            p.onComplete { r =>
                if r.isPanic then
                    canceller.cancel()
            }.andThen(p.get)
        }
end KyoSttpMonad

object KyoSttpMonad extends KyoSttpMonad:
    type M[T] = T < (Async & Abort[Closed])

    inline given KyoSttpMonad = this
end KyoSttpMonad
