package kyo.directInternal

import cps.CpsMonad
import cps.CpsMonadContext
import kyo.*
import kyo.kernel.internal.Safepoint

class KyoCpsMonad[S]
    extends CpsMonadContext[[A] =>> A < S]
    with CpsMonad[[A] =>> A < S]
    with Serializable:

    given Frame = Frame.internal

    type Context = KyoCpsMonad[S]

    override def monad: CpsMonad[[A] =>> A < S] = this

    override def apply[A](op: Context => A < S): A < S = op(this)

    override def pure[A](t: A): A < S = t

    override def map[A, B](fa: A < S)(f: A => B): B < S = flatMap(fa)(f)

    override def flatMap[A, B](fa: A < S)(f: A => B < S): B < S = fa.flatMap(f)

end KyoCpsMonad
