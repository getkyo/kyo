package kyo

import kyo.scheduler.*
import zio.*
import zio.internal.ExecutionMetrics
import zio.internal.FiberRuntime

object KyoSchedulerZioRuntime {
    private[kyo] lazy val layer = {
        val exec =
            new Executor {
                val scheduler =
                    kyo.scheduler.Scheduler.get

                def metrics(implicit unsafe: Unsafe): Option[ExecutionMetrics] = None

                def submit(runnable: Runnable)(implicit unsafe: Unsafe): Boolean = {
                    scheduler.schedule(kyo.scheduler.Task(runnable.run()))
                    true
                }
            }
        Runtime.setExecutor(exec) ++ Runtime.setBlockingExecutor(exec)
    }

    lazy val default: Runtime[Any] = {
        Unsafe.unsafe { implicit u =>
            Runtime.default.unsafe.run {
                for {
                    env     <- layer.build(Scope.global)
                    runtime <- ZIO.runtime[Any].provideEnvironment(env)
                } yield runtime
            }.getOrThrowFiberFailure()
        }
    }
}
