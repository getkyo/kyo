package kyo.scheduler.top

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kyo.scheduler.util.Threads

/** Periodic sinks for the scheduler top. The console (human, delta) and status-file (machine, absolute)
  * sinks are platform-agnostic and live here; JMX registration is the only JVM-specific part and is
  * delegated to [[TopJMX]] (a no-op on Scala Native).
  *
  * Both sinks run on a dedicated single-thread daemon executor, created only when a sink is enabled, so
  * their work (including the file sink's blocking write) never runs on the scheduler's shared timer pool
  * that drives the regulators, and there is zero overhead when the top is disabled.
  */
class Reporter(
    status: () => Status,
    enableTopJMX: Boolean,
    enableTopConsoleMs: Int,
    topStatusFile: String,
    topStatusFileMs: Int
) {

    private val executor: ScheduledExecutorService =
        if (enableTopConsoleMs > 0 || topStatusFile.nonEmpty)
            Executors.newSingleThreadScheduledExecutor(Threads("kyo-scheduler-top"))
        else null

    private var lastConsoleStatus: Status = null

    if (enableTopConsoleMs > 0) {
        val _ = executor.scheduleWithFixedDelay(
            () => {
                val currentStatus = status()
                if (lastConsoleStatus ne null)
                    println(Printer(currentStatus - lastConsoleStatus))
                lastConsoleStatus = currentStatus
            },
            enableTopConsoleMs.toLong,
            enableTopConsoleMs.toLong,
            TimeUnit.MILLISECONDS
        )
    }

    if (topStatusFile.nonEmpty) {
        val intervalMs = (if (topStatusFileMs > 0) topStatusFileMs else 1000).toLong
        val _ = executor.scheduleWithFixedDelay(
            () => StatusFile.write(topStatusFile, Printer.compact(status())),
            intervalMs,
            intervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    private val closeJMX: () => Unit = TopJMX.register(enableTopJMX, status)

    def close(): Unit = {
        closeJMX()
        if (executor ne null) { val _ = executor.shutdownNow() }
    }
}
