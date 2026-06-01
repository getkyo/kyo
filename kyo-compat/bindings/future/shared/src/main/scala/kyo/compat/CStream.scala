package kyo.compat

import kyo.compat.internal.LocalCtx
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

/** Underlying carrier is `LocalCtx => Future[Repr[A]]` where `Repr` is the binding-private cons-stream ADT (`Empty | Cons(head, tail:
  * LocalCtx => Future[Repr[A]])`). The Future ecosystem has no canonical async stream type, so the binding supplies its own — same pattern
  * the binding already uses for `CChannel` / `CLatch` / `CMeter`. `LocalCtx` threads through every stage, matching the `CIO` carrier shape.
  * Transformations build cons cells with lazy tails that recurse via extension-method dispatch on `t`. Terminal walks (`run`, `foldPure`,
  * `foreach`, `discard`) split their loop into a nested `@tailrec def loop` (sync-completed path, keeps the stack flat) plus a `drive`
  * wrapper that re-enters the loop through `Future.flatMap` when the next `Future[Repr[A]]` isn't yet resolved — so 100000-element
  * sync-completed streams don't blow the stack. `lift` and `lower` are identity on the opaque `CStream[A]` since the binding's "native"
  * stream type is the carrier itself.
  */
opaque type CStream[+A] = LocalCtx => Future[CStream.Repr[A]]

object CStream:

    sealed private[compat] trait Repr[+A]
    private[compat] case object Empty                                                     extends Repr[Nothing]
    final private[compat] case class Cons[+A](head: A, tail: LocalCtx => Future[Repr[A]]) extends Repr[A]

    private val parasiticEc: ExecutionContext      = scala.concurrent.ExecutionContext.parasitic
    private val emptyFuture: Future[Repr[Nothing]] = Future.successful(Empty)

    /** Empty stream. */
    inline def empty[A]: CStream[A] = _ => emptyFuture

    /** Stream emitting the elements produced by the effectful sequence. */
    inline def init[A](inline c: CIO[Seq[A]]): CStream[A] =
        ctx => c.lower(using ctx).map(seq => seqToRepr(seq.iterator))(parasiticEc)

    /** Stream emitting the elements of the given sequence. */
    inline def init[A](inline seq: Seq[A]): CStream[A] =
        _ => Future.successful(seqToRepr(seq.iterator))

    /** Stream emitting `start` until `end` (exclusive), step 1. */
    inline def range(inline start: Int, inline end: Int): CStream[Int] =
        _ => Future.successful(seqToRepr((start until end).iterator))

    /** Stream produced by iteratively unfolding `acc` through `f`; emission stops when `f` yields `None`. */
    def unfold[S, A](acc: S)(f: S => CIO[Option[(A, S)]]): CStream[A] =
        ctx =>
            f(acc).lower(using ctx).map {
                case Some((a, s)) => Cons(a, unfold(s)(f))
                case None         => Empty
            }(parasiticEc)

    /** Wraps a native stream as a `CStream`. Identity — the binding's native stream type is the carrier itself. */
    inline def lift[A](inline native: CStream[A]): CStream[A] = native

    extension [A](self: CStream[A])

        /** Unwraps to the native stream. Identity — the binding's native stream type is the carrier itself. */
        inline def lower: CStream[A] = self

        /** Concatenates `other` after `self`. */
        def concat[A2 >: A](other: CStream[A2]): CStream[A2] =
            ctx =>
                self(ctx).flatMap {
                    case _: Empty.type => other(ctx)
                    case Cons(h, t)    => Future.successful(Cons(h, t.concat(other)))
                }(parasiticEc)

        /** Maps each element with a pure function. */
        def mapPure[B](f: A => B): CStream[B] =
            ctx =>
                self(ctx).map {
                    case _: Empty.type => Empty
                    case Cons(h, t)    => Cons(f(h), t.mapPure(f))
                }(parasiticEc)

        /** Maps each element with an effectful function. */
        def map[B](f: A => CIO[B]): CStream[B] =
            ctx =>
                self(ctx).flatMap {
                    case _: Empty.type => emptyFuture
                    case Cons(h, t)    => f(h).lower(using ctx).map(b => Cons(b, t.map(f)))(parasiticEc)
                }(parasiticEc)

        /** Flat-maps each element to another stream and concatenates the results. */
        def flatMap[B](f: A => CStream[B]): CStream[B] =
            ctx =>
                self(ctx).flatMap {
                    case _: Empty.type => emptyFuture
                    case Cons(h, t)    => f(h).concat(t.flatMap(f))(ctx)
                }(parasiticEc)

        /** Runs `f` for its effect on each element, passing the element through unchanged. */
        def tap(f: A => CIO[Any]): CStream[A] =
            ctx =>
                self(ctx).flatMap {
                    case _: Empty.type => emptyFuture
                    case Cons(h, t)    => f(h).lower(using ctx).map(_ => Cons(h, t.tap(f)))(parasiticEc)
                }(parasiticEc)

        /** Takes the first `n` elements. */
        def take(n: Int): CStream[A] =
            ctx =>
                if n <= 0 then emptyFuture
                else
                    self(ctx).map {
                        case _: Empty.type => Empty
                        case Cons(h, t)    => Cons(h, t.take(n - 1))
                    }(parasiticEc)

        /** Drops the first `n` elements. */
        def drop(n: Int): CStream[A] =
            ctx =>
                if n <= 0 then self(ctx)
                else
                    self(ctx).flatMap {
                        case _: Empty.type => emptyFuture
                        case Cons(_, t)    => t.drop(n - 1)(ctx)
                    }(parasiticEc)

        /** Takes elements while `p` holds. */
        def takeWhilePure(p: A => Boolean): CStream[A] =
            ctx =>
                self(ctx).map {
                    case _: Empty.type      => Empty
                    case Cons(h, t) if p(h) => Cons(h, t.takeWhilePure(p))
                    case _                  => Empty
                }(parasiticEc)

        /** Keeps elements matching the pure predicate. */
        def filterPure(p: A => Boolean): CStream[A] =
            ctx =>
                self(ctx).flatMap {
                    case _: Empty.type      => emptyFuture
                    case Cons(h, t) if p(h) => Future.successful(Cons(h, t.filterPure(p)))
                    case Cons(_, t)         => t.filterPure(p)(ctx)
                }(parasiticEc)

        /** Keeps elements matching the effectful predicate. */
        def filter(p: A => CIO[Boolean]): CStream[A] =
            ctx =>
                self(ctx).flatMap {
                    case _: Empty.type => emptyFuture
                    case Cons(h, t) =>
                        p(h).lower(using ctx).flatMap { keep =>
                            if keep then Future.successful(Cons(h, t.filter(p)))
                            else t.filter(p)(ctx)
                        }(parasiticEc)
                }(parasiticEc)

        /** Maps and filters in one pass via a pure partial mapping. */
        def collectPure[B](f: A => Option[B]): CStream[B] =
            ctx =>
                self(ctx).flatMap {
                    case _: Empty.type => emptyFuture
                    case Cons(h, t) =>
                        f(h) match
                            case Some(b) => Future.successful(Cons(b, t.collectPure(f)))
                            case None    => t.collectPure(f)(ctx)
                }(parasiticEc)

        /** Runs the stream and collects all emitted elements into a `CChunk`. */
        def run: CIO[CChunk[A]] = CIO.deferLift {
            val ctx = summon[LocalCtx]
            @tailrec def loop(repr: Repr[A], acc: List[A]): Future[CChunk[A]] =
                repr match
                    case _: Empty.type => Future.successful(CChunk.lift(acc.reverse.toVector))
                    case Cons(h, t) =>
                        val newAcc = h :: acc
                        val next   = t(ctx)
                        next.value match
                            case Some(Success(r)) => loop(r, newAcc)
                            case Some(Failure(e)) => Future.failed(e)
                            case None             => next.flatMap(drive(_, newAcc))(parasiticEc)
                        end match
            def drive(repr: Repr[A], acc: List[A]): Future[CChunk[A]] = loop(repr, acc)
            self(ctx).flatMap(drive(_, Nil))(parasiticEc)
        }

        /** Folds the stream with a pure accumulator. */
        def foldPure[B](acc: B)(f: (B, A) => B): CIO[B] = CIO.deferLift {
            val ctx = summon[LocalCtx]
            @tailrec def loop(repr: Repr[A], st: B): Future[B] =
                repr match
                    case _: Empty.type => Future.successful(st)
                    case Cons(h, t) =>
                        val newSt = f(st, h)
                        val next  = t(ctx)
                        next.value match
                            case Some(Success(r)) => loop(r, newSt)
                            case Some(Failure(e)) => Future.failed(e)
                            case None             => next.flatMap(drive(_, newSt))(parasiticEc)
                        end match
            def drive(repr: Repr[A], st: B): Future[B] = loop(repr, st)
            self(ctx).flatMap(drive(_, acc))(parasiticEc)
        }

        /** Runs `f` for its effect on each element, discarding results. */
        def foreach(f: A => CIO[Unit]): CIO[Unit] = CIO.deferLift {
            val ctx = summon[LocalCtx]
            @tailrec def loop(repr: Repr[A]): Future[Unit] =
                repr match
                    case _: Empty.type => Future.unit
                    case Cons(h, t) =>
                        val effFut = f(h).lower(using ctx)
                        effFut.value match
                            case Some(Success(_)) =>
                                val next = t(ctx)
                                next.value match
                                    case Some(Success(r)) => loop(r)
                                    case Some(Failure(e)) => Future.failed(e)
                                    case None             => next.flatMap(drive)(parasiticEc)
                                end match
                            case Some(Failure(e)) => Future.failed(e)
                            case None             => effFut.flatMap(_ => t(ctx).flatMap(drive)(parasiticEc))(parasiticEc)
                        end match
            def drive(repr: Repr[A]): Future[Unit] = loop(repr)
            self(ctx).flatMap(drive)(parasiticEc)
        }

        /** Runs the stream and discards all emitted elements. */
        def discard: CIO[Unit] = CIO.deferLift {
            val ctx = summon[LocalCtx]
            @tailrec def loop(repr: Repr[A]): Future[Unit] =
                repr match
                    case _: Empty.type => Future.unit
                    case Cons(_, t) =>
                        val next = t(ctx)
                        next.value match
                            case Some(Success(r)) => loop(r)
                            case Some(Failure(e)) => Future.failed(e)
                            case None             => next.flatMap(drive)(parasiticEc)
                        end match
            def drive(repr: Repr[A]): Future[Unit] = loop(repr)
            self(ctx).flatMap(drive)(parasiticEc)
        }

    end extension

    private def seqToRepr[A](it: Iterator[A]): Repr[A] =
        if it.hasNext then Cons(it.next(), _ => Future.successful(seqToRepr(it)))
        else Empty

end CStream
