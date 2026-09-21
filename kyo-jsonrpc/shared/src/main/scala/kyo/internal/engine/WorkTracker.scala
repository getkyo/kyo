package kyo.internal.engine

import kyo.*

/** Counts units of in-flight work and lets a fiber wait until that work has drained.
  *
  * The count and the promise that completes when the count next reaches zero live in one immutable state cell updated by CAS. A waiter reads
  * the cell once and parks on its promise, so it resumes exactly when the work it observed has drained. Holding the count and the promise in
  * two separate atomics instead lets a waiter read a count of one next to the previous, already completed, promise while a new unit of work is
  * between its increment and the installation of a fresh promise, and return while that work is still running.
  *
  * `mirror`, when present, tracks the same count for diagnostics. It is never consulted for a decision.
  */
final private[kyo] class WorkTracker private (
    state: AtomicRef.Unsafe[WorkTracker.State],
    mirror: Maybe[AtomicInt.Unsafe]
):
    import WorkTracker.State

    /** Records one more unit of in-flight work. */
    def acquire()(using AllowUnsafe): Unit =
        @scala.annotation.tailrec
        def loop(): Unit =
            val current = state.get()
            val next    =
                if current.count == 0 then State(1, Promise.Unsafe.init[Unit, Any]())
                else State(current.count + 1, current.idle)
            if !state.compareAndSet(current, next) then loop()
        end loop
        loop()
        mirror.foreach(m => discard(m.incrementAndGet()))
    end acquire

    /** Records that one unit of work finished; completes the idle promise when the count reaches zero. */
    def release()(using AllowUnsafe): Unit =
        @scala.annotation.tailrec
        def loop(): Unit =
            val current = state.get()
            val next    = State(current.count - 1, current.idle)
            if !state.compareAndSet(current, next) then loop()
            else if next.count == 0 then current.idle.completeUnitDiscard()
            end if
        end loop
        loop()
        mirror.foreach(m => discard(m.decrementAndGet()))
    end release

    /** The current count of in-flight work. */
    def count()(using AllowUnsafe): Int = state.get().count

    /** Fibers currently parked in [[awaitIdle]] on the present drain; a diagnostic and a test barrier. */
    def idleWaiters()(using AllowUnsafe): Int = state.get().idle.waiters()

    /** Completes once the work in flight at the time of the call has drained; immediately when there is none. */
    def awaitIdle(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            val current = state.get()
            if current.count <= 0 then Kyo.unit
            else current.idle.safe.get
        }

end WorkTracker

private[kyo] object WorkTracker:

    final private[engine] case class State(count: Int, idle: Promise.Unsafe[Unit, Any])

    /** A tracker with no work in flight. */
    def init()(using AllowUnsafe): WorkTracker = initMirrored(Absent)

    /** A tracker that also keeps `mirror` equal to its count, for diagnostics. */
    def initMirrored(mirror: Maybe[AtomicInt.Unsafe])(using AllowUnsafe): WorkTracker =
        val idle = Promise.Unsafe.init[Unit, Any]()
        idle.completeUnitDiscard()
        new WorkTracker(AtomicRef.Unsafe.init(State(0, idle)), mirror)
    end initMirrored

end WorkTracker
