package kyo.ffi.internal

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kyo.discard
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.ffi.Ffi.CloseOutcome
import scala.util.control.NonFatal

/** Shared state and lifecycle orchestration for [[kyo.ffi.Ffi.Guard]] implementations.
  *
  * Owned by platform guards (`JvmGuard`, `NativeGuard`, `JsGuard`). Manages the `StateOpen` → `StateClosing` → `StateClosed` transition,
  * drains in-flight retained-callback invocations, closes registered buffers in LIFO order, then runs `platformCloser` and `postCloseHook`.
  * On drain timeout the platform closer is deferred to the last `endCallback` so arena / retained-slot memory stays alive for readers.
  * Thread-safe; concurrent teardown paths are CAS-guarded to fire exactly once.
  */
final private[ffi] class GuardCore(platformCloser: () => Unit, postCloseHook: () => Unit):

    import GuardCore.*

    /** Close state machine: `StateOpen` (0) → `StateClosing` (1) → `StateClosed` (2). */
    val state: AtomicInteger = new AtomicInteger(StateOpen)

    /** Count of retained-callback invocations currently executing; drained before teardown. */
    val inFlight: AtomicInteger = new AtomicInteger(0)

    @volatile private var closerThread: Thread | Null = null

    /** Guards single invocation of the deferred platformCloser, set when close() times out or when endCallback fires the deferred path.
      * Whichever party CASes this from false to true owns running `platformCloser` and `postCloseHook` and performing the final state
      * transition. Without this flag, concurrent endCallback decrements racing with a late-arriving close() timeout could double-fire.
      */
    private val deferredCloseRan: AtomicBoolean = new AtomicBoolean(false)

    // Strong refs to retained callbacks / koffi handles, paired with optional per-handle cleanup closures.
    // Cleanups are used on JS (koffi unregister per retained callback handle) and on Native (release retained-callback
    // pool slots). JVM leaves the cleanup slot null, the platform-closer's arena close covers upcall-stub lifetime.
    private val retained: scala.collection.mutable.ArrayBuffer[(AnyRef, (() => Unit) | Null)] =
        new scala.collection.mutable.ArrayBuffer[(AnyRef, (() => Unit) | Null)]()

    // Strong-ref bag of buffers registered with this guard. ArrayDeque + addFirst gives LIFO iteration on close().
    private val buffers: java.util.ArrayDeque[Buffer[?]] = new java.util.ArrayDeque[Buffer[?]]()

    /** Diagnostic access to the closed flag. True once the guard is in `StateClosing` or `StateClosed`. */
    def isClosed: Boolean = state.get() != StateOpen

    /** Diagnostic access to the retained-count for tests. */
    def retainedCount: Int = retained.length

    /** Add `b` to the LIFO close queue. No-op after `close()`. Returns `b` for chaining. */
    def registerBuffer[A](b: Buffer[A]): b.type =
        if !isClosed then buffers.addFirst(b)
        b

    /** Pin `ref` for the lifetime of this guard. No-op after `close()`. */
    def retain(ref: AnyRef): Unit =
        if !isClosed then retained += ((ref, null))

    /** Attach a cleanup closure to the most recently retained reference. No-op after `close()` or if nothing has been retained. */
    def retainCleanup(f: () => Unit): Unit =
        if !isClosed && retained.nonEmpty then
            val (ref, _) = retained.last
            retained(retained.length - 1) = (ref, f)

    /** Iterate retained references in LIFO order, invoking `f` on each. Exceptions propagate to the caller. */
    def forEachRetained(f: AnyRef => Unit): Unit =
        var i = retained.length - 1
        while i >= 0 do
            f(retained(i)._1)
            i -= 1
    end forEachRetained

    /** Iterate cleanup closures (paired with retained refs) in LIFO order, invoking `op` on each non-null cleanup. Exceptions propagate to
      * the caller.
      */
    def forEachRetainedCleanup(op: (() => Unit) => Unit): Unit =
        var i = retained.length - 1
        while i >= 0 do
            val cleanup = retained(i)._2
            if cleanup != null then op(cleanup)
            i -= 1
        end while
    end forEachRetainedCleanup

    /** Enter a retained-callback invocation. Returns `true` if the guard is still open; `false` if closing or closed. */
    // Double-check protocol: increment inFlight THEN re-verify state is still Open.
    // If close() raced us (state transitioned to Closing between our first check and
    // the increment), we retract and return false. This is safe because:
    // (1) The AtomicInteger increment is visible to drainInFlight() via happens-before.
    // (2) Our re-check of state after increment catches the race, if state moved to
    //     Closing, we decrement and unpark the closer thread.
    // (3) drainInFlight() cannot see inFlight==0 while we're between increment and
    //     re-check, because both operations are on the same thread (no reordering).
    def beginCallback(): Boolean =
        if state.get() != StateOpen then false
        else
            discard(inFlight.incrementAndGet())
            if state.get() == StateOpen then true
            else
                // Lost the race with close(); retract our increment.
                val n = inFlight.decrementAndGet()
                if n == 0 then
                    val t = closerThread
                    if t != null then GuardDrainSupport.unpark(t)
                    // Also handle the deferred-close path: if close() already timed out and moved on, the last
                    // endCallback-equivalent (this decrement) is responsible for running the deferred closer.
                    runDeferredCloseIfOwner()
                end if
                false
            end if
    end beginCallback

    /** Exit a retained-callback invocation. Decrements `inFlight`; if the result is 0 and the guard is in `StateClosing`, either unparks
      * the synchronous closer thread (if one is still draining) or, when `close()` has already returned `TimedOut` and left, runs the
      * deferred platformCloser itself.
      */
    def endCallback(): Unit =
        val n = inFlight.decrementAndGet()
        if n == 0 && state.get() == StateClosing then
            val t = closerThread
            if t != null then GuardDrainSupport.unpark(t)
            runDeferredCloseIfOwner()
        end if
    end endCallback

    /** If the guard is in `StateClosing`, no synchronous closer is blocked on us, and no party has yet run the deferred closer, CAS
      * ownership of the deferred close and execute `platformCloser` + `postCloseHook`, then transition to `StateClosed`. Called from both
      * `endCallback` (and `beginCallback` rollback) and the timeout path of `closeWithPolicy`.
      */
    private def runDeferredCloseIfOwner(): Unit =
        // Only fire the deferred path once the synchronous closer has released `closerThread`. Otherwise close() is still
        // actively spinning and will handle the transition itself when inFlight hits 0.
        if (closerThread eq null) && state.get() == StateClosing && deferredCloseRan.compareAndSet(false, true) then
            try platformCloser()
            catch
                case NonFatal(t) =>
                    java.lang.System.err.println(
                        s"[kyo-ffi] non-fatal exception during guard teardown: ${t.getClass.getName}: ${t.getMessage}"
                    )
                case e: Error =>
                    java.lang.System.err.println(s"[kyo-ffi] Error during guard teardown: ${e.getClass.getName}: ${e.getMessage}")
            end try
            try postCloseHook()
            catch
                case NonFatal(t) =>
                    java.lang.System.err.println(
                        s"[kyo-ffi] non-fatal exception during guard teardown: ${t.getClass.getName}: ${t.getMessage}"
                    )
                case e: Error =>
                    java.lang.System.err.println(s"[kyo-ffi] Error during guard teardown: ${e.getClass.getName}: ${e.getMessage}")
            end try
            retained.clear()
            state.set(StateClosed)
        end if
    end runDeferredCloseIfOwner

    /** Close using the default `drainTimeoutNanos`. See [[closeWithPolicy]] for semantics. */
    def close(): CloseOutcome = closeWithPolicy(DrainTimeoutNanos)

    /** Transition to closed and run teardown.
      *
      *   - Returns [[CloseOutcome.AlreadyClosed]] on re-entry (or when another thread won the `StateOpen → StateClosing` CAS).
      *   - Returns [[CloseOutcome.Clean]] after drain completes within the timeout: buffers are closed in LIFO order, `platformCloser`
      *     runs, the `postCloseHook` runs, retained refs are cleared, and the state transitions to `StateClosed`.
      *   - Returns [[CloseOutcome.TimedOut]] when the drain spin-wait exceeds the configured timeout. In this case, `platformCloser` is NOT
      *     invoked synchronously, the arena / retained slots are left alive so in-flight callbacks keep reading valid memory. The deferred
      *     closer runs from whichever `endCallback` drops `inFlight` to zero (exactly once; double-invocation is prevented by
      *     `deferredCloseRan`). Buffers are still closed synchronously in LIFO order because the close side owns them directly, they are
      *     not read by retained callbacks.
      */
    def closeWithPolicy(timeoutNanos: Long): CloseOutcome =
        if !state.compareAndSet(StateOpen, StateClosing) then CloseOutcome.AlreadyClosed
        else
            // Drain concurrent retained-callback invocations before tearing down slots.
            val drained = drainInFlight(timeoutNanos)
            // Buffers close regardless of drain outcome, they are not read by retained callbacks.
            // Preserves the LIFO invariant from the old code.
            val it = buffers.iterator().nn
            while it.hasNext do
                try it.next().nn.close()
                catch
                    case NonFatal(t) =>
                        java.lang.System.err.println(
                            s"[kyo-ffi] non-fatal exception during guard teardown: ${t.getClass.getName}: ${t.getMessage}"
                        )
                    case e: Error =>
                        java.lang.System.err.println(s"[kyo-ffi] Error during guard teardown: ${e.getClass.getName}: ${e.getMessage}")
            end while
            buffers.clear()
            if drained then
                // Clean path: in-flight drained to 0 within the timeout. Run platformCloser immediately.
                // CAS `deferredCloseRan` so a late endCallback that raced us cannot double-run.
                if deferredCloseRan.compareAndSet(false, true) then
                    try platformCloser()
                    catch
                        case NonFatal(t) =>
                            java.lang.System.err.println(
                                s"[kyo-ffi] non-fatal exception during guard teardown: ${t.getClass.getName}: ${t.getMessage}"
                            )
                        case e: Error =>
                            java.lang.System.err.println(s"[kyo-ffi] Error during guard teardown: ${e.getClass.getName}: ${e.getMessage}")
                    end try
                    try postCloseHook()
                    catch
                        case NonFatal(t) =>
                            java.lang.System.err.println(
                                s"[kyo-ffi] non-fatal exception during guard teardown: ${t.getClass.getName}: ${t.getMessage}"
                            )
                        case e: Error =>
                            java.lang.System.err.println(s"[kyo-ffi] Error during guard teardown: ${e.getClass.getName}: ${e.getMessage}")
                    end try
                    retained.clear()
                    state.set(StateClosed)
                end if
                CloseOutcome.Clean
            else
                // Timeout path: leave state at StateClosing. platformCloser is deferred to the endCallback that
                // drops inFlight to zero. Retained refs stay alive so callback closures remain reachable.
                // If the drain loop raced a final endCallback such that inFlight is now 0, fire the deferred path
                // here, closerThread is null because drainInFlight set it back before returning.
                if inFlight.get() == 0 then runDeferredCloseIfOwner()
                CloseOutcome.TimedOut
            end if
        end if
    end closeWithPolicy

    /** Spin-wait for `inFlight` to reach 0 or the drain timeout to elapse. Returns `true` if `inFlight` reached 0 (clean drain), `false` on
      * timeout.
      */
    private def drainInFlight(timeoutNanos: Long): Boolean =
        if inFlight.get() == 0 then return true
        val self = Thread.currentThread().nn
        closerThread = self
        try
            val deadline = System.nanoTime() + timeoutNanos
            var spins    = 0
            while inFlight.get() != 0 do
                if spins < SpinBudget then
                    GuardDrainSupport.onSpinWait()
                    spins += 1
                else
                    val remaining = deadline - System.nanoTime()
                    if remaining <= 0L then
                        java.lang.System.err.println(FfiErrors.guardCloseDrainTimeout(inFlight.get(), timeoutNanos / 1000000L))
                        return false
                    end if
                    GuardDrainSupport.parkNanos(math.min(remaining, ParkQuantumNanos))
                end if
            end while
            true
        finally
            closerThread = null
        end try
    end drainInFlight
end GuardCore

private[ffi] object GuardCore:
    /** Initial state, guard open, retained callbacks may run. */
    val StateOpen: Int = 0

    /** Closing state, `close()` has started, new retained callbacks refused, drain in progress or deferred teardown pending. */
    val StateClosing: Int = 1

    /** Terminal state, teardown has finished. */
    val StateClosed: Int = 2

    /** Configurable drain timeout backing field. Defaults to 5 seconds. On JVM, may be overridden via the `kyo.ffi.guard.drainTimeoutMs`
      * system property.
      */
    @volatile var drainTimeoutNanos: Long = 5L * 1000L * 1000L * 1000L

    /** Drain timeout for the close spin-wait. Reads from the configurable var. */
    def DrainTimeoutNanos: Long = drainTimeoutNanos

    /** Spin iterations before switching to parkNanos during drain. */
    val SpinBudget: Int = 1024

    /** Park quantum during drain. */
    val ParkQuantumNanos: Long = 1L * 1000L * 1000L
end GuardCore
