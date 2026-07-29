package kyo

import kyo.internal.*
import scala.annotation.tailrec

/** A synchronization primitive that controls concurrency and rate limiting with configurable admission policies.
  *
  * Meter provides a structured mechanism for controlling access to shared resources, acting as a gatekeeper for concurrent operations. It
  * supports different concurrency control models through its factory methods:
  *
  *   - `initMutex`: Creates a binary semaphore allowing only one operation at a time, ideal for protecting critical sections
  *   - `initSemaphore`: Creates a counter-based control limiting concurrent operations, balancing throughput with resource constraints
  *   - `initRateLimiter`: Creates a time-based control limiting operations to a specified rate, preventing overload while maintaining
  *     throughput
  *
  * All Meter implementations can be configured as reentrant (default) or non-reentrant:
  *
  *   - Reentrant meters allow nested calls from the same fiber, avoiding deadlocks in recursive scenarios
  *   - Non-reentrant meters block nested calls from the same fiber, enforcing stricter concurrency guarantees
  *
  * Meters can be combined into pipelines with `Meter.pipeline`, creating composite admission policies that enforce multiple constraints.
  * This allows building complex access control patterns like "limit to 10 concurrent operations but no more than 100 per second."
  *
  * @see
  *   [[kyo.Meter.initMutex]] For creating mutual exclusion controls
  * @see
  *   [[kyo.Meter.initSemaphore]] For creating concurrent operation limiters
  * @see
  *   [[kyo.Meter.initRateLimiter]] For creating time-based rate limiters
  * @see
  *   [[kyo.Meter.pipeline]] For combining multiple meters into a composite control
  */
abstract class Meter private[kyo] ():
    self =>

    /** Runs an effect after acquiring a permit.
      *
      * If the meter is reentrant, nested calls from the same fiber will be allowed. If non-reentrant, nested calls will block or fail.
      *
      * @param v
      *   The effect to run.
      * @tparam A
      *   The return type of the effect.
      * @tparam S
      *   The effect type.
      * @return
      *   The result of running the effect.
      */
    def run[A, S](v: => A < S)(using Frame): A < (S & Async & Abort[Closed])

    /** Attempts to run an effect if a permit is available.
      *
      * @param v
      *   The effect to run.
      * @tparam A
      *   The return type of the effect.
      * @tparam S
      *   The effect type.
      * @return
      *   A Maybe containing the result of running the effect, or Absent if no permit was available.
      */
    def tryRun[A, S](v: => A < S)(using Frame): Maybe[A] < (S & Async & Abort[Closed])

    /** Returns the number of available permits.
      *
      * @return
      *   The number of available permits.
      */
    def availablePermits(using Frame): Int < (Async & Abort[Closed])

    /** Returns the number of fibers waiting for a permit.
      *
      * @return
      *   The number of fibers waiting for a permit.
      */
    def pendingWaiters(using Frame): Int < (Async & Abort[Closed])

    /** Closes the Meter.
      *
      * @return
      *   A Boolean effect indicating whether the Meter was successfully closed.
      */
    def close(using Frame): Boolean < Sync

    /** Checks if the Meter is closed.
      *
      * @return
      *   A Boolean effect indicating whether the Meter is closed.
      */
    def closed(using Frame): Boolean < Sync

end Meter

object Meter:

    /** A no-op Meter that always allows operations and can't be closed. */
    case object Noop extends Meter:
        def availablePermits(using Frame)          = Int.MaxValue
        def pendingWaiters(using Frame)            = 0
        def run[A, S](v: => A < S)(using Frame)    = v
        def tryRun[A, S](v: => A < S)(using Frame) = v.map(Maybe(_))
        def close(using Frame)                     = false
        def closed(using Frame): Boolean < Sync    = false
    end Noop

    /** Creates a **reentrant** Meter that acts as a mutex (binary semaphore).
      *
      * @return
      *   A Meter effect that represents a mutex.
      */
    def initMutex(using Frame): Meter < (Sync & Scope) =
        initMutex(true)

    /** Creates a Meter that acts as a mutex (binary semaphore).
      *
      * @param reentrant
      *   If true, allows nested calls from the same fiber. Default is true.
      * @return
      *   A Meter effect that represents a mutex.
      */
    def initMutex(reentrant: Boolean)(using Frame): Meter < (Sync & Scope) =
        initSemaphore(1, reentrant)

    /** Use a **reentrant** Meter that acts as a mutex (binary semaphore). Meter is closed automatically after usage.
      *
      * @return
      *   A Meter effect that represents a mutex.
      */
    def useMutex[A, S](f: Meter => A < S)(using Frame): A < (Sync & S) =
        initMutexUnscoped.map: meter =>
            Sync.ensure(meter.close)(f(meter))

    /** Use a **reentrant** Meter that acts as a mutex (binary semaphore). Meter is closed automatically after usage.
      *
      * @return
      *   A Meter effect that represents a mutex.
      */
    def useMutex(reentrant: Boolean)[A, S](f: Meter => A < S)(using Frame): A < (Sync & S) =
        initMutexUnscoped(reentrant).map: meter =>
            Sync.ensure(meter.close)(f(meter))

    /** Creates a **reentrant** Meter that acts as a mutex (binary semaphore). Does not ensure meter is cleaned up.
      *
      * @return
      *   A Meter effect that represents a mutex.
      */
    def initMutexUnscoped(using Frame): Meter < Sync =
        initMutexUnscoped(true)

    /** Creates a Meter that acts as a mutex (binary semaphore). Does not ensure meter is cleaned up.
      *
      * @param reentrant
      *   If true, allows nested calls from the same fiber. Default is true.
      * @return
      *   A Meter effect that represents a mutex.
      */
    def initMutexUnscoped(reentrant: Boolean)(using Frame): Meter < Sync =
        initSemaphoreUnscoped(1, reentrant)

    /** Creates a Meter that acts as a semaphore with the specified concurrency.
      *
      * @param concurrency
      *   The number of concurrent operations allowed.
      * @param reentrant
      *   If true, allows nested calls from the same fiber. Default is true.
      * @return
      *   A Meter effect that represents a semaphore.
      */
    def initSemaphore(concurrency: Int, reentrant: Boolean = true)(using Frame): Meter < (Sync & Scope) =
        Scope.acquireRelease(initSemaphoreUnscoped(concurrency, reentrant))(_.close)

    /** Use a Meter that acts as a semaphore with the specified concurrency. Meter is closed automatically after usage.
      *
      * @param concurrency
      *   The number of concurrent operations allowed.
      * @param reentrant
      *   If true, allows nested calls from the same fiber. Default is true.
      * @return
      *   A Meter effect that represents a semaphore.
      */
    def useSemaphore(concurrency: Int, reentrant: Boolean = true)[A, S](f: Meter => A < S)(using Frame): A < (Sync & S) =
        initSemaphoreUnscoped(concurrency, reentrant).map: meter =>
            Sync.ensure(meter.close)(f(meter))

    /** Creates a Meter that acts as a semaphore with the specified concurrency. Does not ensure meter is cleaned up.
      *
      * @param concurrency
      *   The number of concurrent operations allowed.
      * @param reentrant
      *   If true, allows nested calls from the same fiber. Default is true.
      * @return
      *   A Meter effect that represents a semaphore.
      */
    def initSemaphoreUnscoped(concurrency: Int, reentrant: Boolean = true)(using Frame): Meter < Sync =
        Sync.Unsafe.defer {
            new Base(concurrency, reentrant):
                def dispatch[A, S](v: => A < S) =
                    // Release the permit right after the computation
                    Sync.ensure(discard(release()))(v)
                // A permit is owned by its caller, so an abandoned reservation goes straight back to the
                // ledger. No handoff: nothing was freed, since this caller never held a permit.
                def withdraw(): Unit        = returnRegistration()
                def releaseAcquired(): Unit = discard(release())
                def onClose(): Unit         = ()
        }

    /** Creates a Meter that acts as a rate limiter. Does not ensure meter is cleaned up.
      *
      * @param rate
      *   The number of operations allowed per period.
      * @param period
      *   The duration of each period.
      * @param reentrant
      *   If true, allows nested calls from the same fiber. Default is true.
      * @return
      *   A Meter effect that represents a rate limiter.
      */
    def initRateLimiter(rate: Int, period: Duration, reentrant: Boolean = true)(using initFrame: Frame): Meter < (Sync & Scope) =
        Scope.acquireRelease(initRateLimiterUnscoped(rate, period, reentrant))(_.close)

    /** Use a Meter that acts as a rate limiter. Meter is closed automatically after usage
      *
      * @param rate
      *   The number of operations allowed per period.
      * @param period
      *   The duration of each period.
      * @param reentrant
      *   If true, allows nested calls from the same fiber. Default is true.
      * @return
      *   A Meter effect that represents a rate limiter.
      */
    def useRateLimiter(rate: Int, period: Duration, reentrant: Boolean = true)[A, S](f: Meter => A < S)(using
        initFrame: Frame
    ): A < (Sync & S) =
        initRateLimiterUnscoped(rate, period, reentrant).map: meter =>
            Sync.ensure(meter.close)(f(meter))

    /** Creates a Meter that acts as a rate limiter. Does not ensure meter is cleaned up.
      *
      * @param rate
      *   The number of operations allowed per period.
      * @param period
      *   The duration of each period.
      * @param reentrant
      *   If true, allows nested calls from the same fiber. Default is true.
      * @return
      *   A Meter effect that represents a rate limiter.
      */
    def initRateLimiterUnscoped(rate: Int, period: Duration, reentrant: Boolean = true)(using initFrame: Frame): Meter < Sync =
        Sync.Unsafe.defer {
            new Base(rate, reentrant):
                val timerTask =
                    // Schedule periodic task to replenish permits
                    Sync.Unsafe.evalOrThrow(Clock.repeatAtInterval(period, period)(replenish()))

                def dispatch[A, S](v: => A < S) =
                    // Don't release a permit since it's managed by the timer task
                    v

                // Permits belong to the clock, not to callers: `replenish` restores the full rate each
                // period regardless of who consumed it. Neither finishing nor abandoning returns rate
                // early, matching `dispatch` above; the timer restores it.
                def withdraw(): Unit        = ()
                def releaseAcquired(): Unit = ()

                @tailrec def replenish(i: Int = 0): Unit =
                    if i < rate && release() then
                        replenish(i + 1)

                def onClose() = discard(timerTask.unsafe.interrupt())
        }

    /** Combines two Meters into a pipeline.
      *
      * @param m1
      *   The first Meter.
      * @param m2
      *   The second Meter.
      * @return
      *   A Meter effect that represents the pipeline of m1 and m2.
      */
    def pipeline[S1, S2](m1: Meter < S1, m2: Meter < S2)(using Frame): Meter < (Sync & S1 & S2) =
        pipeline[S1 & S2](List(m1, m2))

    /** Combines three Meters into a pipeline.
      *
      * @param m1
      *   The first Meter.
      * @param m2
      *   The second Meter.
      * @param m3
      *   The third Meter.
      * @return
      *   A Meter effect that represents the pipeline of m1, m2, and m3.
      */
    def pipeline[S1, S2, S3](
        m1: Meter < S1,
        m2: Meter < S2,
        m3: Meter < S3
    )(using Frame): Meter < (Sync & S1 & S2 & S3) =
        pipeline[S1 & S2 & S3](List(m1, m2, m3))

    /** Combines four Meters into a pipeline.
      *
      * @param m1
      *   The first Meter.
      * @param m2
      *   The second Meter.
      * @param m3
      *   The third Meter.
      * @param m4
      *   The fourth Meter.
      * @return
      *   A Meter effect that represents the pipeline of m1, m2, m3, and m4.
      */
    def pipeline[S1, S2, S3, S4](
        m1: Meter < S1,
        m2: Meter < S2,
        m3: Meter < S3,
        m4: Meter < S4
    )(using Frame): Meter < (Sync & S1 & S2 & S3 & S4) =
        pipeline[S1 & S2 & S3 & S4](List(m1, m2, m3, m4))

    /** Combines a sequence of Meters into a pipeline.
      *
      * @param meters
      *   The sequence of Meters to combine.
      * @return
      *   A Meter effect that represents the pipeline of all input Meters.
      */
    def pipeline[S](meters: Seq[Meter < (Sync & S)])(using Frame): Meter < (Sync & S) =
        Kyo.collectAll(meters).map { seq =>
            val meters = seq.toIndexedSeq
            new Meter:
                def availablePermits(using Frame) =
                    Loop.indexed(0) { (idx, acc) =>
                        if idx == meters.length then Loop.done(acc)
                        else meters(idx).availablePermits.map(v => Loop.continue(acc + v))
                    }

                def pendingWaiters(using Frame) =
                    Loop.indexed(0) { (idx, acc) =>
                        if idx == meters.length then Loop.done(acc)
                        else meters(idx).pendingWaiters.map(v => Loop.continue(acc + v))
                    }

                def run[A, S](v: => A < S)(using Frame) =
                    def loop(idx: Int = 0): A < (S & Async & Abort[Closed]) =
                        if idx == meters.length then v
                        else meters(idx).run(loop(idx + 1))
                    loop()
                end run

                def tryRun[A, S](v: => A < S)(using Frame) =
                    def loop(idx: Int = 0): Maybe[A] < (S & Async & Abort[Closed]) =
                        if idx == meters.length then v.map(Maybe(_))
                        else
                            meters(idx).tryRun(loop(idx + 1)).map {
                                case Absent => Maybe.empty
                                case r      => r.flatten
                            }
                    loop()
                end tryRun

                def close(using Frame): Boolean < Sync =
                    Kyo.foreach(meters)(_.close).map(_.exists(identity))

                def closed(using Frame): Boolean < Sync =
                    Kyo.foreach(meters)(_.closed).map(_.exists(identity))
            end new
        }

    private val acquiredMeters = Local.initNoninheritable(Set.empty[Meter])

    sealed abstract private class Base(permits: Int, reentrant: Boolean)(using initFrame: Frame, allow: AllowUnsafe) extends Meter:

        // MinValue => closed
        // >= 0     => # of permits
        // < 0      => # of waiters
        val state   = AtomicInt.Unsafe.init(permits)
        val waiters = new MpmcUnboundedUnsafeQueue[Promise.Unsafe[Unit, Abort[Closed]]](8)
        val closed  = Promise.Unsafe.init[Nothing, Abort[Closed]]()

        protected def dispatch[A, S](v: => A < S): A < (S & Sync)
        protected def onClose(): Unit

        /** How a caller interrupted while parked gives back what it reserved.
          *
          * This is the wait-phase counterpart of [[dispatch]]'s teardown, and it is per-kind for the
          * same reason `dispatch` is: what a reservation costs differs by meter. A semaphore's permits
          * are owned by callers, so an abandoned reservation returns to the ledger. A rate limiter's are
          * owned by the clock and replenished on a schedule, so an abandoned reservation is left for the
          * timer to restore rather than handed back early.
          */
        protected def withdraw(): Unit

        /** How a caller that acquired through the wait queue gives its permit back.
          *
          * The same teardown [[dispatch]] installs on the immediate-acquisition path, reached instead
          * through [[settle]] because the wait path needs one teardown covering both outcomes.
          */
        protected def releaseAcquired(): Unit

        private inline def withReentry[A, S](inline reenter: => A < S)(acquire: AllowUnsafe ?=> A < S): A < (Sync & S) =
            if reentrant then
                Sync.withLocal(acquiredMeters) { meters =>
                    if meters.contains(this) then reenter
                    else acquire
                }
            else
                acquire

        private inline def withAcquiredMeter[A, S](inline v: => A < S) =
            if reentrant then
                acquiredMeters.update(_ + this)(v)
            else
                v

        final def run[A, S](v: => A < S)(using Frame) =
            withReentry(v) {
                @tailrec def loop(): A < (S & Async & Abort[Closed]) =
                    val st = state.get()
                    if st == Int.MinValue then
                        // Meter is closed
                        closed.safe.get
                    else if st > 0 then
                        // Permit available, dispatch immediately
                        if state.compareAndSet(st, st - 1) then dispatch(withAcquiredMeter(v))
                        else loop()
                    else
                        // No permit available. The promise is queued BEFORE the reservation is taken,
                        // so a reservation visible in `state` always has its promise already in the
                        // queue. That ordering is what lets close drain by emptying the queue and lets
                        // handoff poll without waiting for an offer that has been promised but not made.
                        val p = Promise.Unsafe.init[Unit, Abort[Closed]]()
                        discard(waiters.offer(p))
                        if state.compareAndSet(st, st - 1) then
                            // One teardown, installed BEFORE the wait, that reads `p` to decide what this
                            // caller actually owes. Installing it before the wait is what makes it total:
                            // an interrupt that lands after `p` is completed but before the continuation
                            // resumes still runs it, so a transferred permit is never stranded. `dispatch`
                            // is not used on this path because [[settle]] already covers both outcomes.
                            Sync.ensure(settle(p))(p.safe.use(_ => withAcquiredMeter(v)))
                        else
                            // The reservation was lost to a concurrent update, so this promise stands for
                            // nothing. Retire it; handoff and close skip an already-completed promise. It
                            // is never awaited, so the value it carries is unobservable.
                            p.completeDiscard(Result.fail(Closed("Meter", initFrame)))
                            loop()
                        end if
                    end if
                end loop
                loop()
            }
        end run

        final def tryRun[A, S](v: => A < S)(using Frame): Maybe[A] < (S & Async & Abort[Closed]) =
            withReentry(v.map(Maybe(_))) {
                @tailrec def loop(): Maybe[A] < (S & Async & Abort[Closed]) =
                    val st = state.get()
                    if st == Int.MinValue then
                        // Meter is closed
                        closed.safe.get
                    else if st <= 0 then
                        // No permit available, return empty
                        Maybe.empty
                    else if state.compareAndSet(st, st - 1) then
                        // Permit available, dispatch
                        dispatch(withAcquiredMeter(v.map(Maybe(_))))
                    else
                        // CAS failed, retry
                        loop()
                    end if
                end loop
                loop()
            }
        end tryRun

        final def availablePermits(using Frame) =
            Sync.Unsafe.defer {
                state.get() match
                    case Int.MinValue => closed.safe.get
                    case st           => Math.max(0, st)
            }

        final def pendingWaiters(using Frame) =
            Sync.Unsafe.defer {
                state.get() match
                    case Int.MinValue => closed.safe.get
                    case st           => Math.min(0, st).abs
            }

        final def close(using frame: Frame): Boolean < Sync =
            Sync.Unsafe.defer {
                val st = state.getAndSet(Int.MinValue)
                val ok = st != Int.MinValue // The meter wasn't already closed
                if ok then
                    val fail = Result.fail(Closed("Meter", initFrame))
                    // Complete the closed promise to fail new operations
                    closed.completeDiscard(fail)
                    // Drain every queued promise rather than a count derived from `state`: a caller that
                    // withdrew after being interrupted returned its registration but left its retired
                    // promise behind, so the queue can hold more entries than `state` accounts for, and
                    // a count-driven drain could stop on those and leave a live waiter parked forever.
                    // Emptying the queue is sufficient because a registration is queued before it is
                    // reserved, so every waiter that reserved before the getAndSet above is already here.
                    @tailrec def drain(): Unit =
                        waiters.poll() match
                            case Maybe.Present(waiter) =>
                                waiter.completeDiscard(fail)
                                drain()
                            case Maybe.Absent => ()
                    drain()
                    onClose()
                end if
                ok
            }
        end close

        final def closed(using Frame) = Sync.defer(state.get() == Int.MinValue)

        @tailrec final protected def release(): Boolean =
            val st = state.get()
            if st >= permits || st == Int.MinValue then
                // No more permits to release or meter is closed
                false
            else if !state.compareAndSet(st, st + 1) then
                // CAS failed, retry
                release()
            else
                // st < 0 means every permit was held and a registration is queued, so this permit is
                // owed to a waiter rather than to the free pool.
                if st < 0 then handoff()
                // Permit released
                true
            end if
        end release

        /** Hands the just-returned permit to the first waiter that can still take it.
          *
          * A promise whose owner withdrew after being interrupted is skipped without touching `state`:
          * that owner's own withdrawal already returned its registration, so returning it here as well
          * would inflate the ledger and admit more callers than there are permits. An empty queue means
          * no live waiter remains, and the permit stays in the ledger for the next caller.
          */
        @tailrec final private def handoff(): Unit =
            waiters.poll() match
                case Maybe.Present(waiter) => if !waiter.completeUnit() then handoff()
                case Maybe.Absent          => ()
        end handoff

        /** Returns a registration that never became an acquisition, without handing a permit to anyone.
          *
          * A caller interrupted while parked reserved a slot but never held a permit, so nothing was
          * freed for a waiter to take. This is the counterpart of [[release]] for the wait phase, and
          * the difference between them is exactly the handoff.
          */
        @tailrec final protected def returnRegistration(): Unit =
            val st = state.get()
            if st == Int.MinValue then ()
            else if !state.compareAndSet(st, st + 1) then returnRegistration()
            else ()
            end if
        end returnRegistration

        /** Teardown for the wait phase, chosen by whether the permit was actually transferred.
          *
          * The promise is the handoff token and `completeUnit` has a single winner, so a SUCCESSFUL
          * completion means a releaser transferred a permit to this caller, which now owes a full
          * release. Any other outcome (interrupted while parked, or woken by close) means no permit was
          * ever held, so only the registration is owed.
          *
          * Reading the outcome from `p` rather than from the caller's own progress is what makes this
          * total: the transfer is decided by whoever wins `completeUnit`, so the answer is already
          * settled by the time any interrupt can be observed here.
          */
        private def settle(p: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe): Unit =
            p.poll() match
                case Maybe.Present(r) if r.isSuccess => releaseAcquired()
                case _                               => withdraw()
    end Base

end Meter
