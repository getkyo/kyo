package kyo.ffi.internal

import kyo.Frame
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.ffi.Ffi.CloseOutcome
import scala.concurrent.duration.FiniteDuration

/** Scala Native [[Ffi.Guard]]. Holds strong refs to callback closures to prevent GC while C holds the pointer. Idempotent close. Leak
  * detection via [[NativeLeakDetector]] (poll-based WeakReference sweep).
  */
final class NativeGuard private[ffi] (frame: Frame) extends Ffi.Guard:

    // Token passed to CallbackRegistry.claimRetainedSlot_* and unregisterGuard. Identity-equality only.
    private[ffi] val guardToken: AnyRef = new Object()

    // Platform-closer invokes registered cleanup closures to release CallbackRegistry slots.
    // The `postCloseHook` runs after the cleanup closures on either the clean synchronous path or the deferred endCallback path,
    // unregistering from CallbackRegistry, the leak detector, and the shared GuardRegistry.
    private val core: GuardCore = new GuardCore(
        () => closeRetainedCallbacks(),
        () =>
            CallbackRegistry.unregisterGuard(guardToken)
            NativeLeakDetector.unregister(leakToken)
            GuardRegistry.unregister(this)
    )

    // Leak-detector token. Must be assigned AFTER `core` is initialized so `register` does not observe a half-built guard, but BEFORE
    // any generated retained-callback code can run, the constructor ordering here plus `GuardFactory.open` (which publishes the guard
    // only after this class's body completes) is sufficient.
    private val leakToken: GuardLeakToken = NativeLeakDetector.register(this, frame)

    private def closeRetainedCallbacks(): Unit =
        core.forEachRetainedCleanup { f =>
            try f()
            catch case _: Throwable => ()
        }

    /** Internal hook used by generated retained-callback code to pin a closure for the lifetime of the guard. No-op after [[close]]. */
    private[ffi] def retain(ref: AnyRef): Unit = core.retain(ref)

    /** Pin a closure for the guard lifetime. Public for generated code in user packages. Not for user code. */
    def unsafeRetain(ref: AnyRef): Unit = core.retain(ref)

    /** Register a close-time cleanup closure (LIFO). Used by generated code to schedule slot release. Not for user code. */
    def unsafeRetainCleanup(f: () => Unit): Unit = core.retainCleanup(f)

    /** Register a retained-callback pool slot; schedules slot release at close and records it with the leak detector. Not for user code. */
    def unsafeRetainRetainedSlot(shape: String, slot: Int): Unit =
        NativeLeakDetector.recordRetainedSlot(leakToken, shape, slot)
        // Patch the retained slot's TaggedCallback with a back-reference to this guard's core so the
        // retained trampoline can gate its slot read on the guard's state machine (beginCallback/endCallback).
        CallbackRegistry.bindSlotToGuard(shape, slot, core)
        core.retainCleanup(() => CallbackRegistry.releaseRetainedSlotByName(shape, slot))
    end unsafeRetainRetainedSlot

    /** Internal-only: diagnostic access to the retained-count for tests. */
    private[ffi] def retainedCount: Int = core.retainedCount

    def registerBuffer[A](b: Buffer[A]): b.type = core.registerBuffer(b)

    // `core.close()` already drains the retained-cleanup list (which clears each claimed slot's bit via `releaseRetainedSlot_*`).
    // `unregisterGuard`/`NativeLeakDetector.unregister`/`GuardRegistry.unregister` now run inside the `postCloseHook` supplied to
    // `GuardCore` so they fire on both the clean synchronous path and the deferred-endCallback path (after a drain timeout).
    private[kyo] def close(): CloseOutcome = core.close()

    def closeAwait(timeout: FiniteDuration)(using frame: Frame): CloseOutcome = core.closeWithPolicy(timeout.toNanos)

    /** Internal-only: diagnostic access to the closed flag for tests. */
    private[ffi] def isClosed: Boolean = core.isClosed
end NativeGuard
