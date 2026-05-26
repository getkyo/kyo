package kyo.compat

import com.twitter.concurrent.AsyncStream

/** Underlying carrier is `com.twitter.concurrent.AsyncStream[A]`, Twitter's lazy / pull-based functional stream from `util-core`. `lift`
  * and `lower` are identity since the carrier is already a native `AsyncStream`. Twitter Future has no `Frame` / `Trace` to propagate.
  * `unfold` is defined via recursion through `AsyncStream.mk(head, => tail)` since `AsyncStream` ships no native unfold. Effectful `tap`
  * and `filter` compose `AsyncStream.mapF` / `flatMap` with Future combinators (`Future.map`, `AsyncStream.fromFuture`) — the wraps are
  * state-free, matching the shape of the Cats Effect binding's `Stream.eval(c.lower).flatMap(Stream.emits)`. Terminal `run` / `foldPure` /
  * `foreach` / `discard` lower to `AsyncStream.toSeq()` / `foldLeft` / `foreachF` / `force` respectively, wrapped via `CIO.deferLift` so
  * each materialization re-runs the stream.
  */
opaque type CStream[+A] = AsyncStream[A]

object CStream:

    /** Empty stream. */
    inline def empty[A]: CStream[A] = AsyncStream.empty[A]

    /** Stream emitting the elements produced by the effectful sequence. */
    inline def init[A](inline c: CIO[Seq[A]]): CStream[A] =
        AsyncStream.fromFuture(c.lower()).flatMap(AsyncStream.fromSeq)

    /** Stream emitting the elements of the given sequence. */
    inline def init[A](inline seq: Seq[A]): CStream[A] =
        AsyncStream.fromSeq(seq)

    /** Stream emitting `start` until `end` (exclusive), step 1. */
    inline def range(inline start: Int, inline end: Int): CStream[Int] =
        AsyncStream.fromSeq(start until end)

    /** Stream produced by iteratively unfolding `acc` through `f`; emission stops when `f` yields `None`. */
    def unfold[S, A](acc: S)(f: S => CIO[Option[(A, S)]]): CStream[A] =
        AsyncStream.fromFuture(f(acc).lower()).flatMap {
            case Some((a, s)) => AsyncStream.mk(a, unfold(s)(f))
            case None         => AsyncStream.empty[A]
        }

    /** Wraps a native `AsyncStream` as a `CStream`. Identity on the carrier. */
    inline def lift[A](inline native: AsyncStream[A]): CStream[A] = native

    extension [A](inline self: CStream[A])

        /** Unwraps to the native `AsyncStream`. Identity on the carrier. */
        inline def lower: AsyncStream[A] = self

        /** Concatenates `other` after `self`. */
        inline def concat[A2 >: A](inline other: CStream[A2]): CStream[A2] =
            self ++ other.lower

        /** Maps each element with a pure function. */
        inline def mapPure[B](inline f: A => B): CStream[B] =
            self.map(f)

        /** Maps each element with an effectful function. */
        inline def map[B](inline f: A => CIO[B]): CStream[B] =
            self.mapF(a => f(a).lower())

        /** Flat-maps each element to another stream and concatenates the results. */
        inline def flatMap[B](inline f: A => CStream[B]): CStream[B] =
            self.flatMap(a => f(a).lower)

        /** Runs `f` for its effect on each element, passing the element through unchanged. */
        inline def tap(inline f: A => CIO[Any]): CStream[A] =
            self.mapF(a => f(a).lower().map(_ => a))

        /** Takes the first `n` elements. */
        inline def take(inline n: Int): CStream[A] =
            self.take(n)

        /** Drops the first `n` elements. */
        inline def drop(inline n: Int): CStream[A] =
            self.drop(n)

        /** Takes elements while `p` holds. */
        inline def takeWhilePure(inline p: A => Boolean): CStream[A] =
            self.takeWhile(p)

        /** Keeps elements matching the pure predicate. */
        inline def filterPure(inline p: A => Boolean): CStream[A] =
            self.filter(p)

        /** Keeps elements matching the effectful predicate. */
        inline def filter(inline p: A => CIO[Boolean]): CStream[A] =
            self.flatMap(a =>
                AsyncStream.fromFuture(p(a).lower()).flatMap(b =>
                    if b then AsyncStream.of(a) else AsyncStream.empty[A]
                )
            )

        /** Maps and filters in one pass via a pure partial mapping. */
        inline def collectPure[B](inline f: A => Option[B]): CStream[B] =
            self.flatMap(a => f(a).fold(AsyncStream.empty[B])(AsyncStream.of))

        /** Runs the stream and collects all emitted elements into a `CChunk`. */
        inline def run: CIO[CChunk[A]] =
            CIO.deferLift(self.toSeq().map(s => CChunk.lift(s.toVector)))

        /** Folds the stream with a pure accumulator. */
        inline def foldPure[B](inline acc: B)(inline f: (B, A) => B): CIO[B] =
            CIO.deferLift(self.foldLeft(acc)(f))

        /** Runs `f` for its effect on each element, discarding results. */
        inline def foreach(inline f: A => CIO[Unit]): CIO[Unit] =
            CIO.deferLift(self.foreachF(a => f(a).lower()))

        /** Runs the stream and discards all emitted elements. */
        inline def discard: CIO[Unit] =
            CIO.deferLift(self.force)

    end extension

end CStream
