package kyo.compat

import kyo.*
import kyo.kernel.`<`
import scala.concurrent.Future as ScalaFuture
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.NANOSECONDS
import scala.util.control.NonFatal

/** Underlying carrier is `A < (Abort[Throwable] & Async)`. Operations propagate kyo `Frame` through `(using inline frame: Frame)` on every
  * entry point. `lift` and `lower` are identity since the carrier is already a native Kyo computation. E is covariant at the surface
  * (hidden invariance from Abort). acquireReleaseWith uses Scope.acquireRelease; release Aborts/panics surface as acquireReleaseWith
  * failures via Kyo's effect channels. foreach/filter/collectAll accept an optional `concurrency` parameter (default `Int.MaxValue`, i.e.
  * unbounded). cede and blocking are no-ops (Kyo scheduler is preemptive).
  */
opaque type CIO[+A] = A < (Abort[Throwable] & Async)

object CIO:

    /** Wraps an already-constructed Kyo computation as a `CIO`. Identity on the carrier. */
    inline def lift[A](inline v: A < (Abort[Throwable] & Async)): CIO[A] = v

    /** Suspends side-effecting code that produces a Kyo computation, deferring its construction to effect-evaluation time. */
    inline def deferLift[A](inline v: => (A < (Abort[Throwable] & Async)))(using inline frame: Frame): CIO[A] =
        CIO.lift(Sync.defer(v))

    /** Successful `CIO` carrying the given value. */
    inline def value[A](inline a: A): CIO[A] = CIO.lift(a)

    /** Successful `CIO[Unit]`. */
    inline def unit: CIO[Unit] = CIO.lift(())

    /** Suspends a side-effecting thunk that produces a plain value, deferring its execution to effect-evaluation time. */
    inline def defer[A](inline thunk: => A)(using inline frame: Frame): CIO[A] =
        CIO.lift(Sync.defer(thunk))

    /** Failed `CIO` carrying `e` in the failure channel. */
    inline def fail(inline e: Throwable)(using inline frame: Frame): CIO[Nothing] =
        CIO.lift(Abort.fail(e))

    /** Lifts a `Try[A]`: `Success` succeeds with the value, `Failure` fails with the throwable. */
    inline def get[A](inline t: scala.util.Try[A])(using inline frame: Frame): CIO[A] =
        CIO.lift(Abort.get[A](t))

    /** Lifts a `scala.concurrent.Future[A]`; observes the future's eventual completion. Cancellation does not propagate back. */
    inline def fromScalaFuture[A](inline f: ScalaFuture[A])(using inline frame: Frame): CIO[A] =
        CIO.lift(Async.fromFuture(f))

    /** Pairs an acquisition with a release that runs on success and failure of `use`. On kyo a release failure is logged via `kyo.logs` and
      * `use`'s value wins.
      */
    inline def acquireReleaseWith[A, B](
        inline acquire: CIO[A]
    )(
        inline release: A => CIO[Unit]
    )(
        inline use: A => CIO[B]
    )(using inline frame: Frame): CIO[B] =
        CIO.lift {
            Scope.run {
                Scope.acquireRelease(acquire.lower)(release(_).lower).map(use(_).lower)
            }
        }
    end acquireReleaseWith

    /** Runs `c` and guarantees `cleanup` executes on every exit path. */
    inline def ensure[A](
        inline cleanup: CIO[Unit]
    )(
        inline c: CIO[A]
    )(using inline frame: Frame): CIO[A] =
        CIO.lift(Scope.run(`<`.map(Scope.ensure(cleanup.lower))(_ => c.lower)))

    /** `CIO` that never completes. */
    inline def never: CIO[Nothing] =
        CIO.lift(Async.never[Nothing])

    extension [A](inline self: CIO[A])

        /** Unwraps to the native Kyo carrier. Identity on the carrier. */
        inline def lower: A < (Abort[Throwable] & Async) = self

        /** Runs `handler` on failure to produce a recovery `CIO`. */
        inline def recover[A2 >: A](inline handler: Throwable => CIO[A2])(
            using inline frame: Frame
        ): CIO[A2] =
            CIO.lift(Abort.recover[Throwable](e =>
                try handler(e).lower
                catch case t: Throwable if NonFatal(t) => Abort.fail(t)
            )(self.lower))

        /** Collapses success and failure into a single `CIO[B]` via the respective handlers. */
        inline def fold[B](
            inline onSuccess: A => CIO[B],
            inline onFail: Throwable => CIO[B]
        )(using inline frame: Frame): CIO[B] =
            CIO.lift(
                Abort.fold[Throwable](
                    (a: A) =>
                        try onSuccess(a).lower
                        catch case t: Throwable if NonFatal(t) => Abort.fail(t),
                    (e: Throwable) =>
                        try onFail(e).lower
                        catch case t: Throwable if NonFatal(t) => Abort.fail(t)
                )(self.lower)
            )

        /** Reifies failure as `Try`; the resulting `CIO` always succeeds. */
        inline def liftToTry(using inline frame: Frame): CIO[scala.util.Try[A]] =
            CIO.lift(Abort.run[Throwable](self.lower).map {
                case Result.Success(a) => scala.util.Success(a)
                case Result.Failure(e) => scala.util.Failure(e)
                case Result.Panic(t)   => scala.util.Failure(t)
            })

        /** Discards the success value; failure propagates. */
        inline def unit(using inline frame: Frame): CIO[Unit] =
            CIO.lift(<.unit(self.lower))

        /** Falls back to `that` on any failure of `self`. */
        inline def orElse[A2 >: A](inline that: CIO[A2])(
            using inline frame: Frame
        ): CIO[A2] =
            CIO.lift(Abort.recover[Throwable](_ => that.lower)(self.lower))

        /** Rewrites the error value through `f`. */
        inline def mapError(inline f: Throwable => Throwable)(
            using inline frame: Frame
        ): CIO[A] =
            CIO.lift(Abort.recover[Throwable](e => Abort.fail(f(e)))(self.lower))

        /** Transforms the success value with a pure function. */
        inline def map[B](inline f: A => B)(using inline frame: Frame): CIO[B] =
            CIO.lift(<.map(self.lower)(f))

        /** Chains another `CIO` whose construction depends on the success value. */
        inline def flatMap[B](inline f: A => CIO[B])(using inline frame: Frame): CIO[B] =
            CIO.lift(`<`.flatMap(self.lower)(a => f(a).lower))

        /** Materializes this `CIO` into a `scala.concurrent.Future[A]` using kyo's default runtime. */
        inline def unsafeRun(using inline frame: Frame): scala.concurrent.Future[A] =
            import AllowUnsafe.embrace.danger
            Sync.Unsafe.evalOrThrow(<.map(Fiber.initUnscoped(self.lower))(_.toFuture))

    end extension

    /** Suspends the calling computation for `d`. */
    inline def sleep(inline d: FiniteDuration)(using inline frame: Frame): CIO[Unit] =
        CIO.lift(Async.sleep(Duration.fromScala(d)))

    /** Wall-clock instant. */
    inline def now(using inline frame: Frame): CIO[java.time.Instant] =
        CIO.lift(Clock.nowWith(_.toJava))

    /** Monotonic timestamp expressed as a `FiniteDuration` since a backend-defined origin; use for intervals, not wall-clock time. */
    inline def nowMonotonic(using inline frame: Frame): CIO[FiniteDuration] =
        CIO.lift(Clock.nowMonotonic.map(d => FiniteDuration(kyo.Duration.toNanos(d), NANOSECONDS)))

    /** Runs `c` with a deadline; resolves to `None` if `d` elapses first. */
    inline def timeout[A](inline d: FiniteDuration)(inline c: CIO[A])(
        using inline frame: Frame
    ): CIO[Option[A]] =
        CIO.lift(
            Async.timeout(Duration.fromScala(d))(c.lower.map(Option(_)))
                .handle(Abort.recover[Timeout](_ => Option.empty))
        )

    /** Runs `c` with a deadline; fails with `e` if `d` elapses first. */
    inline def timeoutWithError[A](inline d: FiniteDuration)(inline e: Throwable)(inline c: CIO[A])(
        using inline frame: Frame
    ): CIO[A] =
        CIO.lift(Async.timeoutWithError(Duration.fromScala(d), Result.Failure(e))(c.lower))

    /** Sleeps for `d` and then runs `c`. */
    inline def delay[A](inline d: FiniteDuration)(inline c: CIO[A])(
        using inline frame: Frame
    ): CIO[A] =
        CIO.lift(Async.delay(Duration.fromScala(d))(c.lower))

    /** Runs `a` and `b` in parallel and returns the first to complete successfully. */
    inline def race[A](
        inline a: CIO[A],
        inline b: CIO[A]
    )(using inline frame: Frame): CIO[A] =
        CIO.lift(Async.race(a.lower, b.lower))

    /** Parallel map; `concurrency` caps in-flight items (unbounded by default). */
    inline def foreach[A, B](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(inline f: A => CIO[B])(
        using inline frame: Frame
    ): CIO[CChunk[B]] =
        CIO.lift(Async.foreach(coll, concurrency)(a => f(a).lower).map(CChunk.lift(_)))

    /** Parallel map that passes the element index to `f`; same concurrency semantics as `foreach`. */
    inline def foreachIndexed[A, B](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(inline f: (Int, A) => CIO[B])(
        using inline frame: Frame
    ): CIO[CChunk[B]] =
        CIO.lift(Async.foreachIndexed(coll, concurrency)((i, a) => f(i, a).lower).map(CChunk.lift(_)))

    /** Runs `f` for its effects on each element and discards the results; same concurrency semantics as `foreach`. */
    inline def foreachDiscard[A](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(inline f: A => CIO[Any])(
        using inline frame: Frame
    ): CIO[Unit] =
        CIO.lift(Async.foreachDiscard(coll, concurrency)(a => f(a).lower))

    /** Concurrent predicate filtering; same concurrency semantics as `foreach`. */
    inline def filter[A](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(inline p: A => CIO[Boolean])(
        using inline frame: Frame
    ): CIO[CChunk[A]] =
        CIO.lift(Async.filter(coll, concurrency)(a => p(a).lower).map(CChunk.lift(_)))

    /** Sequences an `Iterable[CIO[A]]`; same concurrency semantics as `foreach`. */
    inline def collectAll[A](
        inline coll: Iterable[CIO[A]],
        inline concurrency: Int = Int.MaxValue
    )(
        using inline frame: Frame
    ): CIO[CChunk[A]] =
        CIO.lift(Async.collectAll(coll.map(_.lower), concurrency).map(CChunk.lift(_)))

    /** Sequences and discards the results; same concurrency semantics as `foreach`. */
    inline def collectAllDiscard(
        inline coll: Iterable[CIO[Any]],
        inline concurrency: Int = Int.MaxValue
    )(
        using inline frame: Frame
    ): CIO[Unit] =
        CIO.lift(Async.collectAllDiscard(coll.map(_.lower), concurrency))

    /** Bridges a one-shot completion callback into `CIO`; `register` receives a `Try[A] => Unit`. */
    inline def async[A](inline register: ((scala.util.Try[A] => Unit) => Unit))(
        using inline frame: Frame
    ): CIO[A] =
        CIO.lift {
            Sync.Unsafe.defer {
                val p = Promise.Unsafe.init[A, Abort[Throwable]]()
                val cb: scala.util.Try[A] => Unit = {
                    case scala.util.Success(a) => p.completeDiscard(Result.succeed(a))
                    case scala.util.Failure(t) => p.completeDiscard(Result.fail(t))
                }
                try register(cb)
                catch case t: Throwable if NonFatal(t) => p.completeDiscard(Result.fail(t))
                Fiber.get(p.safe)
            }
        }

    /** Runs `thunk` on the kyo scheduler (no-op wrapper on kyo — the scheduler auto-detects blocking). */
    inline def blocking[A](inline thunk: => A)(using inline frame: Frame): CIO[A] =
        CIO.lift(Sync.defer(thunk))

    /** Yields the current fiber (no-op on kyo — the scheduler is preemptive). */
    inline def cede: CIO[Unit] = Sync.defer(())

    /** Runs two computations in parallel and returns their results as a tuple. */
    inline def zip[A1, A2](
        inline a1: CIO[A1],
        inline a2: CIO[A2]
    )(using inline frame: Frame): CIO[(A1, A2)] =
        CIO.lift(Async.zip(a1.lower, a2.lower))

    /** Runs three computations in parallel and returns their results as a tuple. */
    inline def zip[A1, A2, A3](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3]
    )(using inline frame: Frame): CIO[(A1, A2, A3)] =
        CIO.lift(Async.zip(a1.lower, a2.lower, a3.lower))

    /** Runs four computations in parallel and returns their results as a tuple. */
    inline def zip[A1, A2, A3, A4](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4]
    )(using inline frame: Frame): CIO[(A1, A2, A3, A4)] =
        CIO.lift(Async.zip(a1.lower, a2.lower, a3.lower, a4.lower))

    /** Runs five computations in parallel and returns their results as a tuple. */
    inline def zip[A1, A2, A3, A4, A5](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4],
        inline a5: CIO[A5]
    )(using inline frame: Frame): CIO[(A1, A2, A3, A4, A5)] =
        CIO.lift(Async.zip(a1.lower, a2.lower, a3.lower, a4.lower, a5.lower))

    /** Runs six computations in parallel and returns their results as a tuple. */
    inline def zip[A1, A2, A3, A4, A5, A6](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4],
        inline a5: CIO[A5],
        inline a6: CIO[A6]
    )(using inline frame: Frame): CIO[(A1, A2, A3, A4, A5, A6)] =
        CIO.lift(Async.zip(a1.lower, a2.lower, a3.lower, a4.lower, a5.lower, a6.lower))

    /** Runs seven computations in parallel and returns their results as a tuple. */
    inline def zip[A1, A2, A3, A4, A5, A6, A7](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4],
        inline a5: CIO[A5],
        inline a6: CIO[A6],
        inline a7: CIO[A7]
    )(using inline frame: Frame): CIO[(A1, A2, A3, A4, A5, A6, A7)] =
        CIO.lift(Async.zip(
            a1.lower,
            a2.lower,
            a3.lower,
            a4.lower,
            a5.lower,
            a6.lower,
            a7.lower
        ))

end CIO
