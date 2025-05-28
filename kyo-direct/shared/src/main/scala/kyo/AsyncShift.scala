package kyo

import cps.CpsMonad
import cps.runtime.SeqAsyncShift
import directInternal.KyoCpsMonad
import kyo.Kyo.toIndexed
import kyo.kernel.Loop
import kyo.kernel.internal.Safepoint
import scala.collection.SeqOps
import scala.collection.immutable.LinearSeq

object asyncShiftInternal:
    given Frame = Frame.internal

    class KyoSeqAsyncShift[A, C[X] >: Chunk[X] <: Seq[X] & SeqOps[X, C, C[X]], CA <: C[A]] extends SeqAsyncShift[A, C, CA]:

        override def shiftedFold[F[_], Acc, B, R](
            c: CA,
            monad: CpsMonad[F]
        )(prolog: Acc, action: A => F[B], acc: (Acc, A, B) => Acc, epilog: Acc => R): F[R] =
            monad match
                case _: KyoCpsMonad[?] =>
                    Kyo.foldLeft(c)(prolog)((state, a) =>
                        action(a).map(b => acc(state, a, b))
                    ).map(s => epilog(s))
                case _ => super.shiftedFold(c, monad)(prolog, action, acc, epilog)
        end shiftedFold

        override def shiftedStateFold[F[_], S, R](c: CA, monad: CpsMonad[F])(prolog: S, acc: (S, A) => F[S], epilog: S => R): F[R] =
            monad match
                case _: KyoCpsMonad[?] => Kyo.foldLeft(c)(prolog)((s, a) => acc(s, a)).map(s => epilog(s))
                case _                 => super.shiftedStateFold(c, monad)(prolog, acc, epilog)

        override def shiftedWhile[F[_], S, R](
            c: CA,
            monad: CpsMonad[F]
        )(prolog: S, condition: A => F[Boolean], acc: (S, Boolean, A) => S, epilog: S => R): F[R] =
            monad match
                case _: KyoCpsMonad[?] => asyncShiftInternal.shiftedWhile(c)(prolog, condition, acc, epilog)
                case _                 => super.shiftedWhile(c, monad)(prolog, condition, acc, epilog)

        override def dropWhile[F[_]](c: CA, monad: CpsMonad[F])(p: A => F[Boolean]): F[C[A]] =
            monad match
                case _: KyoCpsMonad[?] => Kyo.dropWhile(c)(a => p(a))
                case _                 => super.dropWhile(c, monad)(p)

        override def filter[F[_]](c: CA, monad: CpsMonad[F])(p: A => F[Boolean]): F[C[A]] =
            monad match
                case _: KyoCpsMonad[?] => Kyo.filter(c)(a => p(a))
                case _                 => super.filter(c, monad)(p)

        override def takeWhile[F[_]](c: CA, monad: CpsMonad[F])(p: (A) => F[Boolean]): F[C[A]] =
            monad match
                case _: KyoCpsMonad[?] => Kyo.takeWhile(c)(a => p(a))
                case _                 => super.takeWhile(c, monad)(p)

        override def map[F[_], B](c: CA, monad: CpsMonad[F])(f: A => F[B]): F[C[B]] =
            monad match
                case _: KyoCpsMonad[?] => Kyo.foreach(c)(a => f(a))
                case _                 => super.map(c, monad)(f)

        override def foreach[F[_], U](c: CA, monad: CpsMonad[F])(f: A => F[U]): F[Unit] =
            monad match
                case _: KyoCpsMonad[?] => Kyo.foreachDiscard(c)(a => f(a))
                case _                 => super.foreach(c, monad)(f)
    end KyoSeqAsyncShift

    class SeqToChunkAsyncShift[A] extends KyoSeqAsyncShift[A, Seq, Seq[A]]
    class ChunkAsyncShift[A]      extends KyoSeqAsyncShift[A, Chunk, Chunk[A]]

    transparent inline given shiftedSeqToChunk[A]: SeqToChunkAsyncShift[A] = new SeqToChunkAsyncShift[A]
    transparent inline given shiftedChunk[A]: ChunkAsyncShift[A]           = new ChunkAsyncShift[A]

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
