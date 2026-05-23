package kyo.compat

import kyo.*

/** Underlying carrier is `kyo.Stream[A, Abort[Throwable] & Async]`. Operations preserve kyo `Frame`, `Tag`, and chunking semantics by
  * delegating to the native `kyo.Stream` methods directly. `lift` and `lower` are identity since the carrier is already a kyo-native
  * stream. Method names mirror `kyo.Stream` (`mapPure`/`filterPure`/`takeWhilePure`/`collectPure`/`foldPure`/`discard`); the pure/effectful
  * split tracks the kyo convention.
  */
opaque type CStream[+A] = kyo.Stream[A, kyo.Abort[Throwable] & kyo.Async]

object CStream:

    /** Empty stream. */
    inline def empty[A]: CStream[A] = kyo.Stream.empty[A]

    /** Stream emitting the elements produced by the effectful sequence. */
    inline def init[A](inline c: CIO[Seq[A]])(using inline frame: Frame): CStream[A] =
        kyo.Stream.init(c.lower)

    /** Stream emitting the elements of the given sequence. */
    inline def init[A](inline seq: Seq[A])(using inline frame: Frame): CStream[A] =
        kyo.Stream.init(seq)

    /** Stream emitting `start` until `end` (exclusive), step 1. */
    inline def range(inline start: Int, inline end: Int)(using inline frame: Frame): CStream[Int] =
        kyo.Stream.range(start, end)

    /** Stream produced by iteratively unfolding `acc` through `f`; emission stops when `f` yields `None`. */
    inline def unfold[S, A](inline acc: S)(inline f: S => CIO[Option[(A, S)]])(using inline frame: Frame): CStream[A] =
        kyo.Stream.unfold(acc)(s => f(s).lower.map(kyo.Maybe.fromOption))

    /** Wraps a native `kyo.Stream` as a `CStream`. Identity on the carrier. */
    inline def lift[A](inline native: kyo.Stream[A, kyo.Abort[Throwable] & kyo.Async]): CStream[A] = native

    extension [A](inline self: CStream[A])

        /** Unwraps to the native `kyo.Stream`. Identity on the carrier. */
        inline def lower: kyo.Stream[A, kyo.Abort[Throwable] & kyo.Async] = self

        /** Concatenates `other` after `self`. */
        inline def concat[A2 >: A](inline other: CStream[A2])(using inline frame: Frame): CStream[A2] =
            self.concat(other.lower)

        /** Maps each element with a pure function. */
        inline def mapPure[B](inline f: A => B)(using inline frame: Frame): CStream[B] =
            self.mapPure(f)

        /** Maps each element with an effectful function. */
        inline def map[B](inline f: A => CIO[B])(using inline frame: Frame): CStream[B] =
            self.map(a => f(a).lower)

        /** Flat-maps each element to another stream and concatenates the results. */
        inline def flatMap[B](inline f: A => CStream[B])(using inline frame: Frame): CStream[B] =
            self.flatMap(a => f(a).lower)

        /** Runs `f` for its effect on each element, passing the element through unchanged. */
        inline def tap(inline f: A => CIO[Any])(using inline frame: Frame): CStream[A] =
            self.tap(a => f(a).lower)

        /** Takes the first `n` elements. */
        inline def take(inline n: Int)(using inline frame: Frame): CStream[A] =
            self.take(n)

        /** Drops the first `n` elements. */
        inline def drop(inline n: Int)(using inline frame: Frame): CStream[A] =
            self.drop(n)

        /** Takes elements while `p` holds. */
        inline def takeWhilePure(inline p: A => Boolean)(using inline frame: Frame): CStream[A] =
            self.takeWhilePure(p)

        /** Keeps elements matching the pure predicate. */
        inline def filterPure(inline p: A => Boolean)(using inline frame: Frame): CStream[A] =
            self.filterPure(p)

        /** Keeps elements matching the effectful predicate. */
        inline def filter(inline p: A => CIO[Boolean])(using inline frame: Frame): CStream[A] =
            self.filter(a => p(a).lower)

        /** Maps and filters in one pass via a pure partial mapping. */
        inline def collectPure[B](inline f: A => Option[B])(using inline frame: Frame): CStream[B] =
            self.collectPure(a => kyo.Maybe.fromOption(f(a)))

        /** Runs the stream and collects all emitted elements into a `CChunk`. */
        inline def run(using inline frame: Frame): CIO[CChunk[A]] =
            CIO.lift(self.run.map(CChunk.lift(_)))

        /** Folds the stream with a pure accumulator. */
        inline def foldPure[B](inline acc: B)(inline f: (B, A) => B)(using inline frame: Frame): CIO[B] =
            CIO.lift(self.foldPure(acc)(f))

        /** Runs `f` for its effect on each element, discarding results. */
        inline def foreach(inline f: A => CIO[Unit])(using inline frame: Frame): CIO[Unit] =
            CIO.lift(self.foreach(a => f(a).lower))

        /** Runs the stream and discards all emitted elements. */
        inline def discard(using inline frame: Frame): CIO[Unit] =
            CIO.lift(self.discard)

    end extension

end CStream
