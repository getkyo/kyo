package kyo.ffi.internal

import java.lang.foreign.Arena
import java.lang.ref.Cleaner
import java.util.concurrent.atomic.AtomicInteger
import kyo.Frame
import kyo.discard
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.ffi.Ffi.CloseOutcome
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/** JVM [[Ffi.Guard]] wrapping a Panama shared Arena. Upcall stubs stay valid until [[close]] closes the arena. Idempotent. */
final class JvmGuard private[ffi] (arena: Arena, frame: Frame) extends Ffi.Guard:

    // Spill arenas adopted from Scratch at methods with retained-callback params; closed at guard close time.
    private val adopted: java.util.concurrent.ConcurrentLinkedDeque[Arena] =
        new java.util.concurrent.ConcurrentLinkedDeque[Arena]()

    /** Closes the main arena then every adopted spill arena. Exceptions swallowed per-arena. */
    private def platformCloser(): Unit =
        try arena.close()
        catch
            case NonFatal(_) => ()
            case e: Error =>
                java.lang.System.err.println(s"[kyo-ffi] Error during guard teardown: ${e.getClass.getName}: ${e.getMessage}")
        end try
        val it = adopted.iterator().nn
        while it.hasNext do
            try it.next().nn.close()
            catch
                case NonFatal(_) => ()
                case e: Error =>
                    java.lang.System.err.println(s"[kyo-ffi] Error during guard teardown: ${e.getClass.getName}: ${e.getMessage}")
        end while
        adopted.clear()
    end platformCloser

    /** Composed core carrying all platform-agnostic lifecycle state. The `postCloseHook` cancels the pending leak warning and unregisters
      * this guard from [[GuardRegistry]], it runs on either the clean synchronous close path or the deferred `endCallback` path (after a
      * drain timeout).
      */
    private[internal] val core: GuardCore = new GuardCore(
        () => platformCloser(),
        () =>
            val c = leakCleanable
            if c ne null then c.clean()
            GuardRegistry.unregister(this)
    )

    /** Cleanable for the [[JvmLeakDetector]] registration. Wired by [[GuardFactory.open]] right after construction. Invoked from the
      * `postCloseHook` so a legitimately closed guard does not later fire a false-positive leak warning when the Cleaner reaps it. Volatile
      * because the read in `postCloseHook` can run on a different thread than the writer in `GuardFactory.open`.
      */
    @volatile private var leakCleanable: Cleaner.Cleanable | Null = null

    private[internal] def attachLeakCleanable(c: Cleaner.Cleanable): Unit =
        leakCleanable = c

    def registerBuffer[A](b: Buffer[A]): b.type = core.registerBuffer(b)

    private[kyo] def close(): CloseOutcome = core.close()

    def closeAwait(timeout: FiniteDuration)(using frame: Frame): CloseOutcome = core.closeWithPolicy(timeout.toNanos)

    /** Internal-only: diagnostic access to the closed flag for tests. */
    private[ffi] def isClosed: Boolean = core.isClosed

    /** Pin a ref for the guard lifetime. No-op after close. Implements trait method. */
    private[ffi] def unsafeRetain(ref: AnyRef): Unit = core.retain(ref)

    /** Diagnostic access to the retained-count. Implements trait method. */
    private[ffi] def retainedCount: Int = core.retainedCount

    /** Arena accessor for generated retained-callback code. Public because generated impls live in user packages. Not for user code. */
    def unsafeArena: Arena = arena

    /** Transfer a spill arena to this guard so it outlives the downcall. Called by generated code. No-op if already closed. */
    def adoptArena(a: Arena): Unit =
        if core.isClosed then
            // Guard already closed, close the arena now so we don't leak. This path is not expected in generated code but is
            // defensive against API misuse.
            try a.close()
            catch
                case NonFatal(_) => ()
                case e: Error =>
                    java.lang.System.err.println(s"[kyo-ffi] Error during guard teardown: ${e.getClass.getName}: ${e.getMessage}")
        else adopted.addLast(a)
        end if
    end adoptArena

    /** Internal diagnostic: count of adopted spill arenas. */
    private[ffi] def adoptedArenaCount: Int = adopted.size()

    /** Internal-only: test hook giving [[JvmLeakDetector.testForceLeak]] direct access to the frame and state backing this guard's
      * [[JvmGuard.LeakWarning]]. Visible only to `kyo.ffi.internal`.
      */
    private[internal] def leakWarningState: (Frame, AtomicInteger) = (frame, core.state)
end JvmGuard

private[ffi] object JvmGuard:

    /** Cleaner runnable that warns on stderr if the guard was GC'd without being closed. Must not reference the guard itself. */
    final class LeakWarning(frame: Frame, state: AtomicInteger) extends Runnable:
        def run(): Unit =
            if state.get() == GuardCore.StateOpen then
                java.lang.System.err.println(FfiErrors.leakWarning(frame.show))
    end LeakWarning
end JvmGuard
