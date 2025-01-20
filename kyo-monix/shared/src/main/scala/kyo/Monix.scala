package kyo

import kyo.*
import monix.eval.Task

/** Integration between Kyo and Monix Task. Provides bidirectional conversion between Kyo's effect system and Monix Task, enabling seamless
  * interop between the two libraries.
  *
  * The implementation handles proper resource management and cancellation semantics. Task cancellation is propagated to Kyo fibers, while
  * Kyo fiber interruption triggers Task cancellation. Error handling is preserved across boundaries and asynchronous boundaries are
  * properly managed.
  */
object Monix:

    /** Converts a Monix Task into a Kyo computation. The resulting computation captures both asynchronous execution and potential failure
      * through Abort and Async.
      *
      * The implementation ensures proper cancellation handling if the Kyo fiber is interrupted, error propagation from Task failures to Kyo
      * Abort, and resource cleanup on cancellation.
      *
      * @param task
      *   The Monix Task to convert
      * @param scheduler
      *   The Monix scheduler for Task execution
      * @return
      *   A Kyo computation combining Abort and Async that represents the Task execution
      */
    def get[A: Flat](task: Task[A])(using monix.execution.Scheduler, Frame): A < (Abort[Throwable] & Async) =
        IO.Unsafe {
            val p = Promise.Unsafe.init[Throwable, A]()
            val cancelable = task.executeAsync.runAsync { (e: Either[Throwable, A]) =>
                p.completeDiscard(Result.fromEither(e))
            }
            p.onInterrupt(_ => discard(cancelable.cancel()))
            p.safe.get
        }

    /** Converts a Kyo computation into a Monix Task. The resulting Task will execute the computation with proper handling of cancellation
      * and errors.
      *
      * Note that this method only accepts computations with Abort[Throwable] and Async. To convert computations with additional
      * capabilities, first handle those capabilities to reduce to just Abort[Throwable] and Async before calling this method.
      *
      * The implementation ensures Task cancellation propagates to the underlying Kyo fiber, Kyo failures are converted to Task failures,
      * and resources are properly cleaned up on cancellation.
      *
      * @param v
      *   The Kyo computation to convert, requires only Abort[Throwable] and Async
      * @return
      *   A Monix Task representing the computation execution
      */
    def run[A: Flat](v: => A < (Abort[Throwable] & Async))(using f: Frame): Task[A] =
        Task.cancelable { cb =>
            import AllowUnsafe.embrace.danger
            Async.run(v).map { fiber =>
                fiber.unsafe.onComplete { (r: Result[Throwable, A]) =>
                    cb(r.toEither)
                }
                Task(discard(fiber.unsafe.interrupt()))
            }.pipe(IO.Unsafe.evalOrThrow)
        }

end Monix
