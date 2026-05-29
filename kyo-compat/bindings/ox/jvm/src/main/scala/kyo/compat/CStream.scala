package kyo.compat

import ox.Ox
import ox.flow.Flow

/** Underlying carrier is `Ox => ox.flow.Flow[A]`. Mirroring the Ox `CIO` binding's `(Int, Ox) => A` shape, the function threads the `Ox`
  * capability through Flow stages so effectful stage bodies can lower their inner `CIO` arguments at consumption time. Opaque aliases
  * cannot use context-function types directly, so the carrier is a plain `Function1[Ox, Flow[A]]`; the public `lower` returns the
  * context-function shape `Ox ?=> Flow[A]` for ergonomic use at call sites. `lift` wraps an Ox-agnostic `Flow[A]` by ignoring the carrier
  * argument. Operations delegate to the native `Flow` methods directly; method names mirror `kyo.Stream`
  * (`mapPure`/`filterPure`/`takeWhilePure`/`collectPure`/`foldPure`/`discard`); the pure/effectful split tracks the kyo convention.
  */
opaque type CStream[A] = Ox => Flow[A]

object CStream:

    /** Empty stream. */
    inline def empty[A]: CStream[A] = (_: Ox) => Flow.empty[A]

    /** Stream emitting the elements produced by the effectful sequence. */
    inline def init[A](inline c: CIO[Seq[A]]): CStream[A] =
        (ox: Ox) => Flow.fromIterable(c.lower(using ox))

    /** Stream emitting the elements of the given sequence. */
    inline def init[A](inline seq: Seq[A]): CStream[A] =
        (_: Ox) => Flow.fromIterable(seq)

    /** Stream emitting `start` until `end` (exclusive), step 1. */
    inline def range(inline start: Int, inline end: Int): CStream[Int] =
        (_: Ox) => if start >= end then Flow.empty[Int] else Flow.range(start, end - 1, 1)

    /** Stream produced by iteratively unfolding `acc` through `f`; emission stops when `f` yields `None`. */
    inline def unfold[S, A](inline acc: S)(inline f: S => CIO[Option[(A, S)]]): CStream[A] =
        (ox: Ox) => Flow.unfold(acc)(s => f(s).lower(using ox))

    /** Wraps a native `Flow` (optionally Ox-threaded) as a `CStream`. Identity on the carrier. */
    inline def lift[A](inline native: Ox ?=> Flow[A]): CStream[A] =
        (ox: Ox) => native(using ox)

    extension [A](inline self: CStream[A])

        /** Unwraps to the native `Flow`. Identity on the carrier. */
        inline def lower: Ox ?=> Flow[A] = (ox: Ox) ?=> self(ox)

        /** Concatenates `other` after `self`. */
        inline def concat[A2 >: A](inline other: CStream[A2]): CStream[A2] =
            (ox: Ox) => self(ox).concat(other(ox))

        /** Maps each element with a pure function. */
        inline def mapPure[B](inline f: A => B): CStream[B] =
            (ox: Ox) => self(ox).map(f)

        /** Maps each element with an effectful function. */
        inline def map[B](inline f: A => CIO[B]): CStream[B] =
            (ox: Ox) => self(ox).map(a => f(a).lower(using ox))

        /** Flat-maps each element to another stream and concatenates the results. */
        inline def flatMap[B](inline f: A => CStream[B]): CStream[B] =
            (ox: Ox) => self(ox).flatMap(a => f(a)(ox))

        /** Runs `f` for its effect on each element, passing the element through unchanged. */
        inline def tap(inline f: A => CIO[Any]): CStream[A] =
            (ox: Ox) =>
                self(ox).tap { a =>
                    f(a).lower(using ox)
                    ()
                }

        /** Takes the first `n` elements. */
        inline def take(inline n: Int): CStream[A] =
            (ox: Ox) => self(ox).take(n)

        /** Drops the first `n` elements. */
        inline def drop(inline n: Int): CStream[A] =
            (ox: Ox) => self(ox).drop(n)

        /** Takes elements while `p` holds. */
        inline def takeWhilePure(inline p: A => Boolean): CStream[A] =
            (ox: Ox) => self(ox).takeWhile(p)

        /** Keeps elements matching the pure predicate. */
        inline def filterPure(inline p: A => Boolean): CStream[A] =
            (ox: Ox) => self(ox).filter(p)

        /** Keeps elements matching the effectful predicate. */
        inline def filter(inline p: A => CIO[Boolean]): CStream[A] =
            (ox: Ox) => self(ox).filter(a => p(a).lower(using ox))

        /** Maps and filters in one pass via a pure partial mapping. */
        inline def collectPure[B](inline f: A => Option[B]): CStream[B] =
            (ox: Ox) => self(ox).collect(Function.unlift(f))

        /** Runs the stream and collects all emitted elements into a `CChunk`. */
        inline def run: CIO[CChunk[A]] =
            CIO.deferLift {
                CChunk.lift(self(summon[Ox]).runToList().toVector)
            }

        /** Folds the stream with a pure accumulator. */
        inline def foldPure[B](inline acc: B)(inline f: (B, A) => B): CIO[B] =
            CIO.deferLift {
                self(summon[Ox]).runFold(acc)(f)
            }

        /** Runs `f` for its effect on each element, discarding results. */
        inline def foreach(inline f: A => CIO[Unit]): CIO[Unit] =
            CIO.deferLift {
                val ox = summon[Ox]
                self(ox).runForeach(a => f(a).lower(using ox))
            }

        /** Runs the stream and discards all emitted elements. */
        inline def discard: CIO[Unit] =
            CIO.deferLift {
                self(summon[Ox]).runDrain()
            }

    end extension

end CStream
