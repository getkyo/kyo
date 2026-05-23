package kyo.compat

import zio.Trace
import zio.stream.ZStream

/** Underlying carrier is `zio.stream.ZStream[Any, Throwable, A]`. Operations delegate to the native `ZStream` methods directly and
  * propagate ZIO `Trace` through `(using inline trace: Trace)` on every entry point. `lift` and `lower` are identity since the carrier is
  * already a `ZStream`. Method names mirror `kyo.Stream` (`mapPure`/`filterPure`/`takeWhilePure`/`collectPure`/`foldPure`/`discard`); the
  * pure/effectful split tracks the kyo convention.
  */
opaque type CStream[+A] = ZStream[Any, Throwable, A]

object CStream:

    /** Empty stream. */
    inline def empty[A]: CStream[A] = ZStream.empty

    /** Stream emitting the elements produced by the effectful sequence. */
    inline def init[A](inline c: CIO[Seq[A]])(using inline trace: Trace): CStream[A] =
        ZStream.fromIterableZIO(c.lower)

    /** Stream emitting the elements of the given sequence. */
    inline def init[A](inline seq: Seq[A])(using inline trace: Trace): CStream[A] =
        ZStream.fromIterable(seq)

    /** Stream emitting `start` until `end` (exclusive), step 1. */
    inline def range(inline start: Int, inline end: Int)(using inline trace: Trace): CStream[Int] =
        ZStream.range(start, end)

    /** Stream produced by iteratively unfolding `acc` through `f`; emission stops when `f` yields `None`. */
    inline def unfold[S, A](inline acc: S)(inline f: S => CIO[Option[(A, S)]])(using inline trace: Trace): CStream[A] =
        ZStream.unfoldZIO(acc)(s => f(s).lower)

    /** Wraps a native `ZStream` as a `CStream`. Identity on the carrier. */
    inline def lift[A](inline native: ZStream[Any, Throwable, A]): CStream[A] = native

    extension [A](inline self: CStream[A])

        /** Unwraps to the native `ZStream`. Identity on the carrier. */
        inline def lower: ZStream[Any, Throwable, A] = self

        /** Concatenates `other` after `self`. */
        inline def concat[A2 >: A](inline other: CStream[A2])(using inline trace: Trace): CStream[A2] =
            self.concat(other.lower)

        /** Maps each element with a pure function. */
        inline def mapPure[B](inline f: A => B)(using inline trace: Trace): CStream[B] =
            self.map(f)

        /** Maps each element with an effectful function. */
        inline def map[B](inline f: A => CIO[B])(using inline trace: Trace): CStream[B] =
            self.mapZIO(a => f(a).lower)

        /** Flat-maps each element to another stream and concatenates the results. */
        inline def flatMap[B](inline f: A => CStream[B])(using inline trace: Trace): CStream[B] =
            self.flatMap(a => f(a).lower)

        /** Runs `f` for its effect on each element, passing the element through unchanged. */
        inline def tap(inline f: A => CIO[Any])(using inline trace: Trace): CStream[A] =
            self.tap(a => f(a).lower)

        /** Takes the first `n` elements. */
        inline def take(inline n: Int)(using inline trace: Trace): CStream[A] =
            self.take(n.toLong)

        /** Drops the first `n` elements. */
        inline def drop(inline n: Int)(using inline trace: Trace): CStream[A] =
            self.drop(n)

        /** Takes elements while `p` holds. */
        inline def takeWhilePure(inline p: A => Boolean)(using inline trace: Trace): CStream[A] =
            self.takeWhile(p)

        /** Keeps elements matching the pure predicate. */
        inline def filterPure(inline p: A => Boolean)(using inline trace: Trace): CStream[A] =
            self.filter(p)

        /** Keeps elements matching the effectful predicate. */
        inline def filter(inline p: A => CIO[Boolean])(using inline trace: Trace): CStream[A] =
            self.filterZIO(a => p(a).lower)

        /** Maps and filters in one pass via a pure partial mapping. */
        inline def collectPure[B](inline f: A => Option[B])(using inline trace: Trace): CStream[B] =
            self.collect(Function.unlift(f))

        /** Runs the stream and collects all emitted elements into a `CChunk`. */
        inline def run(using inline trace: Trace): CIO[CChunk[A]] =
            CIO.lift(self.runCollect.map(CChunk.lift(_)))

        /** Folds the stream with a pure accumulator. */
        inline def foldPure[B](inline acc: B)(inline f: (B, A) => B)(using inline trace: Trace): CIO[B] =
            CIO.lift(self.runFold(acc)(f))

        /** Runs `f` for its effect on each element, discarding results. */
        inline def foreach(inline f: A => CIO[Unit])(using inline trace: Trace): CIO[Unit] =
            CIO.lift(self.runForeach(a => f(a).lower))

        /** Runs the stream and discards all emitted elements. */
        inline def discard(using inline trace: Trace): CIO[Unit] =
            CIO.lift(self.runDrain)

    end extension

end CStream
