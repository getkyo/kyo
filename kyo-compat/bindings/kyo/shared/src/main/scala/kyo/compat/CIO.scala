package kyo.compat

import kyo.*
import kyo.kernel.`<`
import scala.concurrent.Future as ScalaFuture
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.NANOSECONDS
import scala.util.control.NonFatal

/** Underlying carrier is `A < (Abort[Throwable] & Async)`. E is covariant at the surface (hidden invariance from Abort). acquireReleaseWith
  * uses Scope.acquireRelease; release Aborts/panics surface as acquireReleaseWith failures via Kyo's effect channels.
  * foreach/filter/collectAll accept an optional `concurrency` parameter (default `Int.MaxValue`, i.e. unbounded). cede and blocking are
  * no-ops (Kyo scheduler is preemptive).
  */
opaque type CIO[+A] = A < (Abort[Throwable] & Async)

object CIO:

    inline def lift[A](inline v: A < (Abort[Throwable] & Async)): CIO[A] = v

    /** Suspends side-effecting code that produces a Kyo computation, deferring its construction to effect-evaluation time. */
    inline def deferLift[A](inline v: => (A < (Abort[Throwable] & Async)))(using inline frame: Frame): CIO[A] =
        CIO.lift(Sync.defer(v))

    inline def value[A](inline a: A): CIO[A] = CIO.lift(a)

    inline def unit: CIO[Unit] = CIO.lift(())

    inline def defer[A](inline thunk: => A)(using inline frame: Frame): CIO[A] =
        CIO.lift(Sync.defer(thunk))

    inline def fail(inline e: Throwable)(using inline frame: Frame): CIO[Nothing] =
        CIO.lift(Abort.fail(e))

    inline def get[A](inline t: scala.util.Try[A])(using inline frame: Frame): CIO[A] =
        CIO.lift(Abort.get[A](t))

    inline def fromScalaFuture[A](inline f: ScalaFuture[A])(using inline frame: Frame): CIO[A] =
        CIO.lift(Async.fromFuture(f))

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

    inline def ensure[A](
        inline cleanup: CIO[Unit]
    )(
        inline c: CIO[A]
    )(using inline frame: Frame): CIO[A] =
        CIO.lift(Scope.run(`<`.map(Scope.ensure(cleanup.lower))(_ => c.lower)))

    inline def never: CIO[Nothing] =
        CIO.lift(Async.never[Nothing])

    extension [A](inline self: CIO[A])

        inline def lower: A < (Abort[Throwable] & Async) = self

        inline def recover[A2 >: A](inline handler: Throwable => CIO[A2])(
            using inline frame: Frame
        ): CIO[A2] =
            CIO.lift(Abort.recover[Throwable](e =>
                try handler(e).lower
                catch case t: Throwable if NonFatal(t) => Abort.fail(t)
            )(self.lower))

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

        inline def liftToTry(using inline frame: Frame): CIO[scala.util.Try[A]] =
            CIO.lift(Abort.run[Throwable](self.lower).map {
                case Result.Success(a) => scala.util.Success(a)
                case Result.Failure(e) => scala.util.Failure(e)
                case Result.Panic(t)   => scala.util.Failure(t)
            })

        inline def unit(using inline frame: Frame): CIO[Unit] =
            CIO.lift(<.unit(self.lower))

        inline def orElse[A2 >: A](inline that: CIO[A2])(
            using inline frame: Frame
        ): CIO[A2] =
            CIO.lift(Abort.recover[Throwable](_ => that.lower)(self.lower))

        inline def mapError(inline f: Throwable => Throwable)(
            using inline frame: Frame
        ): CIO[A] =
            CIO.lift(Abort.recover[Throwable](e => Abort.fail(f(e)))(self.lower))

        inline def map[B](inline f: A => B)(using inline frame: Frame): CIO[B] =
            CIO.lift(<.map(self.lower)(f))

        inline def flatMap[B](inline f: A => CIO[B])(using inline frame: Frame): CIO[B] =
            CIO.lift(`<`.flatMap(self.lower)(a => f(a).lower))

        inline def unsafeRun(using inline frame: Frame): scala.concurrent.Future[A] =
            import AllowUnsafe.embrace.danger
            Sync.Unsafe.evalOrThrow(<.map(Fiber.initUnscoped(self.lower))(_.toFuture))

    end extension

    inline def sleep(inline d: FiniteDuration)(using inline frame: Frame): CIO[Unit] =
        CIO.lift(Async.sleep(Duration.fromScala(d)))

    inline def now(using inline frame: Frame): CIO[java.time.Instant] =
        CIO.lift(Clock.nowWith(_.toJava))

    inline def nowMonotonic(using inline frame: Frame): CIO[FiniteDuration] =
        CIO.lift(Clock.nowMonotonic.map(d => FiniteDuration(kyo.Duration.toNanos(d), NANOSECONDS)))

    inline def timeout[A](inline d: FiniteDuration)(inline c: CIO[A])(
        using inline frame: Frame
    ): CIO[Option[A]] =
        CIO.lift(
            Async.timeout(Duration.fromScala(d))(c.lower.map(Option(_)))
                .handle(Abort.recover[Timeout](_ => Option.empty))
        )

    inline def timeoutWithError[A](inline d: FiniteDuration)(inline e: Throwable)(inline c: CIO[A])(
        using inline frame: Frame
    ): CIO[A] =
        CIO.lift(Async.timeoutWithError(Duration.fromScala(d), Result.Failure(e))(c.lower))

    inline def delay[A](inline d: FiniteDuration)(inline c: CIO[A])(
        using inline frame: Frame
    ): CIO[A] =
        CIO.lift(Async.delay(Duration.fromScala(d))(c.lower))

    inline def race[A](
        inline a: CIO[A],
        inline b: CIO[A]
    )(using inline frame: Frame): CIO[A] =
        CIO.lift(Async.race(a.lower, b.lower))

    inline def foreach[A, B](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(inline f: A => CIO[B])(
        using inline frame: Frame
    ): CIO[CChunk[B]] =
        CIO.lift(Async.foreach(coll, concurrency)(a => f(a).lower).map(CChunk.lift(_)))

    inline def foreachIndexed[A, B](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(inline f: (Int, A) => CIO[B])(
        using inline frame: Frame
    ): CIO[CChunk[B]] =
        CIO.lift(Async.foreachIndexed(coll, concurrency)((i, a) => f(i, a).lower).map(CChunk.lift(_)))

    inline def foreachDiscard[A](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(inline f: A => CIO[Any])(
        using inline frame: Frame
    ): CIO[Unit] =
        CIO.lift(Async.foreachDiscard(coll, concurrency)(a => f(a).lower))

    inline def filter[A](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(inline p: A => CIO[Boolean])(
        using inline frame: Frame
    ): CIO[CChunk[A]] =
        CIO.lift(Async.filter(coll, concurrency)(a => p(a).lower).map(CChunk.lift(_)))

    inline def collectAll[A](
        inline coll: Iterable[CIO[A]],
        inline concurrency: Int = Int.MaxValue
    )(
        using inline frame: Frame
    ): CIO[CChunk[A]] =
        CIO.lift(Async.collectAll(coll.map(_.lower), concurrency).map(CChunk.lift(_)))

    inline def collectAllDiscard(
        inline coll: Iterable[CIO[Any]],
        inline concurrency: Int = Int.MaxValue
    )(
        using inline frame: Frame
    ): CIO[Unit] =
        CIO.lift(Async.collectAllDiscard(coll.map(_.lower), concurrency))

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

    inline def blocking[A](inline thunk: => A)(using inline frame: Frame): CIO[A] =
        CIO.lift(Sync.defer(thunk))

    inline def cede: CIO[Unit] = Sync.defer(())

    inline def zip[A1, A2](
        inline a1: CIO[A1],
        inline a2: CIO[A2]
    )(using inline frame: Frame): CIO[(A1, A2)] =
        CIO.lift(Async.zip(a1.lower, a2.lower))

    inline def zip[A1, A2, A3](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3]
    )(using inline frame: Frame): CIO[(A1, A2, A3)] =
        CIO.lift(Async.zip(a1.lower, a2.lower, a3.lower))

    inline def zip[A1, A2, A3, A4](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4]
    )(using inline frame: Frame): CIO[(A1, A2, A3, A4)] =
        CIO.lift(Async.zip(a1.lower, a2.lower, a3.lower, a4.lower))

    inline def zip[A1, A2, A3, A4, A5](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4],
        inline a5: CIO[A5]
    )(using inline frame: Frame): CIO[(A1, A2, A3, A4, A5)] =
        CIO.lift(Async.zip(a1.lower, a2.lower, a3.lower, a4.lower, a5.lower))

    inline def zip[A1, A2, A3, A4, A5, A6](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4],
        inline a5: CIO[A5],
        inline a6: CIO[A6]
    )(using inline frame: Frame): CIO[(A1, A2, A3, A4, A5, A6)] =
        CIO.lift(Async.zip(a1.lower, a2.lower, a3.lower, a4.lower, a5.lower, a6.lower))

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
