package kyo

import cps.CpsMonad
import cps.runtime.IterableOpsAsyncShift
import directInternal.KyoCpsMonad
import kyo.Kyo.toIndexed
import kyo.kernel.Loop
import kyo.kernel.internal.Safepoint
import scala.collection.IterableOps
import scala.collection.immutable.LinearSeq

trait asyncShiftInternalLowPriorityImplicit1:
    transparent inline given shiftedIterableOps[A, C[X] <: Iterable[X] & IterableOps[X, C, C[X]]]
        : asyncShiftInternal.KyoSeqAsyncShift[A, C, C[A]] =
        asyncShiftInternal.KyoSeqAsyncShift[A, C, C[A]]()
end asyncShiftInternalLowPriorityImplicit1

object asyncShiftInternal extends asyncShiftInternalLowPriorityImplicit1:
    given Frame = Frame.internal

    transparent inline given shiftedChunk[A]: ChunkAsyncShift[A] = new ChunkAsyncShift[A]

    class ChunkAsyncShift[A] extends KyoSeqAsyncShift[A, Chunk, Chunk[A]]

    class KyoSeqAsyncShift[A, C[X] <: Iterable[X] & IterableOps[X, C, C[X]], CA <: C[A]] extends IterableOpsAsyncShift[A, C, CA]:

        extension [S, X](chunk: Chunk[X] < S)
            def resultInto(c: CA): C[X] < S = chunk.map(ch =>
                c.iterableFactory.from(ch)
            )
        end extension

        override def shiftedFold[F[_], Acc, B, R](
            c: CA,
            monad: CpsMonad[F]
        )(prolog: Acc, action: A => F[B], acc: (Acc, A, B) => Acc, epilog: Acc => R): F[R] =
            monad match
                case _: KyoCpsMonad[?] =>
                    Kyo.foldLeft(c)(prolog)((state, a) =>
                        action(a).map(b => acc(state, a, b))
                    ).map(s => epilog(s))
        end shiftedFold

        override def shiftedStateFold[F[_], S, R](c: CA, monad: CpsMonad[F])(prolog: S, acc: (S, A) => F[S], epilog: S => R): F[R] =
            monad match
                case _: KyoCpsMonad[?] => Kyo.foldLeft(c)(prolog)((s, a) => acc(s, a)).map(s => epilog(s))

        override def shiftedWhile[F[_], S, R](
            c: CA,
            monad: CpsMonad[F]
        )(prolog: S, condition: A => F[Boolean], acc: (S, Boolean, A) => S, epilog: S => R): F[R] =
            monad match
                case _: KyoCpsMonad[?] => asyncShiftInternal.shiftedWhile(c)(prolog, condition, acc, epilog)

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
    end KyoSeqAsyncShift

    private[kyo] def shiftedWhile[A, S, B, C](source: IterableOnce[A])(
        prolog: B,
        f: Safepoint ?=> A => Boolean < S,
        acc: (B, Boolean, A) => B,
        epilog: B => C
    )(using Frame, Safepoint): C < S =
        source.knownSize match
            case 0 => epilog(prolog)
            case _ =>
                source match
                    case linearSeq: LinearSeq[A] => Loop(linearSeq, prolog): (seq, b) =>
                            if seq.isEmpty then Loop.done(epilog(b))
                            else
                                val curr = seq.head
                                f(curr).map:
                                    case true  => Loop.continue(seq.tail, acc(b, true, curr))
                                    case false => Loop.done(epilog(acc(b, false, curr)))

                    case other =>
                        val indexedSeq = toIndexed(other)
                        val size       = indexedSeq.length
                        Loop.indexed(prolog): (idx, b) =>
                            if idx == size then Loop.done(epilog(b))
                            else
                                val curr = indexedSeq(idx)
                                f(curr).map:
                                    case true  => Loop.continue(acc(b, true, curr))
                                    case false => Loop.done(epilog(acc(b, false, curr)))

        end match
    end shiftedWhile
end asyncShiftInternal
