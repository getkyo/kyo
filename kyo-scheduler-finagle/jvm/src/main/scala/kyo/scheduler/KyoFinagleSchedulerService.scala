package kyo.scheduler

import com.twitter.concurrent.ForkingScheduler
import com.twitter.concurrent.Scheduler as FinagleScheduler
import com.twitter.finagle.exp.FinagleSchedulerService
import com.twitter.util.Awaitable
import com.twitter.util.Future
import com.twitter.util.Promise
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import kyo.scheduler.InternalClock
import kyo.scheduler.Scheduler
import kyo.scheduler.Task

/** A scheduler service that integrates Kyo's adaptive scheduling system with Finagle's execution model. This integration enables Finagle
  * applications to benefit from sophisticated load management and thread optimization while maintaining full compatibility with Finagle's
  * scheduling interfaces.
  *
  * Enabling the integration requires setting the global flag `-Dcom.twitter.finagle.exp.scheduler=kyo`. Once enabled, the service is
  * automatically discovered through Finagle's service loading mechanism. The scheduler maintains Finagle's default LocalScheduler for
  * lightweight Future compositions, while seamlessly redirecting FuturePools and compute-intensive tasks to Kyo's adaptive thread
  * management.
  *
  * Backpressure is managed through Finagle's ForkingSchedulerFilter, which is included by default in the server stack. This enables Kyo's
  * scheduler to monitor system load and automatically engage backpressure through its rejection mechanism when needed, maintaining optimal
  * performance under varying load conditions.
  *
  * @see
  *   [[com.twitter.finagle.exp.ForkingSchedulerFilter]] for details on server-side task offloading
  * @see
  *   [[com.twitter.concurrent.ForkingScheduler]] for the underlying scheduler interface
  * @see
  *   [[kyo.scheduler.Scheduler]] for details on the underlying adaptive scheduling features and behavior
  */
class KyoFinagleSchedulerService extends FinagleSchedulerService {

    /** Creates a new Finagle scheduler integrated with Kyo's adaptive scheduling system.
      *
      * @param params
      *   Configuration parameters (currently unused but maintained for compatibility)
      * @return
      *   Some(Scheduler) containing the configured scheduler instance
      */
    def create(params: List[String]): Option[FinagleScheduler] = {
        val scheduler =
            new FinagleScheduler with ForkingScheduler {
                val original        = FinagleScheduler()
                val scheduler       = Scheduler.get
                val executorService = scheduler.asExecutorService

                def submit(r: Runnable) = original.submit(r)

                def flush() = {
                    original.flush()
                    scheduler.flush()
                }

                def numDispatches = 0

                def blocking[T](f: => T)(implicit perm: Awaitable.CanAwait): T = {
                    flush()
                    f
                }

                def fork[T](f: => Future[T]): Future[T] = {
                    val task =
                        new Promise[T] with Task {
                            def run(startMillis: Long, clock: InternalClock): Task.Result = {
                                super.become(f)
                                Task.Done
                            }
                        }
                    scheduler.schedule(task)
                    task
                }

                def tryFork[T](f: => Future[T]): Future[Option[T]] =
                    if (scheduler.reject())
                        Future.None
                    else
                        fork(f.map(Some(_)))

                def fork[T](executor: Executor)(f: => Future[T]): Future[T] =
                    fork(f)

                def withMaxSyncConcurrency(concurrency: Int, maxWaiters: Int): ForkingScheduler =
                    this

                def asExecutorService(): ExecutorService =
                    executorService

                def redirectFuturePools() = true

            }

        Some(scheduler)
    }

    /** Provides the parameter format string for service configuration.
      * @return
      *   Parameter format string
      */
    def paramsFormat: String = "<kyo>"
}
