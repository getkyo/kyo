package kyo.scheduler.regulator

import java.util.concurrent.atomic.LongAdder
import java.util.function.DoubleSupplier
import kyo.scheduler.InternalTimer
import kyo.scheduler.top.RegulatorStatus
import kyo.scheduler.util.*
import scala.util.control.NonFatal

/** A self-tuning regulator that dynamically adjusts scheduler behavior based on system performance metrics. This base class provides
  * automatic adjustment of scheduler parameters based on real-time performance measurements and statistical analysis of timing variations.
  *
  * ==Measurement Collection==
  *
  * The regulator collects timing measurements through periodic probes at configured intervals. These measurements are stored in a moving
  * window, which provides an efficient way to maintain recent performance history while automatically discarding old data. This approach
  * enables quick detection of emerging performance trends while smoothing out momentary irregularities.
  *
  * ==Jitter Analysis==
  *
  * Collected measurements are analyzed using a moving standard deviation calculation to determine system stability. This "jitter" metric
  * reveals performance characteristics such as sudden instability, ongoing systemic issues, and recovery patterns. The analysis focuses on
  * detecting significant deviations that indicate potential performance problems.
  *
  * ==Adjustment Mechanism==
  *
  * Based on the jitter analysis, the regulator makes incremental adjustments to maintain system stability. When jitter exceeds the upper
  * threshold, the regulator adjusts using negative steps. Conversely, when jitter falls below the lower threshold and load meets the
  * target, it adjusts with positive steps. Step sizes increase exponentially with consecutive adjustments in the same direction but reset
  * when the direction changes, providing both responsiveness and stability.
  *
  * ==Configuration==
  *
  * The regulator's behavior is controlled through configuration parameters that define the measurement window size, collection and
  * regulation intervals, jitter thresholds, target load, and step escalation rate. These parameters can be tuned to match specific system
  * characteristics and performance requirements.
  *
  * @param loadAvg
  *   A supplier that provides the current system load average (0.0 to 1.0)
  * @param timer
  *   Timer used for scheduling periodic measurements and adjustments
  * @param config
  *   Configuration parameters for the regulator
  *
  * @note
  *   Implementations must provide probe() and update() methods to define measurement collection and adjustment application respectively.
  *
  * @see
  *   Config for configuration parameters
  * @see
  *   MovingStdDev for jitter calculation details
  * @see
  *   Admission for admission control implementation
  * @see
  *   Concurrency for concurrency control implementation
  */
abstract class Regulator(
    loadAvg: DoubleSupplier,
    timer: InternalTimer,
    config: Config
) {
    import config.*

    private var step            = 0
    private val measurements    = new MovingStdDev(collectWindow)
    private val probesSent      = new LongAdder
    private val probesCompleted = new LongAdder
    private val adjustments     = new LongAdder
    private val updates         = new LongAdder

    /** Collect a performance measurement.
      *
      * This method should implement the specific probing mechanism for the regulator. Implementations must call `measure()` with the
      * collected measurement value.
      */
    protected def probe(): Unit

    /** Apply a regulation adjustment.
      *
      * @param diff
      *   The size and direction of adjustment to apply Positive values indicate increase Negative values indicate decrease Magnitude
      *   increases with consecutive adjustments
      */
    protected def update(diff: Int): Unit

    /** Record a measurement value for regulation.
      *
      * @param v
      *   The measurement value in nanoseconds
      *
      * Measurements are used to:
      *   - Calculate jitter (standard deviation)
      *   - Detect performance anomalies
      *   - Guide adjustment decisions
      */
    protected def measure(v: Long): Unit = {
        probesCompleted.increment()
        stats.measurement.observe(Math.max(0, v.toDouble))
        synchronized(measurements.observe(v))
    }

    /** Stop the regulator.
      *
      * Cancels all scheduled tasks and cleans up resources.
      */
    def stop(): Unit = {
        def discard(v: Any) = {}
        discard(collectTask.cancel())
        discard(regulateTask.cancel())
    }

    private val collectTask =
        timer.schedule(collectInterval)(collect())

    private val regulateTask =
        timer.schedule(regulateInterval)(adjust())

    final private def collect(): Unit = {
        try {
            probesSent.increment()
            probe()
        } catch {
            case ex if NonFatal(ex) =>
                kyo.scheduler.bug(s"${getClass.getSimpleName()} regulator's probe collection has failed.", ex)
        }
    }

    final private def adjust() = {
        try {
            adjustments.increment()

            // Calculate current performance metrics
            val jitter = synchronized(measurements.dev())
            val load   = loadAvg.getAsDouble()

            // Determine adjustment direction based on jitter thresholds
            if (jitter > jitterUpperThreshold) {
                // High jitter - increase negative step size
                if (step < 0) step -= 1
                else step = -1
            } else if (jitter < jitterLowerThreshold && load >= loadAvgTarget) {
                // Low jitter and sufficient load - increase positive step size
                if (step > 0) step += 1
                else step = 1
            } else
                // Reset step when within acceptable range
                step = 0

            if (step != 0) {
                // Calculate exponential adjustment size based on consecutive steps
                val pow = Math.pow(Math.abs(step), stepExp).toInt
                val delta =
                    if (step < 0) -pow
                    else pow
                // Track actual updates and apply the adjustment
                updates.increment()
                update(delta)
            }

            stats.jitter.observe(jitter)
            stats.loadavg.observe(load)
        } catch {
            case ex if NonFatal(ex) =>
                kyo.scheduler.bug(s"${getClass.getSimpleName()} regulator's adjustment has failed.", ex)
        }
    }
    protected val statsScope = kyo.scheduler.statsScope.scope("regulator", getClass.getSimpleName().toLowerCase())

    private object stats {
        val loadavg     = statsScope.histogram("loadavg")
        val measurement = statsScope.histogram("measurement")
        val update      = statsScope.histogram("update")
        val jitter      = statsScope.histogram("jitter")
        val gauges = List(
            statsScope.gauge("probes_sent")(probesSent.sum().toDouble),
            statsScope.gauge("probes_completed")(probesSent.sum().toDouble),
            statsScope.gauge("adjustments")(adjustments.sum().toDouble),
            statsScope.gauge("updates")(updates.sum.toDouble)
        )
    }

    protected def regulatorStatus(): RegulatorStatus =
        RegulatorStatus(
            step,
            measurements.avg(),
            measurements.dev(),
            probesSent.sum(),
            probesCompleted.sum(),
            adjustments.sum(),
            updates.sum()
        )
}
