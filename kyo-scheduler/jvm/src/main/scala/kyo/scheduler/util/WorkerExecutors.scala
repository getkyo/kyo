package kyo.scheduler.util

import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory

/** JVM worker/clock/timer executors: a cached worker pool with lazily started clock and timer threads.
  *
  * The JVM has no scala-native `MutatorThread_init` thread-startup GC hazard, so nothing here is pre-started or init-serialized. The native
  * variant carries that workaround; keeping the two platform implementations behind one signature lets the shared scheduler stay unchanged.
  */
private[scheduler] object WorkerExecutors {

    def worker(factory: ThreadFactory, coreWorkers: Int, maxWorkers: Int): ExecutorService =
        Executors.newCachedThreadPool(factory)

    def clock(factory: ThreadFactory): ExecutorService =
        Executors.newSingleThreadExecutor(factory)

    def timer(corePoolSize: Int, factory: ThreadFactory): ScheduledExecutorService =
        Executors.newScheduledThreadPool(corePoolSize, factory)
}
