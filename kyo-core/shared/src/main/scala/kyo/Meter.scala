package kyo

import org.jctools.queues.MpmcUnboundedXaddArrayQueue
import scala.annotation.tailrec

/** A synchronization primitive that controls concurrency and rate limiting with configurable admission policies.
  *
  * Meter provides a structured mechanism for controlling access to shared resources, acting as a gatekeeper for concurrent operations. It
  * supports different concurrency control models through its factory methods:
  *   - `initMutex`: Creates a binary semaphore allowing only one operation at a time, ideal for protecting critical sections
  *   - `initSemaphore`: Creates a counter-based control limiting concurrent operations, balancing throughput with resource constraints
  *   - `initRateLimiter`: Creates a time-based control limiting operations to a specified rate, preventing overload while maintaining
  *     throughput
  *
  * All Meter implementations can be configured as reentrant (default) or non-reentrant:
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
    def initMutex(using Frame): Meter < Sync =
        initMutex(true)

    /** Creates a Meter that acts as a mutex (binary semaphore).
      *
      * @param reentrant
      *   If true, allows nested calls from the same fiber. Default is true.
      * @return
      *   A Meter effect that represents a mutex.
      */
    def initMutex(reentrant: Boolean)(using Frame): Meter < Sync =
        initSemaphore(1, reentrant)

    /** Creates a Meter that acts as a semaphore with the specified concurrency.
      *
      * @param concurrency
      *   The number of concurrent operations allowed.
      * @param reentrant
      *   If true, allows nested calls from the same fiber. Default is true.
      * @return
      *   A Meter effect that represents a semaphore.
      */
    def initSemaphore(concurrency: Int, reentrant: Boolean = true)(using Frame): Meter < Sync =
        Sync.Unsafe {
            new Base(concurrency, reentrant):
                def dispatch[A, S](v: => A < S) =
                    // Release the permit right after the computation
                    Sync.ensure(discard(release()))(v)
                def onClose(): Unit = ()
        }

    /** Creates a Meter that acts as a rate limiter.
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
    def initRateLimiter(rate: Int, period: Duration, reentrant: Boolean = true)(using initFrame: Frame): Meter < Sync =
        Sync.Unsafe {
            new Base(rate, reentrant):
                val timerTask =
                    // Schedule periodic task to replenish permits
                    Sync.Unsafe.evalOrThrow(Clock.repeatAtInterval(period, period)(replenish()))

                def dispatch[A, S](v: => A < S) =
                    // Don't release a permit since it's managed by the timer task
                    v

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
        val waiters = new MpmcUnboundedXaddArrayQueue[Promise.Unsafe[Closed, Unit]](8)
        val closed  = Promise.Unsafe.init[Closed, Nothing]()

        protected def dispatch[A, S](v: => A < S): A < (S & Sync)
        protected def onClose(): Unit

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
                    else if state.compareAndSet(st, st - 1) then
                        if st > 0 then
                            // Permit available, dispatch immediately
                            dispatch(withAcquiredMeter(v))
                        else
                            // No permit available, add to waiters queue
                            val p = Promise.Unsafe.init[Closed, Unit]()
                            waiters.add(p)
                            dispatch(p.safe.use(_ => withAcquiredMeter(v)))
                    else
                        // CAS failed, retry
                        loop()
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
            Sync.Unsafe {
                state.get() match
                    case Int.MinValue => closed.safe.get
                    case st           => Math.max(0, st)
            }

        final def pendingWaiters(using Frame) =
            Sync.Unsafe {
                state.get() match
                    case Int.MinValue => closed.safe.get
                    case st           => Math.min(0, st).abs
            }

        final def close(using frame: Frame): Boolean < Sync =
            Sync.Unsafe {
                val st = state.getAndSet(Int.MinValue)
                val ok = st != Int.MinValue // The meter wasn't already closed
                if ok then
                    val fail = Result.fail(Closed("Meter", initFrame))
                    // Complete the closed promise to fail new operations
                    closed.completeDiscard(fail)
                    // Drain the pending waiters
                    @tailrec def drain(st: Int): Unit =
                        if st < 0 then
                            // Use pollWaiter to ensure all pending waiters
                            // as indicated by the state are drained
                            pollWaiter().completeDiscard(fail)
                            drain(st + 1)
                    drain(st)
                    onClose()
                end if
                ok
            }
        end close

        final def closed(using Frame) = Sync(state.get() == Int.MinValue)

        @tailrec final protected def release(): Boolean =
            val st = state.get()
            if st >= permits || st == Int.MinValue then
                // No more permits to release or meter is closed
                false
            else if !state.compareAndSet(st, st + 1) then
                // CAS failed, retry
                release()
            else if st < 0 && !pollWaiter().complete(Result.unit) then
                // Waiter is already complete due to interruption, retry
                release()
            else
                // Permit released
                true
            end if
        end release

        @tailrec final private def pollWaiter(): Promise.Unsafe[Closed, Unit] =
            val waiter = waiters.poll()
            if !isNull(waiter) then waiter
            else
                // If no waiter is found, retry the poll operation
                // This handles the race condition between state change and waiter queuing
                pollWaiter()
            end if
        end pollWaiter
    end Base

end Meter
