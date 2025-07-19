package kyo.internal

import KyoSttpMonad.M
import KyoSttpMonad.given
import kyo.*
import kyo.kernel.Effect
import sttp.monad.Canceler
import sttp.monad.MonadAsyncError

sealed class KyoSttpMonad(using Frame) extends MonadAsyncError[M]:

    def map[A, T2](fa: M[A])(f: A => T2): M[T2] =
        fa.map(v => f(v))

    def flatMap[A, T2](fa: M[A])(f: A => M[T2]): M[T2] =
        fa.map(v => f(v))

    protected def handleWrappedError[A](rt: M[A])(
        h: PartialFunction[Throwable, M[A]]
    ) =
        Effect.catching(rt)(ex => h.applyOrElse(ex, throw _))

    override def handleError[A](rt: => M[A])(h: PartialFunction[Throwable, M[A]]) =
        handleWrappedError(rt)(h)

    def ensure[A](f: M[A], e: => M[Unit]) =
        Promise.initWith[Unit, Any] { p =>
            def run =
                Fiber.initUnscoped(e).map(p.becomeDiscard)
            Sync.ensure(run)(f).map(r => p.get.andThen(r))
        }

    def error[A](t: Throwable) =
        Sync.defer(throw t)

    def unit[A](t: A) =
        t

    override def eval[A](t: => A) =
        Sync.defer(t)

    override def suspend[A](t: => M[A]) =
        Sync.defer(t)

    def async[A](register: (Either[Throwable, A] => Unit) => Canceler): M[A] =
        Sync.Unsafe {
            val p = Promise.Unsafe.init[A, Any]()
            val canceller =
                register {
                    case Left(t)  => p.completeDiscard(Result.panic(t))
                    case Right(t) => p.completeDiscard(Result.succeed(t))
                }
            p.onInterrupt { _ =>
                canceller.cancel()
            }
            p.safe.get
        }

end KyoSttpMonad

object KyoSttpMonad extends KyoSttpMonad(using Frame.internal):
    type M[A] = A < Async

    inline given KyoSttpMonad = this
end KyoSttpMonad
