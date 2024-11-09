package kyo

import kyo.*
import monix.eval.Task

object Monix:

    def get[A: Flat](task: Task[A])(using monix.execution.Scheduler, Frame): A < (Abort[Throwable] & Async) =
        IO.Unsafe {
            val p = Promise.Unsafe.init[Throwable, A]()
            val cancelable = task.runAsync { (e: Either[Throwable, A]) =>
                p.completeDiscard(Result.fromEither(e))
            }
            p.onInterrupt(_ => discard(cancelable.cancel()))
            p.safe.get
        }

    def run[A: Flat](v: => A < (Abort[Throwable] & Async))(using f: Frame): Task[A] =
        Task.cancelable { cb =>
            import AllowUnsafe.embrace.danger
            Async.run(v).map { fiber =>
                fiber.unsafe.onComplete { (r: Result[Throwable, A]) =>
                    cb(r.toEither)
                }
                Task(discard(fiber.unsafe.interrupt()))
            }.pipe(IO.Unsafe.run).eval
        }

end Monix
