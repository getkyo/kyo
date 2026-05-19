package kyo.compat

import java.util.concurrent.TimeUnit
import kyo.compat.internal.CompatScheduler
import kyo.compat.internal.LocalCtx
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
  * `Future[A]`. Composition and `defer` run on `scala.concurrent.ExecutionContext.parasitic` (inline on the completing thread, no hop);
  * `CIO.blocking` is the only operation that leaves the parasitic EC — it runs the thunk on `scala.concurrent.ExecutionContext.global`
  * inside `scala.concurrent.blocking`. No `using ExecutionContext` plumbing in the public surface.
  */
opaque type CIO[+A] = LocalCtx => Future[A]

object CIO:

    // Composition runs inline on the completing thread — no thread hops for pure plumbing.
    private val parasiticEc: ExecutionContext = scala.concurrent.ExecutionContext.parasitic

    /** Wrap an already-constructed, pure `Future` value in a CIO. For side-effecting `Future`-producing code use [[CIO.deferLift]]. */
    inline def lift[A](inline f: Future[A]): CIO[A] =
        (_: LocalCtx) => f

    /** Suspend side-effecting code that produces a `Future`; re-evaluated per `LocalCtx` on every run. */
    inline def deferLift[A](inline f: LocalCtx ?=> Future[A]): CIO[A] =
        (ctx: LocalCtx) => f(using ctx)

    /** Wrap an already-evaluated, side-effect-free value in a CIO. For side-effecting expressions use [[CIO.defer]] instead.
      */
    inline def value[A](inline a: A): CIO[A] = (_: LocalCtx) => Future.successful(a)

    inline def unit: CIO[Unit] = (_: LocalCtx) => Future.unit

    inline def fail(inline e: Throwable): CIO[Nothing] =
        (_: LocalCtx) => Future.failed(e)

    inline def defer[A](inline thunk: => A): CIO[A] =
        (_: LocalCtx) => Future(thunk)(using parasiticEc)

    inline def get[A](inline t: Try[A]): CIO[A] =
        (_: LocalCtx) => Future.fromTry(t)

    inline def fromScalaFuture[A](inline f: Future[A]): CIO[A] = lift(f)

    /** Release runs on success and failure of `use`. Cancellation isn't part of the CIO surface — Future has no interrupt mechanism. */
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
                    case Success(b) => release(a)(ctx).map(_ => b)(parasiticEc)
                    case Failure(t) => release(a)(ctx).transformWith(_ => Future.failed(t))(parasiticEc)
                }(parasiticEc)
            }(parasiticEc)

    inline def ensure[A](
        inline cleanup: CIO[Unit]
    )(
        inline c: CIO[A]
    ): CIO[A] =
        acquireReleaseWith(CIO.defer(()))(_ => cleanup)(_ => c)

    inline def never: CIO[Nothing] =
        (_: LocalCtx) => Promise[Nothing]().future

    extension [A](inline self: CIO[A])

        inline def lower: LocalCtx ?=> Future[A] =
            (ctx: LocalCtx) ?=> self(ctx)

        inline def recover[A2 >: A](inline handler: Throwable => CIO[A2]): CIO[A2] =
            (ctx: LocalCtx) =>
                self(ctx).recoverWith { case t => handler(t)(ctx) }(parasiticEc)

        inline def fold[B](
            inline onSuccess: A => CIO[B],
            inline onFail: Throwable => CIO[B]
        ): CIO[B] =
            (ctx: LocalCtx) =>
                self(ctx).transformWith {
                    case Success(a) => onSuccess(a)(ctx)
                    case Failure(t) => onFail(t)(ctx)
                }(parasiticEc)

        inline def liftToTry: CIO[Try[A]] =
            (ctx: LocalCtx) =>
                self(ctx).transform {
                    case Success(a) => Success[Try[A]](Success(a))
                    case Failure(t) => Success[Try[A]](Failure(t))
                }(parasiticEc)

        inline def unit: CIO[Unit] =
            (ctx: LocalCtx) => self(ctx).map(_ => ())(parasiticEc)

        inline def orElse[A2 >: A](inline that: CIO[A2]): CIO[A2] =
            (ctx: LocalCtx) =>
                self(ctx).recoverWith { case t: Throwable if NonFatal(t) => that(ctx) }(parasiticEc)

        inline def mapError(inline f: Throwable => Throwable): CIO[A] =
            (ctx: LocalCtx) =>
                self(ctx).transform {
                    case Success(a) => Success(a)
                    case Failure(t) =>
                        Try(f(t)) match
                            case Success(t2) => Failure(t2)
                            case Failure(t2) => Failure(t2)
                }(parasiticEc)

        inline def map[B](inline f: A => B): CIO[B] =
            (ctx: LocalCtx) => self(ctx).map(f)(parasiticEc)

        inline def flatMap[B](inline f: A => CIO[B]): CIO[B] =
            (ctx: LocalCtx) => self(ctx).flatMap(a => f(a)(ctx))(parasiticEc)

        inline def unsafeRun: scala.concurrent.Future[A] = self(LocalCtx.empty)
    end extension

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

    inline def now: CIO[java.time.Instant] = defer(java.time.Instant.now())

    inline def nowMonotonic: CIO[FiniteDuration] =
        defer(FiniteDuration(java.lang.System.nanoTime(), NANOSECONDS))

    /** Winner returned; the loser keeps running orphaned (Future has no interrupt). */
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
            }(parasiticEc)
            p.future

    /** On expiry, the returned Future fails with `e`. The inner CIO keeps running orphaned (Future has no interrupt). */
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
            c(ctx).onComplete(p.tryComplete)(parasiticEc)
            p.future

    inline def delay[A](inline d: FiniteDuration)(inline c: CIO[A]): CIO[A] =
        (ctx: LocalCtx) => CIO.sleep(d)(ctx).flatMap(_ => c(ctx))(parasiticEc)

    /** Winner returned; the loser keeps running orphaned. */
    inline def race[A](
        inline a: CIO[A],
        inline b: CIO[A]
    ): CIO[A] =
        (ctx: LocalCtx) =>
            val p = Promise[A]()
            a(ctx).onComplete(p.tryComplete)(parasiticEc)
            b(ctx).onComplete(p.tryComplete)(parasiticEc)
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
            }(parasiticEc)
        }(parasiticEc)

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

    // Each leg is started eagerly (`val fa = a(ctx)` etc.) to force parallelism.
    inline def zip[A, B](
        inline a: CIO[A],
        inline b: CIO[B]
    ): CIO[(A, B)] =
        (ctx: LocalCtx) =>
            val fa = a(ctx)
            val fb = b(ctx)
            fa.zip(fb)

    inline def zip[A, B, C](
        inline a: CIO[A],
        inline b: CIO[B],
        inline c: CIO[C]
    ): CIO[(A, B, C)] =
        (ctx: LocalCtx) =>
            val fa = a(ctx)
            val fb = b(ctx)
            val fc = c(ctx)
            fa.flatMap(x => fb.flatMap(y => fc.map(z => (x, y, z))(parasiticEc))(parasiticEc))(parasiticEc)

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
                    fc.flatMap(z => fd.map(w => (x, y, z, w))(parasiticEc))(parasiticEc)
                )(parasiticEc)
            )(parasiticEc)

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
                        fd.flatMap(w => fe.map(v => (x, y, z, w, v))(parasiticEc))(parasiticEc)
                    )(parasiticEc)
                )(parasiticEc)
            )(parasiticEc)

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
                            fe.flatMap(v => ff.map(u => (x, y, z, w, v, u))(parasiticEc))(parasiticEc)
                        )(parasiticEc)
                    )(parasiticEc)
                )(parasiticEc)
            )(parasiticEc)

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
                                ff.flatMap(u => fg.map(s => (x, y, z, w, v, u, s))(parasiticEc))(parasiticEc)
                            )(parasiticEc)
                        )(parasiticEc)
                    )(parasiticEc)
                )(parasiticEc)
            )(parasiticEc)

end CIO
