package kyo

import kyo.*
import monix.eval.Task

object Monix:

    def get[A: Flat](task: Task[A])(using f: Frame, s: monix.execution.Scheduler): A < (Abort[Throwable] & Async) =
        IO.Unsafe {
            val p = Promise.Unsafe.init[Throwable, A]()
            val cancelable = task.runAsync { (e: Either[Throwable, A]) =>
                p.completeDiscard(Result.fromEither(e))
            }(s)
            p.onInterrupt(_ => discard(cancelable.cancel()))
            p.safe.get
        }

    def run[A: Flat](v: => A < (Abort[Throwable] & Async))(using f: Frame): Task[A] =
        Task.defer {
            import AllowUnsafe.embrace.danger
            Async.run(v).map { fiber =>
                Task.cancelable[A] { cb =>
                    fiber.unsafe.onComplete(r => cb(r.toEither))
                    Task(discard(fiber.unsafe.interrupt(Result.Panic(Fiber.Interrupted(f)))))
                }
            }.pipe(IO.Unsafe.run).eval
        }

end Monix
