package kyo.scheduler

import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kyo.scheduler.util.Threads

/** Shared thread pools for all scheduler tests. Prevents thread accumulation on Scala Native where multiple test classes may run in the
  * same process. The returned executors ignore shutdown calls to prevent accidental closure by test cleanup code.
  */
object TestExecutors {
    // Suppress stack trace printing globally on Scala Native — StackTrace_PrintStackTrace
    // triggers stackOverflowHandler → SIGSEGV.
    Thread.setDefaultUncaughtExceptionHandler((_, _) => ())

    // Thread factory that suppresses uncaught exception stack traces.
    // On Scala Native, Thread.getUncaughtExceptionHandler.uncaughtException triggers
    // StackTrace_PrintStackTrace which causes SIGSEGV via stackOverflowHandler.
    private val silentThreadFactory = Threads(
        "test-worker",
        r => {
            val t = new Thread(null, r, "test-worker", 8 * 1024 * 1024) // 8MB stack
            t.setUncaughtExceptionHandler((_, _) => ())
            t
        }
    )

    val cached: ExecutorService =
        uncloseableExecutor(Executors.newCachedThreadPool(silentThreadFactory))
    private val silentTimerFactory = Threads(
        "test-timer",
        r => {
            val t = new Thread(null, r, "test-timer", 8 * 1024 * 1024) // 8MB stack
            t.setUncaughtExceptionHandler((_, _) => ())
            t
        }
    )

    val scheduled: ScheduledExecutorService =
        uncloseableScheduled(Executors.newScheduledThreadPool(16, silentTimerFactory))

    private def uncloseableExecutor(e: ExecutorService): ExecutorService =
        new java.util.concurrent.AbstractExecutorService {
            def execute(command: Runnable): Unit                         = e.execute(command)
            def shutdown(): Unit                                         = ()
            def shutdownNow(): java.util.List[Runnable]                  = new java.util.ArrayList()
            def isShutdown(): Boolean                                    = false
            def isTerminated(): Boolean                                  = false
            def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
        }

    private def uncloseableScheduled(e: ScheduledExecutorService): ScheduledExecutorService =
        new java.util.concurrent.ScheduledThreadPoolExecutor(0) {
            override def execute(command: Runnable): Unit = e.execute(command)
            override def schedule(command: Runnable, delay: Long, unit: TimeUnit): java.util.concurrent.ScheduledFuture[?] =
                e.schedule(command, delay, unit)
            override def scheduleAtFixedRate(
                command: Runnable,
                init: Long,
                period: Long,
                unit: TimeUnit
            ): java.util.concurrent.ScheduledFuture[?] = e.scheduleAtFixedRate(command, init, period, unit)
            override def scheduleWithFixedDelay(
                command: Runnable,
                init: Long,
                delay: Long,
                unit: TimeUnit
            ): java.util.concurrent.ScheduledFuture[?] = e.scheduleWithFixedDelay(command, init, delay, unit)
            override def submit[T](task: java.util.concurrent.Callable[T]): java.util.concurrent.Future[T] = e.submit(task)
            override def submit(task: Runnable): java.util.concurrent.Future[?]                            = e.submit(task)
            override def shutdown(): Unit                                                                  = ()
            override def shutdownNow(): java.util.List[Runnable]                                           = new java.util.ArrayList()
            override def isShutdown(): Boolean                                                             = false
            override def isTerminated(): Boolean                                                           = false
            override def awaitTermination(timeout: Long, unit: TimeUnit): Boolean                          = true
        }
}
