package kyo.compat

import cats.effect.IO
import cats.effect.syntax.all.*
import cats.syntax.parallel.*
import scala.annotation.nowarn
import scala.concurrent.Future as ScalaFuture
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.NANOSECONDS
import scala.reflect.ClassTag

/** Underlying carrier is `cats.effect.IO[A]`. cats-effect has no `Frame` / `Trace` to propagate. `lift` and `lower` are identity since the
  * carrier is already a native `IO`. `CIO.acquireReleaseWith` is `IO.bracket`; release errors propagate through the IO error channel.
  * `CIO.cede` is `IO.cede`; `CIO.blocking { thunk }` is `IO.blocking`.
  */
opaque type CIO[+A] = IO[A]

object CIO:

    /** Wraps an already-constructed `IO` as a `CIO`. Identity on the carrier. */
    inline def lift[A](inline io: IO[A]): CIO[A] = io

    /** Suspends side-effecting code that produces an `IO`, deferring its construction to effect-evaluation time. */
    inline def deferLift[A](inline io: => IO[A]): CIO[A] = lift(IO.defer(io))

    /** Successful `CIO` carrying the given value. */
    inline def value[A](inline a: A): CIO[A] = lift(IO.pure(a))

    /** Successful `CIO[Unit]`. */
    inline def unit: CIO[Unit] = lift(IO.unit)

    /** Suspends a side-effecting thunk that produces a plain value, deferring its execution to effect-evaluation time. */
    inline def defer[A](inline thunk: => A): CIO[A] =
        lift(IO.delay(thunk))

    /** Failed `CIO` carrying `e` in the failure channel. */
    inline def fail(inline e: Throwable): CIO[Nothing] =
        lift(IO.raiseError(e))

    /** Lifts a `Try[A]`: `Success` succeeds with the value, `Failure` fails with the throwable. */
    inline def get[A](inline t: scala.util.Try[A]): CIO[A] =
        lift(IO.fromTry(t))

    /** Lifts a `scala.concurrent.Future[A]`; observes the future's eventual completion. Cancellation does not propagate back. */
    inline def fromScalaFuture[A](inline f: ScalaFuture[A]): CIO[A] =
        // fromFutureCancelable (not fromFuture): a scala Future cannot be cancelled, but the resulting IO must
        // still be cancelable so CIO.timeout / CIO.race can drop it. The cancel token is a no-op; without it
        // IO.fromFuture is uncancelable and cancelling a pending fromScalaFuture deadlocks the surrounding op.
        lift(IO.fromFutureCancelable(IO.pure(f -> IO.unit)))

    /** Pairs acquisition with release that runs on success, failure, and interrupt of `use`; release errors propagate through the IO error
      * channel (`IO.bracket`).
      */
    inline def acquireReleaseWith[A, B](
        inline acquire: CIO[A]
    )(
        inline release: A => CIO[Unit]
    )(
        inline use: A => CIO[B]
    ): CIO[B] =
        lift(IO.bracketFull(_ => acquire.lower)(use(_).lower)((a, _) => release(a).lower))

    /** Runs `c` and guarantees `cleanup` executes on every exit path. */
    inline def ensure[A](
        inline cleanup: CIO[Unit]
    )(
        inline c: CIO[A]
    ): CIO[A] =
        acquireReleaseWith(CIO.defer(()))(_ => cleanup)(_ => c)

    /** `CIO` that never completes. */
    inline def never: CIO[Nothing] =
        lift(IO.never)

    extension [A](inline self: CIO[A])

        /** Unwraps to the native `IO`. Identity on the carrier. */
        inline def lower: IO[A] = self

        /** Runs `handler` on failure to produce a recovery `CIO`. */
        inline def recover[A2 >: A](inline handler: Throwable => CIO[A2]): CIO[A2] =
            lift(self.lower.handleErrorWith(t => handler(t).lower))

        /** Collapses success and failure into a single `CIO[B]` via the respective handlers. */
        inline def fold[B](
            inline onSuccess: A => CIO[B],
            inline onFail: Throwable => CIO[B]
        ): CIO[B] =
            lift(self.lower.redeemWith(
                t => onFail(t).lower,
                a => onSuccess(a).lower
            ))

        /** Reifies failure as `Try`; the resulting `CIO` always succeeds. */
        inline def liftToTry: CIO[scala.util.Try[A]] =
            lift(self.lower.attempt.map {
                case Right(a) => scala.util.Success(a)
                case Left(t)  => scala.util.Failure(t)
            })

        /** Discards the success value; failure propagates. */
        inline def unit: CIO[Unit] =
            lift(self.lower.void)

        /** Falls back to `that` on any failure of `self`. */
        inline def orElse[A2 >: A](inline that: CIO[A2]): CIO[A2] =
            lift(self.lower.handleErrorWith(_ => that.lower))

        /** Rewrites the error value through `f`. */
        @nowarn("msg=anonymous")
        inline def mapError(inline f: Throwable => Throwable): CIO[A] =
            lift(self.lower.adaptError {
                case t => f(t)
            })

        /** Transforms the success value with a pure function. */
        inline def map[B](inline f: A => B): CIO[B] =
            lift(self.lower.map(f))

        /** Chains another `CIO` whose construction depends on the success value. */
        inline def flatMap[B](inline f: A => CIO[B]): CIO[B] =
            lift(self.lower.flatMap(a => f(a).lower))

        /** Materializes this `CIO` into a `scala.concurrent.Future[A]` using CE's default `IORuntime`. */
        inline def unsafeRun: scala.concurrent.Future[A] =
            self.lower.unsafeToFuture()(using cats.effect.unsafe.IORuntime.global)
    end extension

    /** Suspends the calling computation for `d`. */
    inline def sleep(inline d: FiniteDuration): CIO[Unit] =
        lift(IO.sleep(d))

    /** Wall-clock instant. */
    inline def now: CIO[java.time.Instant] =
        lift(IO.realTime.map(d => java.time.Instant.ofEpochMilli(d.toMillis)))

    /** Monotonic timestamp expressed as a `FiniteDuration` since a backend-defined origin; use for intervals, not wall-clock time. */
    inline def nowMonotonic: CIO[FiniteDuration] =
        lift(IO.monotonic.map(d => FiniteDuration(d.toNanos, NANOSECONDS)))

    /** Runs `c` with a deadline; resolves to `None` if `d` elapses first. */
    inline def timeout[A](inline d: FiniteDuration)(inline c: CIO[A]): CIO[Option[A]] =
        lift(c.lower.map(Option(_)).timeoutTo(d, IO.none[A]))

    /** Runs `c` with a deadline; fails with `e` if `d` elapses first. */
    inline def timeoutWithError[A](inline d: FiniteDuration)(inline e: Throwable)(inline c: CIO[A]): CIO[A] =
        lift(c.lower.timeoutTo(d, IO.raiseError(e)))

    /** Sleeps for `d` and then runs `c`. */
    inline def delay[A](inline d: FiniteDuration)(inline c: CIO[A]): CIO[A] =
        lift(c.lower.delayBy(d))

    /** Runs `a` and `b` in parallel and returns the first to complete successfully; the losing leg is interrupted natively. */
    // `IO.race` returns `Either[A, B]`; `.merge` narrows since both legs share type A.
    inline def race[A](
        inline a: CIO[A],
        inline b: CIO[A]
    ): CIO[A] =
        lift(IO.race(a.lower, b.lower).map(_.merge))

    /** Parallel map; `concurrency` caps in-flight items (unbounded by default). */
    inline def foreach[A, B](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(f: A => CIO[B]): CIO[CChunk[B]] =
        lift {
            val list = coll.toList
            val io =
                if concurrency == Int.MaxValue then list.parTraverse(a => f(a).lower)
                else IO.parTraverseN(concurrency)(list)(a => f(a).lower)
            io.map(lst => CChunk.lift(lst.toVector))
        }

    /** Parallel map that passes the element index to `f`; same concurrency semantics as `foreach`. */
    inline def foreachIndexed[A, B](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(f: (Int, A) => CIO[B]): CIO[CChunk[B]] =
        lift {
            val list = coll.toList.zipWithIndex
            val io =
                if concurrency == Int.MaxValue then list.parTraverse { case (a, i) => f(i, a).lower }
                else IO.parTraverseN(concurrency)(list) { case (a, i) => f(i, a).lower }
            io.map(lst => CChunk.lift(lst.toVector))
        }

    /** Runs `f` for its effects on each element and discards the results; same concurrency semantics as `foreach`. */
    inline def foreachDiscard[A](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(f: A => CIO[Any]): CIO[Unit] =
        lift {
            val list = coll.toList
            if concurrency == Int.MaxValue then list.parTraverse_(a => f(a).lower)
            else IO.parTraverseN_(concurrency)(list)(a => f(a).lower)
        }

    /** Concurrent predicate filtering; same concurrency semantics as `foreach`. */
    @nowarn("msg=anonymous")
    inline def filter[A](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(p: A => CIO[Boolean]): CIO[CChunk[A]] =
        lift {
            val list = coll.toList
            if concurrency == Int.MaxValue then
                list.parFilterA(a => p(a).lower).map(lst => CChunk.lift(lst.toVector))
            else
                IO.parTraverseN(concurrency)(list)(a => p(a).lower.map(b => a -> b))
                    .map(pairs => CChunk.lift(pairs.collect { case (a, true) => a }.toVector))
            end if
        }

    /** Sequences an `Iterable[CIO[A]]`; same concurrency semantics as `foreach`. */
    inline def collectAll[A](
        inline coll: Iterable[CIO[A]],
        inline concurrency: Int = Int.MaxValue
    ): CIO[CChunk[A]] =
        lift {
            val list = coll.toList.map(_.lower)
            if concurrency == Int.MaxValue then list.parSequence.map(lst => CChunk.lift(lst.toVector))
            else IO.parSequenceN(concurrency)(list).map(lst => CChunk.lift(lst.toVector))
        }

    /** Sequences and discards the results; same concurrency semantics as `foreach`. */
    inline def collectAllDiscard(
        inline coll: Iterable[CIO[Any]],
        inline concurrency: Int = Int.MaxValue
    ): CIO[Unit] =
        lift {
            val list = coll.toList.map(_.lower)
            if concurrency == Int.MaxValue then list.parSequence_
            else IO.parSequenceN_(concurrency)(list)
        }

    /** Bridges a one-shot completion callback into `CIO`; `register` receives a `Try[A] => Unit`. */
    inline def async[A](inline register: ((scala.util.Try[A] => Unit) => Unit)): CIO[A] =
        lift(IO.async_[A] { k =>
            val cb: scala.util.Try[A] => Unit = {
                case scala.util.Success(a) => k(Right(a))
                case scala.util.Failure(e) => k(Left(e))
            }
            register(cb)
        })

    /** Runs the thunk on CE's blocking pool (`IO.blocking`). */
    inline def blocking[A](inline thunk: => A): CIO[A] =
        lift(IO.blocking(thunk))

    /** Yields the current fiber (`IO.cede`). */
    inline def cede: CIO[Unit] =
        lift(IO.cede)

    /** Runs two computations in parallel and returns their results as a tuple. */
    // Arity 2 uses `IO.both`; arities 3–7 use `parTupled` from `cats.syntax.parallel.*`.
    inline def zip[A1, A2](
        inline a1: CIO[A1],
        inline a2: CIO[A2]
    ): CIO[(A1, A2)] =
        lift(IO.both(a1.lower, a2.lower))

    /** Runs three computations in parallel and returns their results as a tuple. */
    inline def zip[A1, A2, A3](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3]
    ): CIO[(A1, A2, A3)] =
        lift((a1.lower, a2.lower, a3.lower).parTupled)

    /** Runs four computations in parallel and returns their results as a tuple. */
    inline def zip[A1, A2, A3, A4](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4]
    ): CIO[(A1, A2, A3, A4)] =
        lift((a1.lower, a2.lower, a3.lower, a4.lower).parTupled)

    /** Runs five computations in parallel and returns their results as a tuple. */
    inline def zip[A1, A2, A3, A4, A5](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4],
        inline a5: CIO[A5]
    ): CIO[(A1, A2, A3, A4, A5)] =
        lift((a1.lower, a2.lower, a3.lower, a4.lower, a5.lower).parTupled)

    /** Runs six computations in parallel and returns their results as a tuple. */
    inline def zip[A1, A2, A3, A4, A5, A6](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4],
        inline a5: CIO[A5],
        inline a6: CIO[A6]
    ): CIO[(A1, A2, A3, A4, A5, A6)] =
        lift((a1.lower, a2.lower, a3.lower, a4.lower, a5.lower, a6.lower).parTupled)

    /** Runs seven computations in parallel and returns their results as a tuple. */
    inline def zip[A1, A2, A3, A4, A5, A6, A7](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4],
        inline a5: CIO[A5],
        inline a6: CIO[A6],
        inline a7: CIO[A7]
    ): CIO[(A1, A2, A3, A4, A5, A6, A7)] =
        lift((a1.lower, a2.lower, a3.lower, a4.lower, a5.lower, a6.lower, a7.lower).parTupled)

end CIO
