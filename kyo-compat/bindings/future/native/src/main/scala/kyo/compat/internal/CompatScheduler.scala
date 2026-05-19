package kyo.compat.internal

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Public on purpose: `private[kyo]` triggers a `NoClassDefFoundError` at runtime when `sleep`/`timeout` inline at user-package call sites
  * (Scala 3 synthesizes an inline accessor typed against a package-as-class symbol that does not exist at runtime). Scala Native 0.5
  * supports `java.util.concurrent.Executors.newScheduledThreadPool`.
  */
object CompatScheduler:

    private val executor: ScheduledExecutorService =
        val tf = new ThreadFactory:
            private val count = new AtomicInteger(0)
            def newThread(r: Runnable): Thread =
                val t = new Thread(r, s"compat-scheduler-${count.incrementAndGet()}")
                t.setDaemon(true)
                t
            end newThread
        Executors.newScheduledThreadPool(2, tf)
    end executor

    def schedule(action: () => Unit, delay: Long, unit: TimeUnit): Unit =
        val _ = executor.schedule(
            new Runnable:
                def run() = action()
            ,
            delay,
            unit
        )
        ()
    end schedule
end CompatScheduler
