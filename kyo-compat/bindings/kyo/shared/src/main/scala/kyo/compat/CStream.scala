package kyo.compat

import kyo.*

/** Underlying carrier is `kyo.Stream[A, Abort[Throwable] & Async]`, paired with the `Tag[Emit[Chunk[A]]]` that keys its emit channel.
  *
  * `kyo.Stream` re-summons `Tag[Emit[Chunk[A]]]` on every operator, so generic code over an abstract `A` cannot call it. `CStream` captures
  * that tag once, at construction (where `A` is concrete), and threads the stored tag into each delegated `kyo.Stream` call. The result is a
  * tag-free surface for every element-preserving operator (`take`/`drop`/`filter`/`run`/`fold`/...), matching the other kyo-compat bindings
  * so library authors can generalize a pipeline across libraries.
  *
  * Element-changing operators (`map`/`mapPure`/`flatMap`/`collectPure` → `CStream[B]`) require the output `Tag[Emit[Chunk[B]]]`: the stored
  * `A`-tag cannot name `B`, and fabricating one would be unsound. `B` is concrete at every realistic call site, so the tag derives
  * automatically there.
  *
  * `CStream` is invariant in `A`. Covariance would let `CStream[Dog]` flow where `CStream[Animal]` is expected while the stored tag still
  * keys the `Dog` channel; the type-level widening would not be backed by the runtime key, silently dropping elements. Invariance keeps the
  * stored tag honest, so `lower` returns a faithful native stream with no re-tagging and no casts.
  *
  * Method names mirror `kyo.Stream` (`mapPure`/`filterPure`/`takeWhilePure`/`collectPure`/`foldPure`/`discard`); the pure/effectful split
  * tracks the kyo convention.
  */
final class CStream[A] private[compat] (
    private[compat] val tag: Tag[Emit[Chunk[A]]],
    private[compat] val stream: kyo.Stream[A, Abort[Throwable] & Async]
):

    /** Unwraps to the native `kyo.Stream`. The stored tag is always the honest key for the carrier, so this needs no re-tagging. */
    def lower: kyo.Stream[A, Abort[Throwable] & Async] = stream

    /** Concatenates `other` after `self`. */
    def concat[A2 >: A](other: CStream[A2])(using frame: Frame): CStream[A2] =
        new CStream(other.tag, stream.concat(other.stream))

    /** Maps each element with a pure function. */
    def mapPure[B](f: A => B)(using outTag: Tag[Emit[Chunk[B]]], frame: Frame): CStream[B] =
        given tagA: Tag[Emit[Chunk[A]]] = tag
        given tagB: Tag[Emit[Chunk[B]]] = outTag
        new CStream(outTag, stream.mapPure(f))
    end mapPure

    /** Maps each element with an effectful function. */
    def map[B](f: A => CIO[B])(using outTag: Tag[Emit[Chunk[B]]], frame: Frame): CStream[B] =
        given tagA: Tag[Emit[Chunk[A]]] = tag
        given tagB: Tag[Emit[Chunk[B]]] = outTag
        new CStream(outTag, stream.map(a => f(a).lower))
    end map

    /** Flat-maps each element to another stream and concatenates the results. */
    def flatMap[B](f: A => CStream[B])(using outTag: Tag[Emit[Chunk[B]]], frame: Frame): CStream[B] =
        given tagA: Tag[Emit[Chunk[A]]] = tag
        given tagB: Tag[Emit[Chunk[B]]] = outTag
        new CStream(outTag, stream.flatMap(a => f(a).lower))
    end flatMap

    /** Runs `f` for its effect on each element, passing the element through unchanged. */
    def tap(f: A => CIO[Any])(using frame: Frame): CStream[A] =
        given Tag[Emit[Chunk[A]]] = tag
        new CStream(tag, stream.tap(a => f(a).lower))

    /** Takes the first `n` elements. */
    def take(n: Int)(using frame: Frame): CStream[A] =
        given Tag[Emit[Chunk[A]]] = tag
        new CStream(tag, stream.take(n))

    /** Drops the first `n` elements. */
    def drop(n: Int)(using frame: Frame): CStream[A] =
        given Tag[Emit[Chunk[A]]] = tag
        new CStream(tag, stream.drop(n))

    /** Takes elements while `p` holds. */
    def takeWhilePure(p: A => Boolean)(using frame: Frame): CStream[A] =
        given Tag[Emit[Chunk[A]]] = tag
        new CStream(tag, stream.takeWhilePure(p))

    /** Keeps elements matching the pure predicate. */
    def filterPure(p: A => Boolean)(using frame: Frame): CStream[A] =
        given Tag[Emit[Chunk[A]]] = tag
        new CStream(tag, stream.filterPure(p))

    /** Keeps elements matching the effectful predicate. */
    def filter(p: A => CIO[Boolean])(using frame: Frame): CStream[A] =
        given Tag[Emit[Chunk[A]]] = tag
        new CStream(tag, stream.filter(a => p(a).lower))

    /** Maps and filters in one pass via a pure partial mapping. */
    def collectPure[B](f: A => Option[B])(using outTag: Tag[Emit[Chunk[B]]], frame: Frame): CStream[B] =
        given tagA: Tag[Emit[Chunk[A]]] = tag
        given tagB: Tag[Emit[Chunk[B]]] = outTag
        new CStream(outTag, stream.collectPure(a => Maybe.fromOption(f(a))))
    end collectPure

    /** Runs the stream and collects all emitted elements into a `CChunk`. */
    def run(using frame: Frame): CIO[CChunk[A]] =
        given Tag[Emit[Chunk[A]]] = tag
        CIO.lift(stream.run.map(CChunk.lift(_)))

    /** Folds the stream with a pure accumulator. */
    def foldPure[B](acc: B)(f: (B, A) => B)(using frame: Frame): CIO[B] =
        given Tag[Emit[Chunk[A]]] = tag
        CIO.lift(stream.foldPure(acc)(f))

    /** Runs `f` for its effect on each element, discarding results. */
    def foreach(f: A => CIO[Unit])(using frame: Frame): CIO[Unit] =
        given Tag[Emit[Chunk[A]]] = tag
        CIO.lift(stream.foreach(a => f(a).lower))

    /** Runs the stream and discards all emitted elements. */
    def discard(using frame: Frame): CIO[Unit] =
        given Tag[Emit[Chunk[A]]] = tag
        CIO.lift(stream.discard)

end CStream

object CStream:

    /** Empty stream. */
    def empty[A](using tag: Tag[Emit[Chunk[A]]]): CStream[A] =
        new CStream(tag, kyo.Stream.empty[A])

    /** Stream emitting the elements of the given sequence. */
    def init[A](seq: Seq[A])(using tag: Tag[Emit[Chunk[A]]], frame: Frame): CStream[A] =
        new CStream(tag, kyo.Stream.init(seq))

    /** Stream emitting the elements produced by the effectful sequence. */
    def init[A](c: CIO[Seq[A]])(using tag: Tag[Emit[Chunk[A]]], frame: Frame): CStream[A] =
        new CStream(tag, kyo.Stream.init(c.lower))

    /** Stream emitting `start` until `end` (exclusive), step 1. */
    def range(start: Int, end: Int)(using frame: Frame): CStream[Int] =
        new CStream(Tag[Emit[Chunk[Int]]], kyo.Stream.range(start, end))

    /** Stream produced by iteratively unfolding `acc` through `f`; emission stops when `f` yields `None`. */
    def unfold[S, A](acc: S)(f: S => CIO[Option[(A, S)]])(using tag: Tag[Emit[Chunk[A]]], frame: Frame): CStream[A] =
        new CStream(tag, kyo.Stream.unfold(acc)(s => f(s).lower.map(Maybe.fromOption)))

    /** Wraps a native `kyo.Stream` as a `CStream`, capturing its emit-channel tag. */
    def lift[A](native: kyo.Stream[A, Abort[Throwable] & Async])(using tag: Tag[Emit[Chunk[A]]]): CStream[A] =
        new CStream(tag, native)

end CStream
