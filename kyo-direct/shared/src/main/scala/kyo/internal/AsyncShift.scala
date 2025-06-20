package kyo.internal

import cps.AsyncShift
import cps.CpsMonad
import cps.runtime.IterableOpsAsyncShift
import kyo.*
import kyo.kernel.internal.Safepoint
import scala.annotation.targetName
import scala.collection.IterableOps

trait asyncShiftLowPriorityImplicit1:

    transparent inline given shiftedIterableOps[A, C[X] <: Iterable[X] & IterableOps[X, C, C[X]]]
        : KyoSeqAsyncShift[A, C, C[A]] = KyoSeqAsyncShift[A, C, C[A]]()

end asyncShiftLowPriorityImplicit1

object asyncShift extends asyncShiftLowPriorityImplicit1:

    transparent inline given shiftedChunk[A]: ChunkAsyncShift[A] = new ChunkAsyncShift[A]
    transparent inline given shiftedMaybe: MaybeAsyncShift       = new MaybeAsyncShift
    transparent inline given shiftedResult: ResultAsyncShift     = new ResultAsyncShift

end asyncShift

class ResultAsyncShift(using Frame) extends AsyncShift[Result.type]:

    def map[F[_], E, A](result: Result.type, monad: CpsMonad[F])(ma: Result[E, A])[B](f: A => F[B]): F[Result[E, B]] =
        monad match
            case _: KyoCpsMonad[?] => ma match
                    case Result.Success(a)             => f(a).map(Result.Success(_))
                    case e: Result.Error[E] @unchecked => monad.pure(e)

    def flatMap[F[_], E, A](
        result: Result.type,
        monad: CpsMonad[F]
    )(ma: Result[E, A])[E2, B](f: A => F[Result[E2, B]]): F[Result[E | E2, B]] =
        monad match
            case _: KyoCpsMonad[?] => ma match
                    case Result.Success(a)             => f(a)
                    case e: Result.Error[E] @unchecked => monad.pure(e)

    def exists[F[_], E, A](result: Result.type, monad: CpsMonad[F])(ma: Result[E, A])(p: A => F[Boolean]): F[Boolean] =
        monad match
            case _: KyoCpsMonad[?] => ma match
                    case Result.Success(a)  => p(a)
                    case _: Result.Error[?] => monad.pure(false)

    def forall[F[_], E, A](result: Result.type, monad: CpsMonad[F])(ma: Result[E, A])(p: A => F[Boolean]): F[Boolean] =
        monad match
            case _: KyoCpsMonad[?] => ma match
                    case Result.Success(a)  => p(a)
                    case _: Result.Error[?] => monad.pure(true)

    def filter[F[_], E, A](
        result: Result.type,
        monad: CpsMonad[F]
    )(ma: Result[E, A])(p: A => F[Boolean]): F[Result[E | NoSuchElementException, A]] =
        monad match
            case _: KyoCpsMonad[?] => ma match
                    case Result.Success(a) =>
                        p(a).map:
                            case true  => Result.Success(a)
                            case false => Result.Failure(defaultFilterError)
                    case e: Result.Error[E] @unchecked => monad.pure(e)

    def filterNot[F[_], E, A](
        result: Result.type,
        monad: CpsMonad[F]
    )(ma: Result[E, A])(p: A => F[Boolean]): F[Result[E | NoSuchElementException, A]] =
        filter(result, monad)(ma)(a => monad.map(p(a))(b => !b))

    def fold[F[_], E, A](
        result: Result.type,
        monad: CpsMonad[F]
    )(
        ma: Result[E, A]
    )[B](
        onSuccess: A => F[B],
        onFailure: E => F[B],
        onPanic: Throwable => F[B]
    ): F[B] =
        monad match
            case _: KyoCpsMonad[?] => ma match
                    case Result.Success(a) => onSuccess(a)
                    case Result.Failure(e) => onFailure(e)
                    case Result.Panic(ex)  => onPanic(ex)

    def orElse[F[_], E, A](
        result: Result.type,
        monad: CpsMonad[F]
    )(ma: Result[E, A])[E2, B >: A](ifEmpty: () => F[Result[E2, B]]): F[Result[E | E2, B]] =
        monad match
            case _: KyoCpsMonad[?] => ma match
                    case Result.Success(a)  => monad.pure(Result.Success(a))
                    case _: Result.Error[?] => ifEmpty()

    def getOrElse[F[_], E, A](result: Result.type, monad: CpsMonad[F])(ma: Result[E, A])[B >: A](ifEmpty: () => F[B]): F[B] =
        monad match
            case _: KyoCpsMonad[?] => ma match
                    case Result.Success(a)  => monad.pure(a)
                    case _: Result.Error[?] => ifEmpty()

    private def defaultFilterError: NoSuchElementException =
        new NoSuchElementException("filter predicate failed")

end ResultAsyncShift

class MaybeAsyncShift(using Frame) extends AsyncShift[Maybe.type]:
    def map[F[_], A](maybe: Maybe.type, monad: CpsMonad[F])(ma: Maybe[A])[B](f: A => F[B]): F[Maybe[B]] =
        monad match
            case _: KyoCpsMonad[?] => ma match
                    case Maybe.Present(a) => f(a).map(Maybe.Present.apply)
                    case Maybe.Absent     => monad.pure(Maybe.Absent)

    def flatMap[F[_], A](maybe: Maybe.type, monad: CpsMonad[F])(ma: Maybe[A])[B](f: A => F[Maybe[B]]): F[Maybe[B]] =
        monad match
            case _: KyoCpsMonad[?] => ma match
                    case Maybe.Present(a) => f(a)
                    case Maybe.Absent     => monad.pure(Maybe.Absent)

    def filter[F[_], A](maybe: Maybe.type, monad: CpsMonad[F])(ma: Maybe[A])(p: A => F[Boolean]): F[Maybe[A]] =
        monad match
            case _: KyoCpsMonad[?] => ma match
                    case Maybe.Present(a) => p(a).map(if _ then Maybe.Present(a) else Maybe.Absent)
                    case Maybe.Absent     => monad.pure(Maybe.Absent)

    def exists[F[_], A](maybe: Maybe.type, monad: CpsMonad[F])(ma: Maybe[A])(p: A => F[Boolean]): F[Boolean] =
        monad match
            case _: KyoCpsMonad[?] => ma match
                    case Maybe.Present(a) => p(a)
                    case Maybe.Absent     => monad.pure(false)

    def forall[F[_], A](maybe: Maybe.type, monad: CpsMonad[F])(ma: Maybe[A])(p: A => F[Boolean]): F[Boolean] =
        monad match
            case _: KyoCpsMonad[?] => ma match
                    case Maybe.Present(a) => p(a)
                    case Maybe.Absent     => monad.pure(true)

    def filterNot[F[_], A](maybe: Maybe.type, monad: CpsMonad[F])(ma: Maybe[A])(p: A => F[Boolean]): F[Maybe[A]] =
        monad match
            case _: KyoCpsMonad[?] => filter(maybe, monad)(ma)(a => monad.map(p(a))(i => !i))

    def foreach[F[_], A](maybe: Maybe.type, monad: CpsMonad[F])(ma: Maybe[A])(f: A => F[Unit]): F[Unit] =
        monad match
            case _: KyoCpsMonad[?] => ma match
                    case Maybe.Present(a) => f(a)
                    case Maybe.Absent     => Kyo.unit

    def collect[F[_], A](maybe: Maybe.type, monad: CpsMonad[F])(ma: Maybe[A])[B](pf: PartialFunction[A, F[B]]): F[Maybe[B]] =
        monad match
            case _: KyoCpsMonad[?] => ma match
                    case Maybe.Present(a) if pf.isDefinedAt(a) =>
                        monad.flatMap(pf(a))(b => monad.pure(Maybe.Present(b)))
                    case _ => monad.pure(Maybe.Absent)

    def fold[F[_], A](maybe: Maybe.type, monad: CpsMonad[F])(ma: Maybe[A])[B](ifEmpty: () => F[B])(f: A => F[B]): F[B] =
        monad match
            case _: KyoCpsMonad[?] => ma match
                    case Maybe.Present(a) => f(a)
                    case Maybe.Absent     => ifEmpty()

    def orElse[F[_], A](maybe: Maybe.type, monad: CpsMonad[F])(ma: Maybe[A])(ifEmpty: () => F[Maybe[A]]): F[Maybe[A]] =
        monad match
            case _: KyoCpsMonad[?] => ma match
                    case Maybe.Present(a) => monad.pure(Maybe.Present(a))
                    case Maybe.Absent     => ifEmpty()

    def getOrElse[F[_], A](maybe: Maybe.type, monad: CpsMonad[F])(ma: Maybe[A])[B >: A](ifEmpty: () => F[B]): F[B] =
        monad match
            case _: KyoCpsMonad[?] => ma match
                    case Maybe.Present(a) => monad.pure(a)
                    case Maybe.Absent     => ifEmpty()

end MaybeAsyncShift

class ChunkAsyncShift[A](using Frame) extends KyoSeqAsyncShift[A, Chunk, Chunk[A]]

class KyoSeqAsyncShift[A, C[X] <: Iterable[X] & IterableOps[X, C, C[X]], CA <: C[A]](using Frame)
    extends IterableOpsAsyncShift[A, C, CA]:

    extension [S, X](chunk: Chunk[X] < S)
        def resultInto(c: CA): C[X] < S = chunk.map(ch => c.iterableFactory.from(ch))
    end extension

    extension [S, X, Y](chunks: (Chunk[X], Chunk[Y]) < S)
        def resultInto(c: CA): (C[X], C[Y]) < S = chunks.map((x, y) => (c.iterableFactory.from(x), c.iterableFactory.from(y)))

    extension [S, K, V](chunks: Map[K, Chunk[V]] < S)
        @targetName("resultIntoMap")
        def resultInto(c: CA): Map[K, C[V]] < S = chunks.map(m => m.view.mapValues(ch => c.iterableFactory.from(ch)).toMap)

    override def shiftedFold[F[_], Acc, B, R](
        c: CA,
        monad: CpsMonad[F]
    )(prolog: Acc, action: A => F[B], acc: (Acc, A, B) => Acc, epilog: Acc => R): F[R] =
        monad match
            case _: KyoCpsMonad[?] =>
                Kyo.foldLeft(c)(prolog)((state, a) => action(a).map(b => acc(state, a, b))).map(s => epilog(s))
    end shiftedFold

    override def shiftedStateFold[F[_], S, R](c: CA, monad: CpsMonad[F])(prolog: S, acc: (S, A) => F[S], epilog: S => R): F[R] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.foldLeft(c)(prolog)((s, a) => acc(s, a)).map(s => epilog(s))

    override def shiftedWhile[F[_], S, R](
        c: CA,
        monad: CpsMonad[F]
    )(prolog: S, condition: A => F[Boolean], acc: (S, Boolean, A) => S, epilog: S => R): F[R] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.shiftedWhile(c)(prolog, condition, acc, epilog)

    override def dropWhile[F[_]](c: CA, monad: CpsMonad[F])(p: A => F[Boolean]): F[C[A]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.dropWhile(c)(a => p(a)).resultInto(c)

    override def filter[F[_]](c: CA, monad: CpsMonad[F])(p: A => F[Boolean]): F[C[A]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.filter(c)(a => p(a)).resultInto(c)

    override def takeWhile[F[_]](c: CA, monad: CpsMonad[F])(p: (A) => F[Boolean]): F[C[A]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.takeWhile(c)(a => p(a)).resultInto(c)

    override def map[F[_], B](c: CA, monad: CpsMonad[F])(f: A => F[B]): F[C[B]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.foreach(c)(a => f(a)).resultInto(c)
    end map

    override def flatMap[F[_], B](c: CA, monad: CpsMonad[F])(f: A => F[IterableOnce[B]]): F[C[B]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.foreachConcat(c)(a => f(a)).resultInto(c)

    override def foreach[F[_], U](c: CA, monad: CpsMonad[F])(f: A => F[U]): F[Unit] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.foreachDiscard(c)(a => f(a))

    private def liftPartial[B, S](pf: PartialFunction[A, B < S]): (A => Maybe[B] < S) =
        a =>
            pf.lift(a) match
                case Some(value) => value.map(v => Maybe(v))
                case None        => Maybe.empty

    override def collect[F[_], B](c: CA, monad: CpsMonad[F])(pf: PartialFunction[A, F[B]]): F[C[B]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.collect(c)(liftPartial(pf)).resultInto(c)

    override def filterNot[F[_]](c: CA, monad: CpsMonad[F])(p: A => F[Boolean]): F[C[A]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.filter(c)(a => p(a).map(!_)).resultInto(c)

    override def flatten[F[_], B](c: CA, monad: CpsMonad[F])(implicit asIterable: A => F[IterableOnce[B]]): F[C[B]] =
        monad match
            case _: KyoCpsMonad[?] => flatMap(c, monad)(asIterable)

    override def groupBy[F[_], K](c: CA, monad: CpsMonad[F])(f: A => F[K]): F[Map[K, C[A]]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.groupBy(c)(f).resultInto(c)

    override def groupMap[F[_], K, B](c: CA, monad: CpsMonad[F])(key: A => F[K])(f: A => F[B]): F[Map[K, C[B]]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.groupMap(c)(key)(f).resultInto(c)

    override def partition[F[_]](c: CA, monad: CpsMonad[F])(p: A => F[Boolean]): F[(C[A], C[A])] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.partition(c)(p).resultInto(c)

    override def partitionMap[F[_], A1, A2](c: CA, monad: CpsMonad[F])(f: A => F[Either[A1, A2]]): F[(C[A1], C[A2])] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.partitionMap(c)(f).resultInto(c)

    override def scanLeft[F[_], B](c: CA, monad: CpsMonad[F])(z: B)(op: (B, A) => F[B]): F[C[B]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.scanLeft(c)(z)(op).resultInto(c)

    override def span[F[_]](c: CA, monad: CpsMonad[F])(p: A => F[Boolean]): F[(C[A], C[A])] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.span(c)(p).resultInto(c)

    override def tapEach[F[_], U](c: CA, monad: CpsMonad[F])(f: A => F[U]): F[C[A]] =
        monad match
            case _: KyoCpsMonad[?] => Kyo.foreach(c)(x => f(x).andThen(x)).resultInto(c)

end KyoSeqAsyncShift
