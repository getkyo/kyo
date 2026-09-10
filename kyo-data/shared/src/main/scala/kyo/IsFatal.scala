package kyo

import scala.util.control.ControlThrowable

/** Whether a throwable is one kyo refuses to carry as a value, and lets escape instead.
  *
  * Kyo needs its own answer rather than `scala.util.control.NonFatal`'s, because the two are asking different
  * questions. Scala's asks what is unsafe for a general-purpose `catch` to swallow, and answers conservatively:
  * a `LinkageError` or an `InterruptedException` is fatal there. Kyo asks what leaves the runtime unable to
  * continue, and those two do not. A class that fails to link says the program is wrong, not that the JVM is;
  * an interrupt is something kyo delivers itself, all the time, by design.
  *
  * The difference is not academic. A throwable kyo calls fatal is re-thrown out of the fiber into the scheduler
  * worker, which ends that worker: `IOTask` completes the fiber with a `Panic` and re-propagates, so a
  * `LinkageError` from one computation costs a worker and every release that computation still owed. Under
  * Scala's answer, ordinary application bugs reach that path.
  *
  * Named for the fatal side rather than against it, because `NonFatal` is the name Scala's already has and a
  * second one in package `kyo` would shadow it wherever `kyo.*` is imported. The two policies disagree, and a
  * call site reading `NonFatal(ex)` could not say which it meant.
  *
  * What is fatal is what says the process itself is finished:
  *
  *   - `VirtualMachineError`, which is `OutOfMemoryError`, `StackOverflowError`, `InternalError` and
  *     `UnknownError`. Nothing after one of these can be trusted, including the release that would have run.
  *   - `ControlThrowable`, which is another library's non-local return passing through and is never ours to
  *     absorb.
  *
  * `ThreadDeath` is absent where Scala's list has it: `Thread.stop` is gone from the platform, so nothing can
  * raise one, and naming it only earns a deprecation warning.
  *
  * Everything else is a value: it becomes a `Panic`, the fiber ends, its finalizers run, and the rest of the
  * runtime carries on.
  */
object IsFatal:

    /** Whether `throwable` is one kyo lets escape rather than carrying as a value. */
    def apply(throwable: Throwable): Boolean =
        throwable match
            case _: VirtualMachineError | _: ControlThrowable => true
            case _                                            => false

end IsFatal
