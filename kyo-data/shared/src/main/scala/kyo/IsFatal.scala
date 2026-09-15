package kyo

import scala.util.control.ControlThrowable

/** Whether a throwable is one kyo refuses to carry as a value, and lets escape instead.
  *
  * Narrower than `scala.util.control.NonFatal`, which also treats `LinkageError` and `InterruptedException` as fatal: a link failure says
  * the program is wrong, not the runtime, and kyo delivers interrupts itself. A fatal is re-thrown out of the fiber into the scheduler
  * worker, ending it along with every release that computation still owed; everything else becomes a `Panic`, so the fiber ends, its
  * finalizers run, and the runtime carries on.
  *
  * Fatal is `VirtualMachineError` (`OutOfMemoryError`, `StackOverflowError`, `InternalError`, `UnknownError`) and `ControlThrowable`
  * (another library's non-local return passing through). `ThreadDeath` is absent because `Thread.stop` is gone from the platform.
  *
  * Named for the fatal side because `NonFatal` in package `kyo` would shadow Scala's wherever `kyo.*` is imported, and the two policies
  * disagree.
  */
object IsFatal:

    /** Whether `throwable` is one kyo lets escape rather than carrying as a value. */
    def apply(throwable: Throwable): Boolean =
        throwable match
            case _: VirtualMachineError | _: ControlThrowable => true
            case _                                            => false

end IsFatal
