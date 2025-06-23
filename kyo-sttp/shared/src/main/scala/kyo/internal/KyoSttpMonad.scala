package kyo.internal

import KyoSttpMonad.M
import KyoSttpMonad.given
import kyo.*
import kyo.kernel.Effect
import sttp.monad.Canceler
import sttp.monad.MonadAsyncError

class KyoSttpMonad extends MonadAsyncError[M]:

    def map[A, T2](fa: M[A])(f: A => T2): M[T2] =
        fa.map(v => f(v))

    def flatMap[A, T2](fa: M[A])(f: A => M[T2]): M[T2] =
        fa.map(v => f(v))

    protected def handleWrappedError[A](rt: M[A])(
        h: PartialFunction[Throwable, M[A]]
    ) =
        Effect.catching(rt) {
            case ex if h.isDefinedAt(ex) =>
                h(ex)
            case r =>
                throw r
        }

    override def handleError[A](rt: => M[A])(h: PartialFunction[Throwable, M[A]]) =
        handleWrappedError(rt)(h)

    def ensure[A](f: M[A], e: => M[Unit]) =
        Promise.initWith[Nothing, Unit] { p =>
            def run =
                Async.run(e).map(p.become).unit
            Sync.ensure(run)(f).map(r => p.get.andThen(r))
        }

    def error[A](t: Throwable) =
        Sync(throw t)

    def unit[A](t: A) =
        t

    override def eval[A](t: => A) =
        Sync(t)

    override def suspend[A](t: => M[A]) =
        Sync(t)

    def async[A](register: (Either[Throwable, A] => Unit) => Canceler): M[A] =
        Sync.Unsafe {
            val p = Promise.Unsafe.init[Nothing, A]()
            val canceller =
                register {
                    case Left(t)  => discard(p.complete(Result.panic(t)))
                    case Right(t) => discard(p.complete(Result.succeed(t)))
                }
            p.onInterrupt { _ =>
                canceller.cancel()
            }
            p.safe.get
        }

end KyoSttpMonad

object KyoSttpMonad extends KyoSttpMonad:
    type M[A] = A < Async

    inline given KyoSttpMonad = this
    given Frame               = Frame.internal
end KyoSttpMonad
