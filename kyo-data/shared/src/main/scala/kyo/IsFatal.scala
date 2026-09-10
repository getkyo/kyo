package kyo

import scala.util.control.ControlThrowable

/** Whether a throwable is one kyo refuses to carry as a value, and lets escape instead.
  *
  * Narrower than `scala.util.control.NonFatal`, which also treats `LinkageError` and `InterruptedException` as
  * fatal. Neither leaves the runtime unable to continue: a link failure says the program is wrong, and kyo
  * delivers interrupts itself by design. The distinction is costly, because a fatal is re-thrown out of the
  * fiber into the scheduler worker, ending it along with every release that computation still owed.
  *
  * Fatal is what says the process is finished:
  *
  *   - `VirtualMachineError`: `OutOfMemoryError`, `StackOverflowError`, `InternalError`, `UnknownError`.
  *   - `ControlThrowable`, another library's non-local return passing through.
  *
  * `ThreadDeath` is absent because `Thread.stop` is gone from the platform. Everything else becomes a `Panic`:
  * the fiber ends, its finalizers run, and the runtime carries on.
  *
  * Named for the fatal side because `NonFatal` in package `kyo` would shadow Scala's wherever `kyo.*` is
  * imported, and the two policies disagree.
  */
object IsFatal:

    /** Whether `throwable` is one kyo lets escape rather than carrying as a value. */
    def apply(throwable: Throwable): Boolean =
        throwable match
            case _: VirtualMachineError | _: ControlThrowable => true
            case _                                            => false

end IsFatal
