package kyo.compat

import cats.effect.IO
import fs2.Stream

/** Underlying carrier is `fs2.Stream[cats.effect.IO, A]`. Operations delegate to the native `fs2.Stream` methods directly. The fs2 error
  * channel is the throwable raised inside the underlying `IO`; matches `CStream`'s `Throwable` shape. `lift` and `lower` are identity since
  * the carrier is already an `fs2.Stream`. Method names mirror `kyo.Stream`
  * (`mapPure`/`filterPure`/`takeWhilePure`/`collectPure`/`foldPure`/`discard`); the pure/effectful split tracks the kyo convention.
  */
opaque type CStream[A] = Stream[IO, A]

object CStream:

    /** Empty stream. */
    inline def empty[A]: CStream[A] = Stream.empty.covary[IO]

    /** Stream emitting the elements produced by the effectful sequence. */
    inline def init[A](inline c: CIO[Seq[A]]): CStream[A] =
        Stream.eval(c.lower).flatMap(Stream.emits)

    /** Stream emitting the elements of the given sequence. */
    inline def init[A](inline seq: Seq[A]): CStream[A] =
        Stream.emits(seq)

    /** Stream emitting `start` until `end` (exclusive), step 1. */
    inline def range(inline start: Int, inline end: Int): CStream[Int] =
        Stream.range(start, end).covary[IO]

    /** Stream produced by iteratively unfolding `acc` through `f`; emission stops when `f` yields `None`. */
    inline def unfold[S, A](inline acc: S)(inline f: S => CIO[Option[(A, S)]]): CStream[A] =
        Stream.unfoldEval(acc)(s => f(s).lower)

    /** Wraps a native `fs2.Stream` as a `CStream`. Identity on the carrier. */
    inline def lift[A](inline native: Stream[IO, A]): CStream[A] = native

    extension [A](inline self: CStream[A])

        /** Unwraps to the native `fs2.Stream`. Identity on the carrier. */
        inline def lower: Stream[IO, A] = self

        /** Concatenates `other` after `self`. */
        inline def concat[A2 >: A](inline other: CStream[A2]): CStream[A2] =
            self ++ other.lower

        /** Maps each element with a pure function. */
        inline def mapPure[B](inline f: A => B): CStream[B] =
            self.map(f)

        /** Maps each element with an effectful function. */
        inline def map[B](inline f: A => CIO[B]): CStream[B] =
            self.evalMap(a => f(a).lower)

        /** Flat-maps each element to another stream and concatenates the results. */
        inline def flatMap[B](inline f: A => CStream[B]): CStream[B] =
            self.flatMap(a => f(a).lower)

        /** Runs `f` for its effect on each element, passing the element through unchanged. */
        inline def tap(inline f: A => CIO[Any]): CStream[A] =
            self.evalTap(a => f(a).lower.void)

        /** Takes the first `n` elements. */
        inline def take(inline n: Int): CStream[A] =
            self.take(n.toLong)

        /** Drops the first `n` elements. */
        inline def drop(inline n: Int): CStream[A] =
            self.drop(n.toLong)

        /** Takes elements while `p` holds. */
        inline def takeWhilePure(inline p: A => Boolean): CStream[A] =
            self.takeWhile(p)

        /** Keeps elements matching the pure predicate. */
        inline def filterPure(inline p: A => Boolean): CStream[A] =
            self.filter(p)

        /** Keeps elements matching the effectful predicate. */
        inline def filter(inline p: A => CIO[Boolean]): CStream[A] =
            self.evalFilter(a => p(a).lower)

        /** Maps and filters in one pass via a pure partial mapping. */
        inline def collectPure[B](inline f: A => Option[B]): CStream[B] =
            self.collect(Function.unlift(f))

        /** Runs the stream and collects all emitted elements into a `CChunk`. */
        inline def run: CIO[CChunk[A]] =
            CIO.lift(self.compile.toVector.map(CChunk.lift(_)))

        /** Folds the stream with a pure accumulator. */
        inline def foldPure[B](inline acc: B)(inline f: (B, A) => B): CIO[B] =
            CIO.lift(self.compile.fold(acc)(f))

        /** Runs `f` for its effect on each element, discarding results. */
        inline def foreach(inline f: A => CIO[Unit]): CIO[Unit] =
            CIO.lift(self.foreach(a => f(a).lower).compile.drain)

        /** Runs the stream and discards all emitted elements. */
        inline def discard: CIO[Unit] =
            CIO.lift(self.compile.drain)

    end extension

end CStream
