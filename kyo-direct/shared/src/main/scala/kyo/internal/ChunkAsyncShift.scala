package kyo.internal

import cps.CpsMonad
import cps.runtime.IterableOpsAsyncShift
import kyo.*

class ChunkAsyncShift[A](using Frame) extends IterableOpsAsyncShift[A, Chunk, Chunk[A]]:
    override def shiftedFold[F[_], Acc, B, R](
        c: Chunk[A],
        monad: CpsMonad[F]
    )(prolog: Acc, action: A => F[B], acc: (Acc, A, B) => Acc, epilog: Acc => R): F[R] =
        monad match
            case _: KyoCpsMonad[?] =>
                Kyo.foldLeft(c)(prolog)((state, a) => action(a).map(b => acc(state, a, b))).map(s => epilog(s))
    end shiftedFold

    override def shiftedStateFold[F[_], S, R](c: Chunk[A], monad: CpsMonad[F])(prolog: S, acc: (S, A) => F[S], epilog: S => R): F[R] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.foldLeft(c)(prolog)((s, a) => acc(s, a)).map(s => epilog(s))

    override def shiftedWhile[F[_], S, R](
        c: Chunk[A],
        monad: CpsMonad[F]
    )(prolog: S, condition: A => F[Boolean], acc: (S, Boolean, A) => S, epilog: S => R): F[R] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.shiftedWhile(c)(prolog, condition, acc, epilog)

    override def dropWhile[F[_]](c: Chunk[A], monad: CpsMonad[F])(p: A => F[Boolean]): F[Chunk[A]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.dropWhile(c)(a => p(a))

    override def filter[F[_]](c: Chunk[A], monad: CpsMonad[F])(p: A => F[Boolean]): F[Chunk[A]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.filter(c)(a => p(a))

    override def takeWhile[F[_]](c: Chunk[A], monad: CpsMonad[F])(p: (A) => F[Boolean]): F[Chunk[A]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.takeWhile(c)(a => p(a))

    override def map[F[_], B](c: Chunk[A], monad: CpsMonad[F])(f: A => F[B]): F[Chunk[B]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.foreach(c)(a => f(a))

    override def flatMap[F[_], B](c: Chunk[A], monad: CpsMonad[F])(f: A => F[IterableOnce[B]]): F[Chunk[B]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.foreachConcat(c)(a => f(a))

    override def foreach[F[_], U](c: Chunk[A], monad: CpsMonad[F])(f: A => F[U]): F[Unit] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.foreachDiscard(c)(a => f(a))

    private def liftPartial[B, S](pf: PartialFunction[A, B < S]): (A => Maybe[B] < S) =
        a =>
            pf.lift(a) match
                case Some(value) => value.map(v => Maybe(v))
                case None        => Maybe.empty

    override def collect[F[_], B](c: Chunk[A], monad: CpsMonad[F])(pf: PartialFunction[A, F[B]]): F[Chunk[B]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.collect(c)(liftPartial(pf))

    override def filterNot[F[_]](c: Chunk[A], monad: CpsMonad[F])(p: A => F[Boolean]): F[Chunk[A]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.filter(c)(a => p(a).map(!_))

    override def flatten[F[_], B](c: Chunk[A], monad: CpsMonad[F])(implicit asIterable: A => F[IterableOnce[B]]): F[Chunk[B]] =
        monad match
            case _: KyoCpsMonad[?] => flatMap(c, monad)(asIterable)

    override def groupBy[F[_], K](c: Chunk[A], monad: CpsMonad[F])(f: A => F[K]): F[Map[K, Chunk[A]]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.groupBy(c)(f)

    override def groupMap[F[_], K, B](c: Chunk[A], monad: CpsMonad[F])(key: A => F[K])(f: A => F[B]): F[Map[K, Chunk[B]]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.groupMap(c)(key)(f)

    override def partition[F[_]](c: Chunk[A], monad: CpsMonad[F])(p: A => F[Boolean]): F[(Chunk[A], Chunk[A])] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.partition(c)(p)

    override def partitionMap[F[_], A1, A2](c: Chunk[A], monad: CpsMonad[F])(f: A => F[Either[A1, A2]]): F[(Chunk[A1], Chunk[A2])] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.partitionMap(c)(f)

    override def scanLeft[F[_], B](c: Chunk[A], monad: CpsMonad[F])(z: B)(op: (B, A) => F[B]): F[Chunk[B]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.scanLeft(c)(z)(op)

    override def span[F[_]](c: Chunk[A], monad: CpsMonad[F])(p: A => F[Boolean]): F[(Chunk[A], Chunk[A])] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.span(c)(p)

    override def tapEach[F[_], U](c: Chunk[A], monad: CpsMonad[F])(f: A => F[U]): F[Chunk[A]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.foreach(c)(x => f(x).andThen(x))

end ChunkAsyncShift
