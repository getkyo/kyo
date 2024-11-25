package kyo.scheduler.regulator

import java.util.function.DoubleSupplier
import java.util.function.LongSupplier
import kyo.scheduler.*
import kyo.scheduler.top.ConcurrencyStatus
import kyo.scheduler.util.Flag
import scala.concurrent.duration.*

/** Concurrency control regulator that optimizes thread count by detecting system scheduling delays.
  *
  * The Concurrency regulator maintains optimal performance by measuring the system's ability to promptly execute threads. It uses a
  * dedicated OS thread to perform brief sleep operations, analyzing delays between requested and actual wake times to detect various forms
  * of contention and interference. This approach is similar to the jHiccup tool, but uses standard deviation analysis via the Regulator
  * framework to make automatic adjustments.
  *
  * The probe mechanism relies on the operating system's thread scheduling behavior. In a healthy system, the probe thread wakes up from
  * sleep very close to the requested time. Various conditions can delay thread wake-ups, including OS scheduler overload, CPU throttling,
  * excessive context switching, hypervisor interference, and hardware power management. By measuring these delays, the regulator detects
  * when the system is struggling to handle the current thread count.
  *
  * When wake-up delays show high jitter, indicating degraded thread scheduling, the regulator reduces the number of workers. When delays
  * are consistent and the system maintains its target load, the regulator gradually increases workers. This approach automatically finds
  * the optimal thread count for the current system conditions and available CPU resources.
  *
  * @param loadAvg
  *   A supplier that provides the current system load average
  * @param updateConcurrency
  *   Callback to update the number of worker threads
  * @param sleep
  *   Function to perform sleep probes (must use OS thread sleep)
  * @param nowNanos
  *   Current time supplier for wake-up delay measurements
  * @param timer
  *   Timer for scheduling periodic regulation
  * @param config
  *   Configuration parameters controlling concurrency adjustment
  *
  * @see
  *   Regulator for details on the underlying regulation mechanism
  */
final class Concurrency(
    loadAvg: DoubleSupplier,
    updateConcurrency: Int => Unit,
    sleep: Int => Unit,
    nowNanos: LongSupplier,
    timer: InternalTimer,
    config: Config = Concurrency.defaultConfig
) extends Regulator(loadAvg, timer, config) {

    /** Performs a probe measurement by executing a brief sleep operation.
      *
      * This method measures thread scheduling delays by:
      *   - Recording the start time
      *   - Performing a 1ms sleep
      *   - Measuring the actual delay beyond the requested sleep time
      *
      * The measured delay indicates system scheduling efficiency and contention.
      */
    protected def probe() = {
        val start = nowNanos.getAsLong()
        sleep(1)
        measure(nowNanos.getAsLong() - start - 1000000)
    }

    /** Updates the number of worker threads based on regulation decisions.
      *
      * @param diff
      *   The change in thread count to apply:
      *   - Positive values increase threads
      *   - Negative values decrease threads
      *   - Magnitude increases with consecutive adjustments
      */
    protected def update(diff: Int): Unit =
        updateConcurrency(diff)

    def status(): ConcurrencyStatus =
        ConcurrencyStatus(
            regulatorStatus()
        )
}

object Concurrency {

    val defaultConfig: Config = Config(
        collectWindow = Flag("concurrency.collectWindow", 200),
        collectInterval = Flag("concurrency.collectIntervalMs", 10).millis,
        regulateInterval = Flag("concurrency.regulateIntervalMs", 1500).millis,
        jitterUpperThreshold = Flag("concurrency.jitterUpperThreshold", 800000),
        jitterLowerThreshold = Flag("concurrency.jitterLowerThreshold", 500000),
        loadAvgTarget = Flag("concurrency.loadAvgTarget", 0.8),
        stepExp = Flag("concurrency.stepExp", 1.2)
    )
}
