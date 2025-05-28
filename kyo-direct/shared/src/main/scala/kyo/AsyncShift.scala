package kyo

import cps.CpsMonad
import cps.runtime.IndexedSeqAsyncShift
import cps.runtime.SeqAsyncShift
import directInternal.KyoCpsMonad
import kyo.Kyo.toIndexed
import kyo.kernel.Loop
import kyo.kernel.internal.Safepoint
import scala.collection.IndexedSeq
import scala.collection.IndexedSeqOps
import scala.collection.SeqOps
import scala.collection.immutable.LinearSeq
import scala.collection.mutable

trait asyncShiftInternalLowPriorityImplicit1:

    transparent inline given shiftedSeqOps[A, C[X] <: Seq[X] & SeqOps[X, C, C[X]]]
        : SeqAsyncShift[A, C, C[A]] = asyncShiftInternal.KyoSeqAsyncShift[A, C, C[A]]()

end asyncShiftInternalLowPriorityImplicit1

object asyncShiftInternal extends asyncShiftInternalLowPriorityImplicit1:
    given Frame = Frame.internal

    /* transparent inline given shifedIndexedSeqToChunk[A]: KyoIndexedSeqAsyncShift[A, IndexedSeq, IndexedSeq[A]] =
        new KyoIndexedSeqAsyncShift[A, IndexedSeq, IndexedSeq[A]]*/
    transparent inline given shiftedSeqToChunk[A]: SeqAsyncShift[A, Seq, Seq[A]] = new SeqToChunkAsyncShift[A]
    transparent inline given shiftedChunk[A]: ChunkAsyncShift[A]                 = new ChunkAsyncShift[A]

    class SeqToChunkAsyncShift[A] extends KyoSeqAsyncShift[A, Seq, Seq[A]]:
        extension [S, X](chunk: Chunk[X] < S) override def resultInto(c: Seq[A]): Seq[X] < S = chunk

    class ChunkAsyncShift[A] extends KyoSeqAsyncShift[A, Chunk, Chunk[A]]:
        extension [S, X](chunk: Chunk[X] < S) override def resultInto(c: Chunk[A]): Chunk[X] < S = chunk
    // class IndexedSeqToChunkAsyncShift[A] extends KyoIndexedSeqAsyncShift[A, IndexedSeq, IndexedSeq[A]]

    /*
    class KyoIndexedSeqAsyncShift[A, C[X] <: IndexedSeq[X] & IndexedSeqOps[X, C, C[X]], CA <: C[A]]
        extends KyoSeqAsyncShift[A, C, CA]:

        override def indexWhere[F[_]](c: CA, m: CpsMonad[F])(p: A => F[Boolean], from: Int): F[Int] =
            def run(n: Int): F[Int] =
                if n < c.length then
                    m.flatMap(p(c(n))) { c =>
                        if c then m.pure(n)
                        else run(n + 1)
                    }
                else m.pure(-1)

            run(0)
        end indexWhere

        override def indexWhere[F[_]](c: CA, m: CpsMonad[F])(p: A => F[Boolean]): F[Int] =
            indexWhere(c, m)(p, 0)

        override def segmentLength[F[_]](c: CA, m: CpsMonad[F])(p: A => F[Boolean], from: Int): F[Int] =
            def run(n: Int, acc: Int): F[Int] =
                if n < c.length then
                    m.flatMap(p(c(n))) { r =>
                        if r then run(n + 1, acc + 1)
                        else m.pure(acc)
                    }
                else m.pure(acc)

            run(from, 0)
        end segmentLength

        override def segmentLength[F[_]](c: CA, m: CpsMonad[F])(p: A => F[Boolean]): F[Int] =
            segmentLength(c, m)(p, 0)
    end KyoIndexedSeqAsyncShift
     */

    class KyoSeqAsyncShift[A, C[X] <: Seq[X] & SeqOps[X, C, C[X]], CA <: C[A]] extends SeqAsyncShift[A, C, CA]:

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
