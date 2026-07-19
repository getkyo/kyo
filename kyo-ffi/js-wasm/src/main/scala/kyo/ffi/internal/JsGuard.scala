package kyo.ffi.internal

import kyo.Frame
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.ffi.Ffi.CloseOutcome
import scala.concurrent.duration.FiniteDuration
import scala.scalajs.js
import scala.util.control.NonFatal

/** JS [[Ffi.Guard]] holding strong refs to koffi callback handles. `close()` unregisters each handle from koffi and drops refs. Idempotent.
  */
final class JsGuard private[ffi] (frame: Frame) extends Ffi.Guard:

    // Platform-closer unregisters retained koffi handles in LIFO order, swallowing per-handle exceptions.
    // The `postCloseHook` removes this guard from GuardRegistry on either the clean synchronous path or the deferred endCallback path.
    private val core: GuardCore = new GuardCore(() => closeRetainedKoffi(), () => GuardRegistry.unregister(this))

    private def closeRetainedKoffi(): Unit =
        core.forEachRetained { h =>
            try CallbackRegistry.unregister(h.asInstanceOf[js.Any])
            catch
                case NonFatal(_) => ()
                case e: Error =>
                    java.lang.System.err.println(s"[kyo-ffi] Error during guard teardown: ${e.getClass.getName}: ${e.getMessage}")
        }
        core.forEachRetainedCleanup { f =>
            try f()
            catch
                case NonFatal(_) => ()
                case e: Error =>
                    java.lang.System.err.println(s"[kyo-ffi] Error during guard teardown: ${e.getClass.getName}: ${e.getMessage}")
        }
    end closeRetainedKoffi

    /** Pin a ref for the guard lifetime. No-op after close. Implements trait method. */
    private[ffi] def unsafeRetain(ref: AnyRef): Unit = core.retain(ref)

    /** Pin a koffi handle for the guard lifetime. Public for generated code in user packages. Not for user code. */
    def unsafeRetainJs(handle: js.Any): Unit = core.retain(handle.asInstanceOf[AnyRef])

    /** Register a close-time cleanup closure in LIFO order. Used by generated code. Not for user code. */
    def unsafeRetainCleanup(f: () => Unit): Unit = core.retainCleanup(f)

    /** Internal-only: diagnostic access to the retained-count for tests. */
    private[ffi] def retainedCount: Int = core.retainedCount

    def registerBuffer[A](b: Buffer[A]): b.type = core.registerBuffer(b)

    private[kyo] def close(): CloseOutcome = core.close()

    def closeAwait(timeout: FiniteDuration)(using frame: Frame): CloseOutcome = core.closeWithPolicy(timeout.toNanos)

    /** Internal-only: diagnostic access to the closed flag for tests. */
    private[ffi] def isClosed: Boolean = core.isClosed
end JsGuard
