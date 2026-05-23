package kyo.compat

import com.twitter.util.Future
import com.twitter.util.Promise
import com.twitter.util.Return
import com.twitter.util.Throw
import com.twitter.util.TimeoutException as TwTimeoutException
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.NANOSECONDS
import scala.jdk.FutureConverters.*
import scala.reflect.ClassTag
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

/** Underlying carrier is `() => com.twitter.util.Future[A]` — a cold thunk. `com.twitter.util.Future.apply` is eager, so the thunk defers
  * execution; each materialization re-runs the thunk for a fresh `Future`. The Twitter Future ecosystem has no `Frame` / `Trace` to
  * propagate. `lift` wraps an already-constructed `Future` and `lower` exposes the carrier thunk. `acquireReleaseWith`, `timeout`, and
  * `race` are hand-rolled — Twitter Future has no native acquire-release primitive — and release failures propagate as `Throw(t)`; `race`
  * interrupts the losing leg via `raise(CancellationException)`, while `timeout` uses `Future.raiseWithin` to propagate the same `raise`
  * signal on expiry. `cede` schedules a "run now" task via `twitterTimer.doLater(Duration.Zero)` (a real async boundary, since
  * `Future.sleep(Zero)` would short-circuit); `blocking` runs on `FuturePool.unboundedPool`; `unsafeRun` bridges the underlying
  * `com.twitter.util.Future[A]` to a `scala.concurrent.Future[A]`.
  */
opaque type CIO[+A] = () => Future[A]

object CIO:

    /** Wraps an already-constructed, pure `Future` value (no side effects in building the argument) as a `CIO`. Identity on the carrier. */
    inline def lift[A](inline f: Future[A]): CIO[A] = () => f

    /** Suspends side-effecting code that produces a `Future`; the body runs fresh on each materialization. */
    inline def deferLift[A](inline f: => Future[A]): CIO[A] = () => f

    /** Successful `CIO` carrying the given value. */
    inline def value[A](inline a: A): CIO[A] = lift(Future.value(a))

    /** Successful `CIO[Unit]`. */
    inline def unit: CIO[Unit] = lift(Future.Done)

    /** Failed `CIO` carrying `e` in the failure channel. */
    inline def fail(inline e: Throwable): CIO[Nothing] = lift(Future.exception(e))

    /** Suspends a side-effecting thunk that produces a plain value, deferring its execution to effect-evaluation time. */
    inline def defer[A](inline thunk: => A): CIO[A] =
        deferLift {
            try Future.value(thunk)
            catch case t: Throwable if NonFatal(t) => Future.exception(t)
        }

    /** Lifts a `Try[A]`: `Success` succeeds with the value, `Failure` fails with the throwable. */
    inline def get[A](inline t: Try[A]): CIO[A] =
        lift {
            t match
                case Success(a) => Future.value(a)
                case Failure(e) => Future.exception(e)
        }

    /** Lifts a `scala.concurrent.Future[A]`; observes the future's eventual completion. */
    inline def fromScalaFuture[A](inline f: scala.concurrent.Future[A]): CIO[A] =
        CompatFromCompletionStage.fromCompletionStage(f.asJava)

    /** Pairs an acquisition with a release that runs on success and failure of `use`; a release failure surfaces as `acquireReleaseWith`'s
      * `Throw(t)`.
      */
    inline def acquireReleaseWith[A, B](
        inline acquire: CIO[A]
    )(
        inline release: A => CIO[Unit]
    )(
        inline use: A => CIO[B]
    ): CIO[B] =
        deferLift {
            acquire.lower().flatMap { a =>
                val released = new java.util.concurrent.atomic.AtomicBoolean(false)
                def runRelease(): Future[Unit] =
                    if released.compareAndSet(false, true) then
                        val relFut =
                            try release(a).lower()
                            catch case t: Throwable if NonFatal(t) => Future.exception[Unit](t)
                        relFut.masked
                    else Future.Done
                end runRelease
                use(a).lower().transform { result =>
                    runRelease().transform { releaseResult =>
                        result match
                            case Return(b) =>
                                releaseResult match
                                    case Return(_) => Future.value(b)
                                    case Throw(t)  => Future.exception(t)
                            case Throw(t) => Future.exception(t)
                    }
                }
            }
        }

    /** Runs `c` and guarantees `cleanup` executes on every exit path. */
    inline def ensure[A](
        inline cleanup: CIO[Unit]
    )(
        inline c: CIO[A]
    ): CIO[A] =
        acquireReleaseWith(CIO.unit)(_ => cleanup)(_ => c)

    /** `CIO` that never completes. */
    inline def never: CIO[Nothing] = deferLift(new Promise[Nothing]())

    extension [A](inline self: CIO[A])

        /** Unwraps to the underlying `() => Future[A]` carrier thunk. */
        inline def lower: () => Future[A] = self

        /** Runs `handler` on failure to produce a recovery `CIO`. */
        inline def recover[A2 >: A](inline handler: Throwable => CIO[A2]): CIO[A2] =
            deferLift {
                self.lower().rescue {
                    case t => handler(t).lower()
                }
            }

        /** Collapses success and failure into a single `CIO[B]` via the respective handlers. */
        inline def fold[B](
            inline onSuccess: A => CIO[B],
            inline onFail: Throwable => CIO[B]
        ): CIO[B] =
            deferLift {
                self.lower().transform {
                    case Return(a) =>
                        try onSuccess(a).lower()
                        catch case t: Throwable if NonFatal(t) => Future.exception[B](t)
                    case Throw(t) =>
                        try onFail(t).lower()
                        catch case t2: Throwable if NonFatal(t2) => Future.exception[B](t2)
                }
            }

        /** Reifies failure as `Try`; the resulting `CIO` always succeeds. */
        inline def liftToTry: CIO[Try[A]] =
            deferLift {
                self.lower().transform {
                    case Return(a) => Future.value[Try[A]](Success(a))
                    case Throw(t)  => Future.value[Try[A]](Failure(t))
                }
            }

        /** Discards the success value; failure propagates. */
        inline def unit: CIO[Unit] =
            deferLift(self.lower().unit)

        /** Falls back to `that` on any failure of `self`. */
        inline def orElse[A2 >: A](inline that: CIO[A2]): CIO[A2] =
            deferLift {
                self.lower().rescue {
                    case t: Throwable if NonFatal(t) => that.lower()
                }
            }

        /** Rewrites the error value through `f`. */
        inline def mapError(inline f: Throwable => Throwable): CIO[A] =
            deferLift {
                self.lower().transform {
                    case Return(a) => Future.value(a)
                    case Throw(t) =>
                        try Future.exception(f(t))
                        catch case t2: Throwable if NonFatal(t2) => Future.exception(t2)
                }
            }

        /** Transforms the success value with a pure function. */
        inline def map[B](inline f: A => B): CIO[B] =
            deferLift(self.lower().map(f))

        /** Chains another `CIO` whose construction depends on the success value. */
        inline def flatMap[B](inline f: A => CIO[B]): CIO[B] =
            deferLift(self.lower().flatMap(a => f(a).lower()))

        /** Materializes this `CIO` into a `scala.concurrent.Future[A]` bridged from the underlying `com.twitter.util.Future[A]`. */
        inline def unsafeRun: scala.concurrent.Future[A] =
            val p = scala.concurrent.Promise[A]()
            val _ = self.lower().respond {
                case Return(a) => val _ = p.trySuccess(a)
                case Throw(t)  => val _ = p.tryFailure(t)
            }
            p.future
        end unsafeRun

    end extension

    private inline def toTwitterDuration(inline d: FiniteDuration): com.twitter.util.Duration =
        com.twitter.util.Duration.fromNanoseconds(d.toNanos)

    /** Suspends the calling computation for `d`. */
    inline def sleep(inline d: FiniteDuration): CIO[Unit] =
        deferLift(Future.sleep(toTwitterDuration(d))(using twitterTimer))

    /** Wall-clock instant. */
    inline def now: CIO[java.time.Instant] = defer(java.time.Instant.now())

    /** Monotonic timestamp expressed as a `FiniteDuration` since a backend-defined origin; use for intervals, not wall-clock time. */
    inline def nowMonotonic: CIO[FiniteDuration] =
        defer(FiniteDuration(java.lang.System.nanoTime(), NANOSECONDS))

    /** Runs `c` with a deadline; resolves to `None` if `d` elapses first and propagates an interrupt to the inner Future via raiseWithin.
      */
    inline def timeout[A](inline d: FiniteDuration)(inline c: CIO[A]): CIO[Option[A]] =
        deferLift {
            c.lower().raiseWithin(toTwitterDuration(d))(using twitterTimer).transform {
                case Return(a)                    => Future.value(Option(a))
                case Throw(_: TwTimeoutException) => Future.value(None: Option[A])
                case Throw(t)                     => Future.exception(t)
            }
        }

    /** Runs `c` with a deadline; on expiry raises `e` and propagates an interrupt to the inner Future via raiseWithin. */
    inline def timeoutWithError[A](inline d: FiniteDuration)(inline e: Throwable)(inline c: CIO[A]): CIO[A] =
        deferLift(c.lower().raiseWithin(toTwitterDuration(d), e)(using twitterTimer))

    /** Sleeps for `d` and then runs `c`. */
    inline def delay[A](inline d: FiniteDuration)(inline c: CIO[A]): CIO[A] =
        deferLift(Future.sleep(toTwitterDuration(d))(using twitterTimer).flatMap(_ => c.lower()))

    /** Races `a` and `b`; the loser is interrupted via raise(CancellationException). */
    inline def race[A](
        inline a: CIO[A],
        inline b: CIO[A]
    ): CIO[A] =
        deferLift {
            val fa           = a.lower()
            val fb           = b.lower()
            val p            = new Promise[A]()
            val cancelSignal = new java.util.concurrent.CancellationException("race: loser cancelled")
            val _ = fa.respond {
                case result =>
                    if p.updateIfEmpty(result) then fb.raise(cancelSignal)
            }
            val _ = fb.respond {
                case result =>
                    if p.updateIfEmpty(result) then fa.raise(cancelSignal)
            }
            // Propagate parent-fiber interrupt to both legs so that when the containing
            // CFiber is interrupted, both race legs also stop.
            p.setInterruptHandler { case t =>
                fa.raise(t)
                fb.raise(t)
            }
            p
        }

    /** Collect futures in parallel with interrupt cascade. The returned Promise propagates raise() to all constituent futures so that
      * parent-fiber interrupt stops all in-flight items.
      */
    private def parWithRaiseCascade[B](futs: Seq[Future[B]]): Future[Seq[B]] =
        val p      = new Promise[Seq[B]]()
        val joined = Future.collect(futs)
        val _ = joined.respond { r =>
            val _ = p.updateIfEmpty(r)
        }
        p.setInterruptHandler { case t =>
            futs.foreach(_.raise(t))
            val _ = p.updateIfEmpty(Throw(t))
        }
        p
    end parWithRaiseCascade

    /** Parallel map; `concurrency` caps in-flight items (unbounded by default, via `AsyncSemaphore`). */
    inline def foreach[A, B](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(f: A => CIO[B]): CIO[CChunk[B]] =
        deferLift {
            val items = coll.toVector
            val futs: Seq[Future[B]] =
                if concurrency == Int.MaxValue then items.map(a => f(a).lower())
                else
                    val sem = new com.twitter.concurrent.AsyncSemaphore(concurrency)
                    items.map(a => sem.acquireAndRun(f(a).lower()))
            parWithRaiseCascade(futs).map(s => CChunk.lift(s.toVector))
        }

    /** Parallel map that passes the element index to `f`; same concurrency semantics as `foreach`. */
    inline def foreachIndexed[A, B](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(f: (Int, A) => CIO[B]): CIO[CChunk[B]] =
        deferLift {
            val items = coll.toVector.zipWithIndex
            val futs: Seq[Future[B]] =
                if concurrency == Int.MaxValue then items.map { case (a, i) => f(i, a).lower() }
                else
                    val sem = new com.twitter.concurrent.AsyncSemaphore(concurrency)
                    items.map { case (a, i) => sem.acquireAndRun(f(i, a).lower()) }
            parWithRaiseCascade(futs).map(s => CChunk.lift(s.toVector))
        }

    /** Runs `f` for its effects on each element and discards the results; same concurrency semantics as `foreach`. */
    inline def foreachDiscard[A](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(f: A => CIO[Any]): CIO[Unit] =
        deferLift {
            val items = coll.toVector
            val futs: Seq[Future[Any]] =
                if concurrency == Int.MaxValue then items.map(a => f(a).lower())
                else
                    val sem = new com.twitter.concurrent.AsyncSemaphore(concurrency)
                    items.map(a => sem.acquireAndRun(f(a).lower()))
            parWithRaiseCascade(futs).map(_ => ())
        }

    /** Concurrent predicate filtering; same concurrency semantics as `foreach`. */
    inline def filter[A](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(p: A => CIO[Boolean]): CIO[CChunk[A]] =
        deferLift {
            val items = coll.toVector
            val futs: Seq[Future[Boolean]] =
                if concurrency == Int.MaxValue then items.map(a => p(a).lower())
                else
                    val sem = new com.twitter.concurrent.AsyncSemaphore(concurrency)
                    items.map(a => sem.acquireAndRun(p(a).lower()))
            parWithRaiseCascade(futs).map(flags =>
                CChunk.lift(items.zip(flags).collect { case (a, true) => a }.toVector)
            )
        }

    /** Sequences an `Iterable[CIO[A]]`; same concurrency semantics as `foreach`. */
    inline def collectAll[A](
        inline coll: Iterable[CIO[A]],
        inline concurrency: Int = Int.MaxValue
    ): CIO[CChunk[A]] =
        deferLift {
            val items = coll.toVector
            val futs: Seq[Future[A]] =
                if concurrency == Int.MaxValue then items.map(c => c.lower())
                else
                    val sem = new com.twitter.concurrent.AsyncSemaphore(concurrency)
                    items.map(c => sem.acquireAndRun(c.lower()))
            parWithRaiseCascade(futs).map(s => CChunk.lift(s.toVector))
        }

    /** Sequences and discards the results; same concurrency semantics as `foreach`. */
    inline def collectAllDiscard(
        inline coll: Iterable[CIO[Any]],
        inline concurrency: Int = Int.MaxValue
    ): CIO[Unit] =
        deferLift {
            val items = coll.toVector
            val futs: Seq[Future[Any]] =
                if concurrency == Int.MaxValue then items.map(c => c.lower())
                else
                    val sem = new com.twitter.concurrent.AsyncSemaphore(concurrency)
                    items.map(c => sem.acquireAndRun(c.lower()))
            parWithRaiseCascade(futs).map(_ => ())
        }

    /** Bridges a one-shot completion callback into `CIO`; `register` receives a `Try[A] => Unit`. */
    inline def async[A](inline register: ((Try[A] => Unit) => Unit)): CIO[A] =
        deferLift {
            val p = new Promise[A]()
            val cb: Try[A] => Unit = {
                case Success(a) =>
                    val _ = p.updateIfEmpty(Return(a))
                    ()
                case Failure(e) =>
                    val _ = p.updateIfEmpty(Throw(e))
                    ()
            }
            try register(cb)
            catch
                case t: Throwable if NonFatal(t) =>
                    val _ = p.updateIfEmpty(Throw(t))
            end try
            p
        }

    /** Runs the thunk on `FuturePool.unboundedPool`. */
    inline def blocking[A](inline thunk: => A): CIO[A] =
        deferLift(blockingFuturePool(thunk))

    /** Yields the calling fiber (twitterTimer.doLater(Duration.Zero) — Future.sleep(Zero) would short-circuit). */
    inline def cede: CIO[Unit] =
        // doLater(Zero) schedules a real "run now" task on the timer thread — a genuine async
        // boundary that lets the current fiber suspend. (Future.sleep(Zero) short-circuits to an
        // already-completed future and would not yield.)
        deferLift(twitterTimer.doLater(com.twitter.util.Duration.Zero)(()))

    /** Runs two computations in parallel and returns their results as a tuple. */
    inline def zip[A, B](
        inline a: CIO[A],
        inline b: CIO[B]
    ): CIO[(A, B)] =
        deferLift(a.lower().join(b.lower()))

    /** Runs three computations in parallel and returns their results as a tuple. */
    inline def zip[A, B, C](
        inline a: CIO[A],
        inline b: CIO[B],
        inline c: CIO[C]
    ): CIO[(A, B, C)] =
        deferLift(Future.join(a.lower(), b.lower(), c.lower()))

    /** Runs four computations in parallel and returns their results as a tuple. */
    inline def zip[A, B, C, D](
        inline a: CIO[A],
        inline b: CIO[B],
        inline c: CIO[C],
        inline d: CIO[D]
    ): CIO[(A, B, C, D)] =
        deferLift(Future.join(a.lower(), b.lower(), c.lower(), d.lower()))

    /** Runs five computations in parallel and returns their results as a tuple. */
    inline def zip[A, B, C, D, E1](
        inline a: CIO[A],
        inline b: CIO[B],
        inline c: CIO[C],
        inline d: CIO[D],
        inline e: CIO[E1]
    ): CIO[(A, B, C, D, E1)] =
        deferLift(Future.join(a.lower(), b.lower(), c.lower(), d.lower(), e.lower()))

    /** Runs six computations in parallel and returns their results as a tuple. */
    inline def zip[A, B, C, D, E1, F](
        inline a: CIO[A],
        inline b: CIO[B],
        inline c: CIO[C],
        inline d: CIO[D],
        inline e: CIO[E1],
        inline f: CIO[F]
    ): CIO[(A, B, C, D, E1, F)] =
        deferLift(Future.join(a.lower(), b.lower(), c.lower(), d.lower(), e.lower(), f.lower()))

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
        deferLift(Future.join(a.lower(), b.lower(), c.lower(), d.lower(), e.lower(), f.lower(), g.lower()))

end CIO
