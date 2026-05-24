package kyo.compat

import java.util.concurrent.TimeUnit
import kyo.compat.internal.CompatScheduler
import kyo.compat.internal.LocalCtx
import scala.annotation.nowarn
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.NANOSECONDS
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

/** Underlying carrier is `LocalCtx => Future[A]`, a function from the immutable `LocalCtx` (threaded by `flatMap`/`let`) to a fresh
  * `Future[A]`. The Future ecosystem has no `Frame` / `Trace` to propagate. `lift` and `lower` wrap a plain `Future` by ignoring the ctx;
  * `CLocal.let`/`get`/`update` thread the ctx through the carrier — no `ExecutionContext` smuggling or `ThreadLocal` capture. Composition
  * and `defer` run on `scala.concurrent.ExecutionContext.parasitic` (inline on the completing thread, no hop); `CIO.blocking` is the only
  * operation that leaves the parasitic EC — it runs the thunk on `scala.concurrent.ExecutionContext.global` inside
  * `scala.concurrent.blocking`. The Future binding has no cancellation: `CIO.timeout` returns `None` on expiry but the inner computation
  * keeps running orphaned, and `CIO.race` returns the winner while the loser runs to completion. `CIO.never` blocks the calling fiber for
  * the lifetime of the process. `CIO.cede` schedules a zero-delay task through `CompatScheduler`, forcing a scheduling round-trip.
  * `unsafeRun` materializes the `LocalCtx => Future[A]` against an empty ctx.
  */
opaque type CIO[+A] = LocalCtx => Future[A]

object CIO:

    // Composition runs inline on the completing thread — no thread hops for pure plumbing.
    private val parasiticEc: ExecutionContext = scala.concurrent.ExecutionContext.parasitic

    /** Wraps an already-constructed `Future` as a `CIO`. Identity on the carrier. */
    inline def lift[A](inline f: Future[A]): CIO[A] =
        (_: LocalCtx) => f

    /** Suspends side-effecting code that produces a `Future`, deferring its construction to effect-evaluation time. */
    inline def deferLift[A](inline f: LocalCtx ?=> Future[A]): CIO[A] =
        (ctx: LocalCtx) => f(using ctx)

    /** Successful `CIO` carrying the given value. */
    inline def value[A](inline a: A): CIO[A] = (_: LocalCtx) => Future.successful(a)

    /** Successful `CIO[Unit]`. */
    inline def unit: CIO[Unit] = (_: LocalCtx) => Future.unit

    /** Failed `CIO` carrying `e` in the failure channel. */
    inline def fail(inline e: Throwable): CIO[Nothing] =
        (_: LocalCtx) => Future.failed(e)

    /** Suspends a side-effecting thunk that produces a plain value, deferring its execution to effect-evaluation time. */
    inline def defer[A](inline thunk: => A): CIO[A] =
        (_: LocalCtx) => Future(thunk)(using parasiticEc)

    /** Lifts a `Try[A]`: `Success` succeeds with the value, `Failure` fails with the throwable. */
    inline def get[A](inline t: Try[A]): CIO[A] =
        (_: LocalCtx) => Future.fromTry(t)

    /** Lifts a `scala.concurrent.Future[A]`; observes the future's eventual completion. */
    inline def fromScalaFuture[A](inline f: Future[A]): CIO[A] = lift(f)

    /** Pairs acquisition with release that runs on success and failure of `use`; release failures propagate as `acquireReleaseWith`'s
      * failed Future. Cancellation isn't part of the CIO surface — Future has no interrupt mechanism.
      */
    inline def acquireReleaseWith[A, B](
        inline acquire: CIO[A]
    )(
        release: A => CIO[Unit]
    )(
        inline use: A => CIO[B]
    ): CIO[B] =
        (ctx: LocalCtx) =>
            acquire(ctx).flatMap { a =>
                use(a)(ctx).transformWith {
                    case Success(b) => release(a)(ctx).map(_ => b)(using parasiticEc)
                    case Failure(t) => release(a)(ctx).transformWith(_ => Future.failed(t))(using parasiticEc)
                }(using parasiticEc)
            }(using parasiticEc)

    /** Runs `c` and guarantees `cleanup` executes on every exit path. */
    inline def ensure[A](
        inline cleanup: CIO[Unit]
    )(
        inline c: CIO[A]
    ): CIO[A] =
        acquireReleaseWith(CIO.defer(()))(_ => cleanup)(_ => c)

    /** `CIO` that never completes; blocks the calling fiber for the lifetime of the process (Future has no interrupt). */
    inline def never: CIO[Nothing] =
        (_: LocalCtx) => Promise[Nothing]().future

    extension [A](inline self: CIO[A])

        /** Unwraps to the underlying `LocalCtx ?=> Future[A]` carrier. */
        inline def lower: LocalCtx ?=> Future[A] =
            (ctx: LocalCtx) ?=> self(ctx)

        /** Runs `handler` on failure to produce a recovery `CIO`. */
        @nowarn("msg=anonymous")
        inline def recover[A2 >: A](inline handler: Throwable => CIO[A2]): CIO[A2] =
            (ctx: LocalCtx) =>
                self(ctx).recoverWith { case t => handler(t)(ctx) }(using parasiticEc)

        /** Collapses success and failure into a single `CIO[B]` via the respective handlers. */
        inline def fold[B](
            inline onSuccess: A => CIO[B],
            inline onFail: Throwable => CIO[B]
        ): CIO[B] =
            (ctx: LocalCtx) =>
                self(ctx).transformWith {
                    case Success(a) => onSuccess(a)(ctx)
                    case Failure(t) => onFail(t)(ctx)
                }(using parasiticEc)

        /** Reifies failure as `Try`; the resulting `CIO` always succeeds. */
        inline def liftToTry: CIO[Try[A]] =
            (ctx: LocalCtx) =>
                self(ctx).transform {
                    case Success(a) => Success[Try[A]](Success(a))
                    case Failure(t) => Success[Try[A]](Failure(t))
                }(using parasiticEc)

        /** Discards the success value; failure propagates. */
        inline def unit: CIO[Unit] =
            (ctx: LocalCtx) => self(ctx).map(_ => ())(using parasiticEc)

        /** Falls back to `that` on any failure of `self`. */
        @nowarn("msg=anonymous")
        inline def orElse[A2 >: A](inline that: CIO[A2]): CIO[A2] =
            (ctx: LocalCtx) =>
                self(ctx).recoverWith { case t: Throwable if NonFatal(t) => that(ctx) }(using parasiticEc)

        /** Rewrites the error value through `f`. */
        inline def mapError(inline f: Throwable => Throwable): CIO[A] =
            (ctx: LocalCtx) =>
                self(ctx).transform {
                    case Success(a) => Success(a)
                    case Failure(t) =>
                        Try(f(t)) match
                            case Success(t2) => Failure(t2)
                            case Failure(t2) => Failure(t2)
                }(using parasiticEc)

        /** Transforms the success value with a pure function. */
        inline def map[B](inline f: A => B): CIO[B] =
            (ctx: LocalCtx) => self(ctx).map(f)(using parasiticEc)

        /** Chains another `CIO` whose construction depends on the success value. */
        inline def flatMap[B](inline f: A => CIO[B]): CIO[B] =
            (ctx: LocalCtx) => self(ctx).flatMap(a => f(a)(ctx))(using parasiticEc)

        /** Materializes this `CIO` into a `scala.concurrent.Future[A]` by applying the carrier against an empty `LocalCtx`. */
        inline def unsafeRun: scala.concurrent.Future[A] = self(LocalCtx.empty)
    end extension

    /** Suspends the calling computation for `d` by scheduling a delayed task through `CompatScheduler`. */
    inline def sleep(inline d: FiniteDuration): CIO[Unit] =
        (_: LocalCtx) =>
            val p = Promise[Unit]()
            CompatScheduler.schedule(
                () =>
                    p.success(())
                    ()
                ,
                d.toNanos,
                TimeUnit.NANOSECONDS
            )
            p.future

    /** Wall-clock instant. */
    inline def now: CIO[java.time.Instant] = defer(java.time.Instant.now())

    /** Monotonic timestamp expressed as a `FiniteDuration` since a backend-defined origin; use for intervals, not wall-clock time. */
    inline def nowMonotonic: CIO[FiniteDuration] =
        defer(FiniteDuration(java.lang.System.nanoTime(), NANOSECONDS))

    /** Runs `c` with a deadline; resolves to `None` if `d` elapses first. The inner computation keeps running orphaned (Future has no
      * interrupt).
      */
    inline def timeout[A](inline d: FiniteDuration)(inline c: CIO[A]): CIO[Option[A]] =
        (ctx: LocalCtx) =>
            val p = Promise[Option[A]]()
            CompatScheduler.schedule(
                () =>
                    val _ = p.trySuccess(None)
                ,
                d.toNanos,
                TimeUnit.NANOSECONDS
            )
            c(ctx).onComplete {
                case Success(a) => val _ = p.trySuccess(Some(a))
                case Failure(t) => val _ = p.tryFailure(t)
            }(using parasiticEc)
            p.future

    /** Runs `c` with a deadline; fails with `e` if `d` elapses first. The inner computation keeps running orphaned (Future has no
      * interrupt).
      */
    inline def timeoutWithError[A](inline d: FiniteDuration)(inline e: Throwable)(inline c: CIO[A]): CIO[A] =
        (ctx: LocalCtx) =>
            val p = Promise[A]()
            CompatScheduler.schedule(
                () =>
                    val _ = p.tryFailure(e)
                ,
                d.toNanos,
                TimeUnit.NANOSECONDS
            )
            c(ctx).onComplete(p.tryComplete)(using parasiticEc)
            p.future

    /** Sleeps for `d` and then runs `c`. */
    inline def delay[A](inline d: FiniteDuration)(inline c: CIO[A]): CIO[A] =
        (ctx: LocalCtx) => CIO.sleep(d).lower(using ctx).flatMap(_ => c(ctx))(using parasiticEc)

    /** Runs `a` and `b` in parallel and returns the first to complete; the loser keeps running orphaned (Future has no interrupt). */
    inline def race[A](
        inline a: CIO[A],
        inline b: CIO[A]
    ): CIO[A] =
        (ctx: LocalCtx) =>
            val p = Promise[A]()
            a(ctx).onComplete(p.tryComplete)(using parasiticEc)
            b(ctx).onComplete(p.tryComplete)(using parasiticEc)
            p.future

    /** Non-blocking semaphore that gates dispatch without starvation. */
    private[compat] class FutureSemaphore(permits: Int):
        private val avail = new java.util.concurrent.atomic.AtomicInteger(permits)
        private val queue = new java.util.concurrent.ConcurrentLinkedQueue[Promise[Unit]]()

        def acquire(): Future[Unit] =
            if avail.getAndDecrement() > 0 then Future.unit
            else
                val p = Promise[Unit]()
                queue.offer(p)
                p.future

        def release(): Unit =
            if avail.incrementAndGet() <= 0 then
                val p = queue.poll()
                if p != null then
                    val _ = p.trySuccess(())
    end FutureSemaphore

    /** Run `work` in the semaphore-bounded path. Release fires on both success and failure so queued items drain. */
    private[compat] def semGated[R](
        sem: FutureSemaphore,
        work: => Future[R]
    ): Future[R] =
        sem.acquire().flatMap { _ =>
            work.transform { r =>
                sem.release()
                r
            }(using parasiticEc)
        }(using parasiticEc)

    /** Parallel map; `concurrency` caps in-flight items (unbounded by default, via a non-blocking `FutureSemaphore`). */
    inline def foreach[A, B](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(f: A => CIO[B]): CIO[CChunk[B]] =
        (ctx: LocalCtx) =>
            given ExecutionContext = parasiticEc
            val items              = coll.toVector
            if concurrency == Int.MaxValue then
                val futs = items.map(a => f(a)(ctx))
                Future.sequence(futs).map(CChunk.lift(_))
            else
                val sem  = new FutureSemaphore(concurrency)
                val futs = items.map(a => semGated(sem, f(a)(ctx)))
                Future.sequence(futs).map(CChunk.lift(_))
            end if

    /** Parallel map that passes the element index to `f`; same concurrency semantics as `foreach`. */
    inline def foreachIndexed[A, B](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(f: (Int, A) => CIO[B]): CIO[CChunk[B]] =
        (ctx: LocalCtx) =>
            given ExecutionContext = parasiticEc
            val items              = coll.toVector.zipWithIndex
            if concurrency == Int.MaxValue then
                val futs = items.map { case (a, i) => f(i, a)(ctx) }
                Future.sequence(futs).map(CChunk.lift(_))
            else
                val sem  = new FutureSemaphore(concurrency)
                val futs = items.map { case (a, i) => semGated(sem, f(i, a)(ctx)) }
                Future.sequence(futs).map(CChunk.lift(_))
            end if

    /** Runs `f` for its effects on each element and discards the results; same concurrency semantics as `foreach`. */
    inline def foreachDiscard[A](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(f: A => CIO[Any]): CIO[Unit] =
        (ctx: LocalCtx) =>
            given ExecutionContext = parasiticEc
            val items              = coll.toVector
            if concurrency == Int.MaxValue then
                val futs = items.map(a => f(a)(ctx))
                Future.sequence(futs).map(_ => ())
            else
                val sem  = new FutureSemaphore(concurrency)
                val futs = items.map(a => semGated(sem, f(a)(ctx)))
                Future.sequence(futs).map(_ => ())
            end if

    /** Concurrent predicate filtering; same concurrency semantics as `foreach`. */
    @nowarn("msg=anonymous")
    inline def filter[A](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(p: A => CIO[Boolean]): CIO[CChunk[A]] =
        (ctx: LocalCtx) =>
            given ExecutionContext = parasiticEc
            val items              = coll.toVector
            if concurrency == Int.MaxValue then
                val futs = items.map(a => p(a)(ctx))
                Future.sequence(futs).map(flags =>
                    CChunk.lift(items.zip(flags).collect { case (a, true) => a })
                )
            else
                val sem  = new FutureSemaphore(concurrency)
                val futs = items.map(a => semGated(sem, p(a)(ctx)))
                Future.sequence(futs).map(flags =>
                    CChunk.lift(items.zip(flags).collect { case (a, true) => a })
                )
            end if

    /** Sequences an `Iterable[CIO[A]]`; same concurrency semantics as `foreach`. */
    inline def collectAll[A](
        inline coll: Iterable[CIO[A]],
        inline concurrency: Int = Int.MaxValue
    ): CIO[CChunk[A]] =
        (ctx: LocalCtx) =>
            given ExecutionContext = parasiticEc
            val items              = coll.toVector
            if concurrency == Int.MaxValue then
                val futs = items.map(c => c(ctx))
                Future.sequence(futs).map(CChunk.lift(_))
            else
                val sem  = new FutureSemaphore(concurrency)
                val futs = items.map(c => semGated(sem, c(ctx)))
                Future.sequence(futs).map(CChunk.lift(_))
            end if

    /** Sequences and discards the results; same concurrency semantics as `foreach`. */
    inline def collectAllDiscard(
        inline coll: Iterable[CIO[Any]],
        inline concurrency: Int = Int.MaxValue
    ): CIO[Unit] =
        (ctx: LocalCtx) =>
            given ExecutionContext = parasiticEc
            val items              = coll.toVector
            if concurrency == Int.MaxValue then
                val futs = items.map(c => c(ctx))
                Future.sequence(futs).map(_ => ())
            else
                val sem  = new FutureSemaphore(concurrency)
                val futs = items.map(c => semGated(sem, c(ctx)))
                Future.sequence(futs).map(_ => ())
            end if

    /** Bridges a one-shot completion callback into `CIO`; `register` receives a `Try[A] => Unit`. */
    inline def async[A](inline register: ((Try[A] => Unit) => Unit)): CIO[A] =
        (_: LocalCtx) =>
            val p = Promise[A]()
            val cb: Try[A] => Unit = t =>
                val _ = p.tryComplete(t)
            try register(cb)
            catch
                case t: Throwable if NonFatal(t) =>
                    val _ = p.tryFailure(t)
            end try
            p.future

    /** Runs the thunk inside `scala.concurrent.blocking` on `scala.concurrent.ExecutionContext.global`. */
    inline def blocking[A](inline thunk: => A): CIO[A] =
        (_: LocalCtx) => Future(scala.concurrent.blocking(thunk))(using ExecutionContext.global)

    /** Yield by bouncing the continuation through `CompatScheduler` (a zero-delay schedule); the caller resumes on a scheduler thread,
      * breaking any synchronous recursion without occupying the blocking pool.
      */
    inline def cede: CIO[Unit] =
        (_: LocalCtx) =>
            val p = Promise[Unit]()
            CompatScheduler.schedule(
                () =>
                    p.success(())
                    ()
                ,
                0L,
                TimeUnit.NANOSECONDS
            )
            p.future

    /** Runs two computations in parallel and returns their results as a tuple. */
    // Each leg is started eagerly (`val fa = a(ctx)` etc.) to force parallelism.
    inline def zip[A, B](
        inline a: CIO[A],
        inline b: CIO[B]
    ): CIO[(A, B)] =
        (ctx: LocalCtx) =>
            val fa = a(ctx)
            val fb = b(ctx)
            fa.zip(fb)

    /** Runs three computations in parallel and returns their results as a tuple. */
    inline def zip[A, B, C](
        inline a: CIO[A],
        inline b: CIO[B],
        inline c: CIO[C]
    ): CIO[(A, B, C)] =
        (ctx: LocalCtx) =>
            val fa = a(ctx)
            val fb = b(ctx)
            val fc = c(ctx)
            fa.flatMap(x => fb.flatMap(y => fc.map(z => (x, y, z))(using parasiticEc))(using parasiticEc))(using parasiticEc)

    /** Runs four computations in parallel and returns their results as a tuple. */
    inline def zip[A, B, C, D](
        inline a: CIO[A],
        inline b: CIO[B],
        inline c: CIO[C],
        inline d: CIO[D]
    ): CIO[(A, B, C, D)] =
        (ctx: LocalCtx) =>
            val fa = a(ctx)
            val fb = b(ctx)
            val fc = c(ctx)
            val fd = d(ctx)
            fa.flatMap(x =>
                fb.flatMap(y =>
                    fc.flatMap(z => fd.map(w => (x, y, z, w))(using parasiticEc))(using parasiticEc)
                )(using parasiticEc)
            )(using parasiticEc)

    /** Runs five computations in parallel and returns their results as a tuple. */
    inline def zip[A, B, C, D, E1](
        inline a: CIO[A],
        inline b: CIO[B],
        inline c: CIO[C],
        inline d: CIO[D],
        inline e: CIO[E1]
    ): CIO[(A, B, C, D, E1)] =
        (ctx: LocalCtx) =>
            val fa = a(ctx)
            val fb = b(ctx)
            val fc = c(ctx)
            val fd = d(ctx)
            val fe = e(ctx)
            fa.flatMap(x =>
                fb.flatMap(y =>
                    fc.flatMap(z =>
                        fd.flatMap(w => fe.map(v => (x, y, z, w, v))(using parasiticEc))(using parasiticEc)
                    )(using parasiticEc)
                )(using parasiticEc)
            )(using parasiticEc)

    /** Runs six computations in parallel and returns their results as a tuple. */
    inline def zip[A, B, C, D, E1, F](
        inline a: CIO[A],
        inline b: CIO[B],
        inline c: CIO[C],
        inline d: CIO[D],
        inline e: CIO[E1],
        inline f: CIO[F]
    ): CIO[(A, B, C, D, E1, F)] =
        (ctx: LocalCtx) =>
            val fa = a(ctx)
            val fb = b(ctx)
            val fc = c(ctx)
            val fd = d(ctx)
            val fe = e(ctx)
            val ff = f(ctx)
            fa.flatMap(x =>
                fb.flatMap(y =>
                    fc.flatMap(z =>
                        fd.flatMap(w =>
                            fe.flatMap(v => ff.map(u => (x, y, z, w, v, u))(using parasiticEc))(using parasiticEc)
                        )(using parasiticEc)
                    )(using parasiticEc)
                )(using parasiticEc)
            )(using parasiticEc)

    /** Runs seven computations in parallel and returns their results as a tuple. */
    inline def zip[A, B, C, D, E1, F, G](
        inline a: CIO[A],
        inline b: CIO[B],
        inline c: CIO[C],
        inline d: CIO[D],
        inline e: CIO[E1],
        inline f: CIO[F],
        inline g: CIO[G]
    ): CIO[(A, B, C, D, E1, F, G)] =
        (ctx: LocalCtx) =>
            val fa = a(ctx)
            val fb = b(ctx)
            val fc = c(ctx)
            val fd = d(ctx)
            val fe = e(ctx)
            val ff = f(ctx)
            val fg = g(ctx)
            fa.flatMap(x =>
                fb.flatMap(y =>
                    fc.flatMap(z =>
                        fd.flatMap(w =>
                            fe.flatMap(v =>
                                ff.flatMap(u => fg.map(s => (x, y, z, w, v, u, s))(using parasiticEc))(using parasiticEc)
                            )(using parasiticEc)
                        )(using parasiticEc)
                    )(using parasiticEc)
                )(using parasiticEc)
            )(using parasiticEc)

end CIO
