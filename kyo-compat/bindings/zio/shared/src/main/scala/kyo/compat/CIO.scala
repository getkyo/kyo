package kyo.compat

import scala.concurrent.Future as ScalaFuture
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.NANOSECONDS
import scala.reflect.ClassTag
import scala.util.control.NonFatal
import zio.*

/** Underlying carrier is `ZIO[Any, Throwable, A]`. Operations propagate ZIO `Trace` through `(using inline trace: Trace)` on every entry
  * point. `lift` and `lower` are identity since the carrier is already a native `ZIO`. `CIO.defer` lowers to `ZIO.attempt`; escaped
  * exceptions land in the typed `Throwable` error channel. `CIO.acquireReleaseWith` reifies a release `Throwable` as a defect via `.orDie`
  * so it propagates through ZIO's cause channel. `CIO.cede` is `ZIO.yieldNow`; `CIO.blocking { thunk }` is `ZIO.attemptBlocking`.
  */
opaque type CIO[+A] = ZIO[Any, Throwable, A]

object CIO:

    /** Wraps an already-constructed `ZIO` as a `CIO`. Identity on the carrier. */
    inline def lift[A](inline z: ZIO[Any, Throwable, A]): CIO[A] = z

    /** Suspends side-effecting code that produces a `ZIO`, deferring its construction to effect-evaluation time. */
    inline def deferLift[A](inline z: => ZIO[Any, Throwable, A])(using inline trace: Trace): CIO[A] =
        lift(ZIO.suspendSucceed(z))

    /** Successful `CIO` carrying the given value. */
    inline def value[A](inline a: A): CIO[A] = lift(ZIO.succeed(a))

    /** Successful `CIO[Unit]`. */
    inline def unit: CIO[Unit] = lift(ZIO.unit)

    /** Suspends a side-effecting thunk that produces a plain value; escaped exceptions land in the typed Throwable error channel
      * (`ZIO.attempt`).
      */
    inline def defer[A](inline thunk: => A)(using inline trace: Trace): CIO[A] =
        lift(ZIO.attempt(thunk))

    /** Failed `CIO` carrying `e` in the failure channel. */
    inline def fail(inline e: Throwable)(using inline trace: Trace): CIO[Nothing] =
        lift(ZIO.fail(e))

    /** Lifts a `Try[A]`: `Success` succeeds with the value, `Failure` fails with the throwable. */
    inline def get[A](inline t: scala.util.Try[A])(using inline trace: Trace): CIO[A] =
        lift(ZIO.fromTry(t))

    /** Lifts a `scala.concurrent.Future[A]`; observes the future's eventual completion. Cancellation does not propagate back. */
    inline def fromScalaFuture[A](inline f: ScalaFuture[A])(using inline trace: Trace): CIO[A] =
        lift(ZIO.fromFuture(_ => f))

    /** Pairs acquisition with release that runs on success, failure, and interrupt of `use`; release Throwables propagate as defects via
      * `.orDie` through ZIO's cause channel.
      */
    inline def acquireReleaseWith[A, B](
        inline acquire: CIO[A]
    )(
        inline release: A => CIO[Unit]
    )(
        inline use: A => CIO[B]
    )(using inline trace: Trace): CIO[B] =
        lift(ZIO.acquireReleaseWith(acquire.lower)(a => release(a).lower.orDie)(a => use(a).lower))

    /** Runs `c` and guarantees `cleanup` executes on every exit path. */
    inline def ensure[A](
        inline cleanup: CIO[Unit]
    )(
        inline c: CIO[A]
    )(using inline trace: Trace): CIO[A] =
        acquireReleaseWith(CIO.defer(()))(_ => cleanup)(_ => c)

    /** `CIO` that never completes. */
    inline def never: CIO[Nothing] =
        lift(ZIO.never)

    extension [A](inline self: CIO[A])

        /** Unwraps to the native `ZIO`. Identity on the carrier. */
        inline def lower: ZIO[Any, Throwable, A] = self

        /** Runs `handler` on failure to produce a recovery `CIO`. */
        inline def recover[A2 >: A](inline handler: Throwable => CIO[A2])(
            using inline trace: Trace
        ): CIO[A2] =
            lift(self.lower.catchAll(e =>
                try handler(e).lower
                catch case t: Throwable if NonFatal(t) => ZIO.fail(t)
            ))

        /** Collapses success and failure into a single `CIO[B]` via the respective handlers. */
        inline def fold[B](
            inline onSuccess: A => CIO[B],
            inline onFail: Throwable => CIO[B]
        )(using inline trace: Trace): CIO[B] =
            lift(self.lower.foldZIO(
                e =>
                    try onFail(e).lower
                    catch case t: Throwable if NonFatal(t) => ZIO.fail(t),
                a =>
                    try onSuccess(a).lower
                    catch case t: Throwable if NonFatal(t) => ZIO.fail(t)
            ))

        /** Reifies failure as `Try`; the resulting `CIO` always succeeds. */
        inline def liftToTry(using inline trace: Trace): CIO[scala.util.Try[A]] =
            lift(self.lower.foldZIO(
                e => ZIO.succeed(scala.util.Failure(e): scala.util.Try[A]),
                a => ZIO.succeed(scala.util.Success(a): scala.util.Try[A])
            ))

        /** Discards the success value; failure propagates. */
        inline def unit(using inline trace: Trace): CIO[Unit] =
            lift(self.lower.unit)

        /** Falls back to `that` on any failure of `self`. */
        inline def orElse[A2 >: A](inline that: CIO[A2])(
            using inline trace: Trace
        ): CIO[A2] =
            lift(self.lower.orElse(that.lower))

        /** Rewrites the error value through `f`. */
        inline def mapError(inline f: Throwable => Throwable)(
            using inline trace: Trace
        ): CIO[A] =
            lift(self.lower.foldZIO(
                e =>
                    try
                        val mapped = f(e)
                        ZIO.fail(mapped)
                    catch case t: Throwable if NonFatal(t) => ZIO.fail(t),
                a => ZIO.succeed(a)
            ))

        /** Transforms the success value with a pure function. */
        inline def map[B](inline f: A => B)(using inline trace: Trace): CIO[B] =
            lift(self.lower.map(f))

        /** Chains another `CIO` whose construction depends on the success value. */
        inline def flatMap[B](inline f: A => CIO[B])(using inline trace: Trace): CIO[B] =
            lift(self.lower.flatMap(a => f(a).lower))

        /** Materializes this `CIO` into a `scala.concurrent.Future[A]` using ZIO's default runtime. */
        inline def unsafeRun: scala.concurrent.Future[A] =
            Unsafe.unsafe { implicit u =>
                zio.Runtime.default.unsafe.runToFuture(self.lower)
            }
    end extension

    private inline def toZioDuration(inline d: FiniteDuration): zio.Duration =
        zio.Duration.fromNanos(d.toNanos)

    /** Suspends the calling computation for `d`. */
    inline def sleep(inline d: FiniteDuration)(using inline trace: Trace): CIO[Unit] =
        lift(ZIO.sleep(toZioDuration(d)))

    /** Wall-clock instant. */
    inline def now(using inline trace: Trace): CIO[java.time.Instant] =
        lift(Clock.instant)

    /** Monotonic timestamp expressed as a `FiniteDuration` since a backend-defined origin; use for intervals, not wall-clock time. */
    inline def nowMonotonic(using inline trace: Trace): CIO[FiniteDuration] =
        lift(Clock.nanoTime.map(ns => FiniteDuration(ns, NANOSECONDS)))

    /** Runs `c` with a deadline; resolves to `None` if `d` elapses first. */
    inline def timeout[A](inline d: FiniteDuration)(inline c: CIO[A])(
        using inline trace: Trace
    ): CIO[Option[A]] =
        lift(c.lower.timeout(toZioDuration(d)))

    /** Runs `c` with a deadline; fails with `e` if `d` elapses first. */
    inline def timeoutWithError[A](inline d: FiniteDuration)(inline e: Throwable)(inline c: CIO[A])(
        using inline trace: Trace
    ): CIO[A] =
        lift(c.lower.timeoutFail(e)(toZioDuration(d)))

    /** Sleeps for `d` and then runs `c`. */
    inline def delay[A](inline d: FiniteDuration)(inline c: CIO[A])(
        using inline trace: Trace
    ): CIO[A] =
        lift(c.lower.delay(toZioDuration(d)))

    /** Runs `a` and `b` in parallel and returns the first to complete successfully. */
    inline def race[A](
        inline a: CIO[A],
        inline b: CIO[A]
    )(using inline trace: Trace): CIO[A] =
        lift(a.lower.race(b.lower))

    /** Runs two computations in parallel and returns their results as a tuple. */
    inline def zip[A1, A2](
        inline a1: CIO[A1],
        inline a2: CIO[A2]
    )(using inline trace: Trace): CIO[(A1, A2)] =
        lift(a1.lower.zipPar(a2.lower))

    /** Runs three computations in parallel and returns their results as a tuple. */
    inline def zip[A1, A2, A3](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3]
    )(using inline trace: Trace): CIO[(A1, A2, A3)] =
        lift(a1.lower <&> a2.lower <&> a3.lower)

    /** Runs four computations in parallel and returns their results as a tuple. */
    inline def zip[A1, A2, A3, A4](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4]
    )(using inline trace: Trace): CIO[(A1, A2, A3, A4)] =
        lift(a1.lower <&> a2.lower <&> a3.lower <&> a4.lower)

    /** Runs five computations in parallel and returns their results as a tuple. */
    inline def zip[A1, A2, A3, A4, A5](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4],
        inline a5: CIO[A5]
    )(using inline trace: Trace): CIO[(A1, A2, A3, A4, A5)] =
        lift(a1.lower <&> a2.lower <&> a3.lower <&> a4.lower <&> a5.lower)

    /** Runs six computations in parallel and returns their results as a tuple. */
    inline def zip[A1, A2, A3, A4, A5, A6](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4],
        inline a5: CIO[A5],
        inline a6: CIO[A6]
    )(using inline trace: Trace): CIO[(A1, A2, A3, A4, A5, A6)] =
        lift(a1.lower <&> a2.lower <&> a3.lower <&> a4.lower <&> a5.lower <&> a6.lower)

    /** Runs seven computations in parallel and returns their results as a tuple. */
    inline def zip[A1, A2, A3, A4, A5, A6, A7](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4],
        inline a5: CIO[A5],
        inline a6: CIO[A6],
        inline a7: CIO[A7]
    )(using inline trace: Trace): CIO[(A1, A2, A3, A4, A5, A6, A7)] =
        lift(
            a1.lower <&> a2.lower <&> a3.lower <&> a4.lower <&> a5.lower <&> a6.lower <&> a7.lower
        )

    /** Parallel map; `concurrency` caps in-flight items (unbounded by default). */
    inline def foreach[A, B](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(inline f: A => CIO[B])(
        using inline trace: Trace
    ): CIO[CChunk[B]] =
        lift {
            val par = ZIO.foreachPar(coll)(a => f(a).lower).map(it => CChunk.lift(zio.Chunk.fromIterable(it)))
            if concurrency == Int.MaxValue then par else par.withParallelism(concurrency)
        }

    /** Parallel map that passes the element index to `f`; same concurrency semantics as `foreach`. */
    inline def foreachIndexed[A, B](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(inline f: (Int, A) => CIO[B])(
        using inline trace: Trace
    ): CIO[CChunk[B]] =
        lift {
            val par = ZIO.foreachPar(coll.zipWithIndex) { case (a, i) => f(i, a).lower }
                .map(it => CChunk.lift(zio.Chunk.fromIterable(it)))
            if concurrency == Int.MaxValue then par else par.withParallelism(concurrency)
        }

    /** Runs `f` for its effects on each element and discards the results; same concurrency semantics as `foreach`. */
    inline def foreachDiscard[A](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(inline f: A => CIO[Any])(
        using inline trace: Trace
    ): CIO[Unit] =
        lift {
            val par = ZIO.foreachParDiscard(coll)(a => f(a).lower)
            if concurrency == Int.MaxValue then par else par.withParallelism(concurrency)
        }

    /** Concurrent predicate filtering; same concurrency semantics as `foreach`. */
    inline def filter[A](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(inline p: A => CIO[Boolean])(
        using inline trace: Trace
    ): CIO[CChunk[A]] =
        lift {
            val par = ZIO.filterPar(coll)(a => p(a).lower).map(it => CChunk.lift(zio.Chunk.fromIterable(it)))
            if concurrency == Int.MaxValue then par else par.withParallelism(concurrency)
        }

    /** Sequences an `Iterable[CIO[A]]`; same concurrency semantics as `foreach`. */
    inline def collectAll[A](
        inline coll: Iterable[CIO[A]],
        inline concurrency: Int = Int.MaxValue
    )(
        using inline trace: Trace
    ): CIO[CChunk[A]] =
        lift {
            val par = ZIO.collectAllPar(coll.map(_.lower).toList).map(it => CChunk.lift(zio.Chunk.fromIterable(it)))
            if concurrency == Int.MaxValue then par else par.withParallelism(concurrency)
        }

    /** Sequences and discards the results; same concurrency semantics as `foreach`. */
    inline def collectAllDiscard(
        inline coll: Iterable[CIO[Any]],
        inline concurrency: Int = Int.MaxValue
    )(
        using inline trace: Trace
    ): CIO[Unit] =
        lift {
            val par = ZIO.collectAllParDiscard(coll.map(_.lower))
            if concurrency == Int.MaxValue then par else par.withParallelism(concurrency)
        }

    /** Bridges a one-shot completion callback into `CIO`; `register` receives a `Try[A] => Unit`. */
    // The compat surface uses `Try[A] => Unit` for the callback; ZIO uses `ZIO[R, E, A] => Unit`.
    inline def async[A](inline register: ((scala.util.Try[A] => Unit) => Unit))(
        using inline trace: Trace
    ): CIO[A] =
        lift(ZIO.async[Any, Throwable, A] { (k: ZIO[Any, Throwable, A] => Unit) =>
            val cb: scala.util.Try[A] => Unit = {
                case scala.util.Success(a) => k(ZIO.succeed(a))
                case scala.util.Failure(e) => k(ZIO.fail(e))
            }
            register(cb)
        })

    /** Runs the thunk on ZIO's blocking pool (`ZIO.attemptBlocking`). */
    inline def blocking[A](inline thunk: => A)(using inline trace: Trace): CIO[A] =
        lift(ZIO.attemptBlocking(thunk))

    /** Yields the current fiber (`ZIO.yieldNow`). */
    inline def cede(using inline trace: Trace): CIO[Unit] =
        lift(ZIO.yieldNow)

end CIO
