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
        Sync.acquireReleaseWith(initMutexUnscoped)(_.close)(f)

    /** Use a **reentrant** Meter that acts as a mutex (binary semaphore). Meter is closed automatically after usage.
      *
      * @return
      *   A Meter effect that represents a mutex.
      */
    def useMutex(reentrant: Boolean)[A, S](f: Meter => A < S)(using Frame): A < (Sync & S) =
        Sync.acquireReleaseWith(initMutexUnscoped(reentrant))(_.close)(f)

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
        Sync.acquireReleaseWith(initSemaphoreUnscoped(concurrency, reentrant))(_.close)(f)

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
                // The permit is returned when the body ends; `settle` runs this from the one ensure
                // installed before the take, so an interrupt in the step after the take still releases.
                def settleAcquired(): Unit = discard(release())
                def onClose(): Unit        = ()
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
        Sync.acquireReleaseWith(initRateLimiterUnscoped(rate, period, reentrant))(_.close)(f)

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

                // A consumed permit is not returned on completion; the timer task replenishes it.
                def settleAcquired(): Unit = ()

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

    /** Packed ledger for [[Base]], a single AtomicLong holding both halves of the state.
      *
      *   - bit 63: closed flag (a snapshot is closed iff it is negative)
      *   - bits 62-32: free permits (31 bits, capped at the meter's `permits`)
      *   - bits 31-0: registered waiters (32 bits)
      *
      * Free permits and waiters are separate fields, not the two signs of one counter, so a snapshot
      * can hold a free permit AND a registered waiter at once. That is what lets a registration
      * give-back touch only the waiter field (never a permit), and lets a released permit always land
      * in the free field visible to the next claimant. Mirrors the `Gate` `State` convention.
      */
    private type State = State.Impl

    private object State:

        opaque type Impl = AtomicLong.Unsafe

        opaque type Snapshot = Long

        private inline def pack(free: Int, waiters: Int): Long =
            (free.toLong << 32) | (waiters.toLong & 0xffffffffL)

        def init(free: Int)(using AllowUnsafe): State =
            AtomicLong.Unsafe.init(pack(free, 0))

        extension (self: State)
            def get()(using AllowUnsafe): Snapshot = AtomicLong.Unsafe.get(self)()
            def cas(expected: Snapshot, update: Snapshot)(using AllowUnsafe): Boolean =
                AtomicLong.Unsafe.compareAndSet(self)(expected, update)
            def getAndClose()(using AllowUnsafe): Snapshot =
                AtomicLong.Unsafe.getAndSet(self)(Long.MinValue)
        end extension

        extension (self: Snapshot)
            inline def free: Int              = ((self & 0x7fffffff00000000L) >>> 32).toInt
            inline def waiters: Int           = (self & 0xffffffffL).toInt
            inline def isClosed: Boolean      = self < 0
            inline def addPermit: Snapshot    = self + (1L << 32)
            inline def takePermit: Snapshot   = self - (1L << 32)
            inline def addWaiter: Snapshot    = self + 1L
            inline def removeWaiter: Snapshot = self - 1L
        end extension
    end State

    sealed abstract private class Base(permits: Int, reentrant: Boolean)(using initFrame: Frame, allow: AllowUnsafe) extends Meter:

        val state   = State.init(permits)
        val waiters = new MpmcUnboundedUnsafeQueue[Promise.Unsafe[Unit, Abort[Closed]]](8)
        val closed  = Promise.Unsafe.init[Nothing, Abort[Closed]]()

        // The two per-kind hooks: settleAcquired returns a claimed permit (semaphore) or does nothing
        // (rate limiter, whose timer replenishes instead); onClose releases kind-specific resources.
        protected def settleAcquired(): Unit
        protected def onClose(): Unit

        // Reentrancy: if this fiber already holds this meter (tracked in the acquiredMeters fiber-local),
        // a nested call reenters the body WITHOUT taking a second permit, so it cannot self-deadlock.
        private inline def withReentry[A, S](inline reenter: => A < S)(acquire: AllowUnsafe ?=> A < S): A < (Sync & S) =
            if reentrant then
                Sync.withLocal(acquiredMeters) { meters =>
                    if meters.contains(this) then reenter
                    else acquire
                }
            else
                acquire

        // Marks this meter as held in the fiber-local set for the duration of the body, so a nested
        // acquire (via withReentry) sees it and reenters instead of taking another permit.
        private inline def withAcquiredMeter[A, S](inline v: => A < S) =
            if reentrant then
                acquiredMeters.update(_ + this)(v)
            else
                v

        final def run[A, S](v: => A < S)(using Frame) =
            withReentry(v) {
                // Permits are claimed from `free` by CAS; a completed promise means only "re-check the
                // ledger", never "here is a permit", so a woken waiter re-competes as a fresh caller (one
                // park shape). `waiterPromise`/`registered`/`taken` are this run's private, single-fiber
                // state, so plain vars are safe: they record what this acquisition owes the ledger, and
                // `settle` (installed before any take) reconciles them on every exit, so an interrupt at
                // any suspension still settles correctly.
                var waiterPromise: Maybe[Promise.Unsafe[Unit, Abort[Closed]]] = Maybe.Absent
                var registered                                                = false
                var taken                                                     = false
                Sync.ensure(settle(waiterPromise, registered, taken)) {
                    def loop(): A < (S & Async & Abort[Closed]) =
                        val s = state.get()
                        if s.isClosed then
                            closed.safe.get
                        else if s.free > 0 then
                            // Fast path: claim a free permit. `taken` is set in the same step as the take
                            // CAS, so the pre-installed `settle` owns the release from here on.
                            if state.cas(s, s.takePermit) then
                                taken = true
                                withAcquiredMeter(v)
                            else loop()
                        else
                            // No free permit: register as a waiter and park.
                            val p = Promise.Unsafe.init[Unit, Abort[Closed]]()
                            waiterPromise = Maybe.Present(p)
                            // Queue-before-register: a releaser that witnesses `waiters > 0` finds the
                            // entry. The register CAS is on the whole word, so it re-validates `free == 0`
                            // in the same step; if a permit appeared, the CAS fails and the loop claims it.
                            discard(waiters.offer(p))
                            if state.cas(s, s.addWaiter) then
                                registered = true
                                p.safe.use { _ =>
                                    // Woken. The `use` above is the park; the re-entry IS the re-check.
                                    // Give the registration back and re-enter as a fresh caller: a release
                                    // never consumes registrations, so a permit freed in the eject window
                                    // stays visible in `free` for the re-entry to claim.
                                    registered = false
                                    giveBack()
                                    loop()
                                }
                            else
                                retire(p)
                                loop()
                            end if
                        end if
                    end loop
                    loop()
                }
            }
        end run

        final def tryRun[A, S](v: => A < S)(using Frame): Maybe[A] < (S & Async & Abort[Closed]) =
            withReentry(v.map(Maybe(_))) {
                var taken = false
                // The teardown is installed before the take CAS, so a claimed permit is owned even if an
                // interrupt lands in the step after the CAS. No wait path here (Absent promise, never
                // registered), so `settle` only ever runs its taken branch.
                Sync.ensure(settle(Maybe.Absent, false, taken)) {
                    @tailrec def loop(): Maybe[A] < (S & Async & Abort[Closed]) =
                        val s = state.get()
                        if s.isClosed then
                            // Meter is closed
                            closed.safe.get
                        else if s.free <= 0 then
                            // No permit available, return empty
                            Maybe.empty
                        else if state.cas(s, s.takePermit) then
                            // Permit available, claim it; `settle` returns it after the body.
                            taken = true
                            withAcquiredMeter(v.map(Maybe(_)))
                        else
                            // CAS failed, retry
                            loop()
                        end if
                    end loop
                    loop()
                }
            }
        end tryRun

        final def availablePermits(using Frame) =
            Sync.Unsafe.defer {
                val s = state.get()
                if s.isClosed then closed.safe.get else s.free
            }

        final def pendingWaiters(using Frame) =
            Sync.Unsafe.defer {
                val s = state.get()
                if s.isClosed then closed.safe.get else s.waiters
            }

        final def close(using frame: Frame): Boolean < Sync =
            Sync.Unsafe.defer {
                val s  = state.getAndClose()
                val ok = !s.isClosed // The meter wasn't already closed
                if ok then
                    val fail = Result.fail(Closed("Meter", initFrame))
                    // Complete the closed promise to fail new operations
                    closed.completeDiscard(fail)
                    // Drain the whole queue, not a count taken from `state`: retired promises left by
                    // abandoned reservations make the queue longer than `state`, so a counted drain
                    // stops early and strands live waiters. Queue-before-reserve makes emptying it
                    // sufficient, since every waiter reserved before the getAndSet above is here.
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

        final def closed(using Frame) = Sync.defer(state.get().isClosed)

        /** Releases a permit into the free pool, then nudges one waiter to re-check.
          *
          * The permit lands in `free` unconditionally; the wake is best-effort. If every queued entry is
          * dead, nobody is woken but the permit stays visible in `free`, so any claimant (a fresh arrival,
          * a woken waiter, a re-parking waiter) takes it by CAS. Capped at `permits`.
          */
        @tailrec final protected def release(): Boolean =
            val s = state.get()
            if s.isClosed || s.free >= permits then
                // No more permits to release or meter is closed
                false
            else if !state.cas(s, s.addPermit) then
                // CAS failed, retry
                release()
            else
                if s.waiters > 0 then wake()
                // Permit released
                true
            end if
        end release

        /** Wakes the first still-pending waiter so it re-checks the ledger. A completed promise means
          * "re-check", never "here is a permit"; already-completed entries are skipped, an empty queue is
          * benign (the permit is already visible in `free`).
          */
        @tailrec final private def wake(): Unit =
            waiters.poll() match
                case Maybe.Present(waiter) => if !waiter.completeUnit() then wake()
                case Maybe.Absent          => ()
        end wake

        /** Retires a promise its owner is abandoning. If it had already been woken (completed with
          * success) but that wake was not converted into a claim, re-issue the wake to the next pending
          * waiter so a nudge is never lost. A spurious re-wake is absorbed by the claim CAS.
          */
        final protected def retire(p: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe): Unit =
            if !p.complete(Result.fail(Closed("Meter", initFrame))) && p.poll().exists(_.isSuccess) then
                wake()

        /** Gives back a registration that never became an acquisition. Touches ONLY the waiter field, so
          * it can never consume a permit; that field-locality is what deletes the lost-wakeup class.
          */
        @tailrec final protected def giveBack(): Unit =
            val s = state.get()
            if !s.isClosed && !state.cas(s, s.removeWaiter) then giveBack()

        /** Settles an attempt once it ends, however it ends. A claim (`taken`) hands the permit's
          * lifecycle to [[settleAcquired]] (release for a semaphore, nothing for a rate limiter); the
          * promise, present only when the claim came off the wait path, is retired without re-issuing a
          * wake (it was consumed by the claim). A non-claiming exit retires the current promise (re-issuing
          * a consumed-but-unused wake) and gives back a still-held registration.
          */
        private def settle(waiterPromise: Maybe[Promise.Unsafe[Unit, Abort[Closed]]], registered: Boolean, taken: Boolean)(using
            AllowUnsafe
        ): Unit =
            if taken then
                waiterPromise.foreach(p => discard(p.complete(Result.fail(Closed("Meter", initFrame)))))
                settleAcquired()
            else
                waiterPromise.foreach(retire)
                if registered then giveBack()
    end Base

end Meter
