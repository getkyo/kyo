package kyo.compat

import scala.concurrent.Future as ScalaFuture
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.NANOSECONDS
import scala.reflect.ClassTag
import scala.util.control.NonFatal
import zio.*

/** Underlying carrier is `ZIO[Any, Throwable, A]`.
  *
  * `defer` maps throws to defects via `.orDie`. `acquireReleaseWith` runs release on success, failure, and interrupt via
  * `ZIO.acquireReleaseWith`; release Throwables are reified as defects via `.orDie` so they propagate through ZIO's cause channel.
  * `fold`/`liftToTry`/`ignore` use `foldCauseZIO`/`foldZIO` rather than ZIO's `CanFail`-gated convenience methods, which allows
  * `E = Nothing` to work. `timeoutWithError` swaps arg order relative to ZIO's native `timeoutFail(e)(d)`.
  */
opaque type CIO[+A] = ZIO[Any, Throwable, A]

object CIO:

    inline def lift[A](inline z: ZIO[Any, Throwable, A]): CIO[A] = z

    /** Suspends side-effecting code that produces a `ZIO`, deferring its construction to effect-evaluation time. */
    inline def deferLift[A](inline z: => ZIO[Any, Throwable, A])(using inline trace: Trace): CIO[A] =
        lift(ZIO.suspendSucceed(z))

    inline def value[A](inline a: A): CIO[A] = lift(ZIO.succeed(a))

    inline def unit: CIO[Unit] = lift(ZIO.unit)

    inline def defer[A](inline thunk: => A)(using inline trace: Trace): CIO[A] =
        lift(ZIO.attempt(thunk))

    inline def fail(inline e: Throwable)(using inline trace: Trace): CIO[Nothing] =
        lift(ZIO.fail(e))

    inline def get[A](inline t: scala.util.Try[A])(using inline trace: Trace): CIO[A] =
        lift(ZIO.fromTry(t))

    inline def fromScalaFuture[A](inline f: ScalaFuture[A])(using inline trace: Trace): CIO[A] =
        lift(ZIO.fromFuture(_ => f))

    inline def acquireReleaseWith[A, B](
        inline acquire: CIO[A]
    )(
        inline release: A => CIO[Unit]
    )(
        inline use: A => CIO[B]
    )(using inline trace: Trace): CIO[B] =
        lift(ZIO.acquireReleaseWith(acquire.lower)(a => release(a).lower.orDie)(a => use(a).lower))

    inline def ensure[A](
        inline cleanup: CIO[Unit]
    )(
        inline c: CIO[A]
    )(using inline trace: Trace): CIO[A] =
        acquireReleaseWith(CIO.defer(()))(_ => cleanup)(_ => c)

    inline def never: CIO[Nothing] =
        lift(ZIO.never)

    extension [A](inline self: CIO[A])

        inline def lower: ZIO[Any, Throwable, A] = self

        inline def recover[A2 >: A](inline handler: Throwable => CIO[A2])(
            using inline trace: Trace
        ): CIO[A2] =
            lift(self.lower.catchAll(e =>
                try handler(e).lower
                catch case t: Throwable if NonFatal(t) => ZIO.fail(t)
            ))

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

        inline def liftToTry(using inline trace: Trace): CIO[scala.util.Try[A]] =
            lift(self.lower.foldZIO(
                e => ZIO.succeed(scala.util.Failure(e): scala.util.Try[A]),
                a => ZIO.succeed(scala.util.Success(a): scala.util.Try[A])
            ))

        inline def unit(using inline trace: Trace): CIO[Unit] =
            lift(self.lower.unit)

        inline def orElse[A2 >: A](inline that: CIO[A2])(
            using inline trace: Trace
        ): CIO[A2] =
            lift(self.lower.orElse(that.lower))

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

        inline def map[B](inline f: A => B)(using inline trace: Trace): CIO[B] =
            lift(self.lower.map(f))

        inline def flatMap[B](inline f: A => CIO[B])(using inline trace: Trace): CIO[B] =
            lift(self.lower.flatMap(a => f(a).lower))

        inline def unsafeRun: scala.concurrent.Future[A] =
            Unsafe.unsafe { implicit u =>
                zio.Runtime.default.unsafe.runToFuture(self.lower)
            }
    end extension

    private inline def toZioDuration(inline d: FiniteDuration): zio.Duration =
        zio.Duration.fromNanos(d.toNanos)

    inline def sleep(inline d: FiniteDuration)(using inline trace: Trace): CIO[Unit] =
        lift(ZIO.sleep(toZioDuration(d)))

    inline def now(using inline trace: Trace): CIO[java.time.Instant] =
        lift(Clock.instant)

    inline def nowMonotonic(using inline trace: Trace): CIO[FiniteDuration] =
        lift(Clock.nanoTime.map(ns => FiniteDuration(ns, NANOSECONDS)))

    inline def timeout[A](inline d: FiniteDuration)(inline c: CIO[A])(
        using inline trace: Trace
    ): CIO[Option[A]] =
        lift(c.lower.timeout(toZioDuration(d)))

    inline def timeoutWithError[A](inline d: FiniteDuration)(inline e: Throwable)(inline c: CIO[A])(
        using inline trace: Trace
    ): CIO[A] =
        lift(c.lower.timeoutFail(e)(toZioDuration(d)))

    inline def delay[A](inline d: FiniteDuration)(inline c: CIO[A])(
        using inline trace: Trace
    ): CIO[A] =
        lift(c.lower.delay(toZioDuration(d)))

    inline def race[A](
        inline a: CIO[A],
        inline b: CIO[A]
    )(using inline trace: Trace): CIO[A] =
        lift(a.lower.race(b.lower))

    inline def zip[A1, A2](
        inline a1: CIO[A1],
        inline a2: CIO[A2]
    )(using inline trace: Trace): CIO[(A1, A2)] =
        lift(a1.lower.zipPar(a2.lower))

    inline def zip[A1, A2, A3](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3]
    )(using inline trace: Trace): CIO[(A1, A2, A3)] =
        lift(a1.lower <&> a2.lower <&> a3.lower)

    inline def zip[A1, A2, A3, A4](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4]
    )(using inline trace: Trace): CIO[(A1, A2, A3, A4)] =
        lift(a1.lower <&> a2.lower <&> a3.lower <&> a4.lower)

    inline def zip[A1, A2, A3, A4, A5](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4],
        inline a5: CIO[A5]
    )(using inline trace: Trace): CIO[(A1, A2, A3, A4, A5)] =
        lift(a1.lower <&> a2.lower <&> a3.lower <&> a4.lower <&> a5.lower)

    inline def zip[A1, A2, A3, A4, A5, A6](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4],
        inline a5: CIO[A5],
        inline a6: CIO[A6]
    )(using inline trace: Trace): CIO[(A1, A2, A3, A4, A5, A6)] =
        lift(a1.lower <&> a2.lower <&> a3.lower <&> a4.lower <&> a5.lower <&> a6.lower)

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

    inline def blocking[A](inline thunk: => A)(using inline trace: Trace): CIO[A] =
        lift(ZIO.attemptBlocking(thunk))

    inline def cede(using inline trace: Trace): CIO[Unit] =
        lift(ZIO.yieldNow)

end CIO
