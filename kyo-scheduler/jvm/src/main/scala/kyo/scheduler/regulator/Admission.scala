package kyo.scheduler.regulator

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.LongAdder
import java.util.function.DoubleSupplier
import java.util.function.LongSupplier
import kyo.scheduler.*
import kyo.scheduler.InternalTimer
import kyo.scheduler.top.AdmissionStatus
import kyo.scheduler.util.Flag
import scala.annotation.nowarn
import scala.concurrent.duration.*
import scala.util.hashing.MurmurHash3

/** Admission control regulator that prevents scheduler overload by measuring queuing delays.
  *
  * The Admission regulator protects the system from overload by monitoring scheduler queuing delays and selectively rejecting tasks when
  * delays increase. It maintains an admission percentage that adjusts dynamically based on measured delays.
  *
  * ==Queuing Delay Measurement==
  *
  * The regulator probes for queuing delays by periodically submitting special timing tasks into the scheduler and measuring how long they
  * wait before execution. A probe task simply measures the time between its creation and execution. High variance or increasing delays in
  * these measurements indicate scheduler congestion, triggering reductions in the admission rate to alleviate pressure.
  *
  * ==Rejection Mechanism==
  *
  * Tasks are rejected using a deterministic hashing mechanism that provides stable and consistent admission decisions within time windows.
  * Each task key (string or integer) is hashed using a large prime number multiplication and the current time window to generate a value
  * between 0-99. Tasks with hash values above the current admission percentage are rejected.
  *
  * The rotation mechanism is particularly important for fairness when task keys represent user identifiers:
  *   - Without rotation, users whose IDs hash to high values would face persistent rejection during system pressure
  *   - This could lead to poor user experience where some users are consistently locked out while others maintain access
  *   - Rotation ensures rejection patterns shift periodically, giving previously rejected users opportunities for admission
  *   - The rotation window duration can be tuned to balance stability and fairness
  *
  * For example, with a 60-minute rotation window:
  *   - User A might be rejected in the first window due to their ID's hash value
  *   - In the next window, the time-based component changes the hash calculation
  *   - User A now has a different effective priority and may be admitted while others are rejected
  *   - This prevents any user from experiencing extended denial of service
  *
  * This approach ensures:
  *   - Consistent decisions within each rotation window
  *   - Even distribution of rejections across the key space
  *   - Periodic rotation to prevent unfair persistent rejection
  *   - Load balancing through prime number distribution
  *   - Fair access patterns over time for all users
  *
  * ==Load Shedding Pattern==
  *
  * The system responds to increasing pressure through a gradual and predictable load shedding pattern:
  *
  *   - As pressure increases and admission percentage drops, tasks with highest hash values are rejected
  *   - Additional tasks are rejected if pressure continues building
  *   - System stabilizes at lower load with a stable subset of traffic
  *   - During recovery, admission percentage gradually increases
  *   - Previously rejected tasks may be admitted in new time windows
  *
  * ==Backpressure Characteristics==
  *
  * This design creates an effective backpressure mechanism with several key characteristics. Load reduces predictably as the admission
  * percentage drops, avoiding the oscillation patterns common with random rejection strategies. The system maintains a stable subset of
  * flowing traffic within each time window, while providing natural queue-like behavior for rejected requests.
  *
  * ==Distributed Systems Context==
  *
  * The admission control mechanism is particularly effective in microservices architectures. Consistent rejection patterns help downstream
  * services manage their own load effectively, while enabling client libraries to implement intelligent backoff strategies. The predictable
  * degradation and recovery patterns make it easier to maintain system stability across service boundaries.
  *
  * @param loadAvg
  *   A supplier that provides the current system load average
  * @param schedule
  *   Function to schedule probe tasks in the scheduler
  * @param nowMillis
  *   Current time supplier for delay measurements
  * @param timer
  *   Timer for scheduling periodic regulation
  * @param config
  *   Configuration parameters controlling admission behavior
  * @param rotationWindow
  *   Duration after which the rejection pattern rotates to allow previously rejected keys another chance
  *
  * @see
  *   Regulator for details on the underlying regulation mechanism
  */
final class Admission(
    loadAvg: DoubleSupplier,
    schedule: Task => Unit,
    nowMillis: LongSupplier,
    timer: InternalTimer,
    config: Config = Admission.defaultConfig,
    rotationWindow: Duration = Flag("admission.rotationWindowMinutes", 60).minutes
) extends Regulator(loadAvg, timer, config) {

    private val largePrime = (Math.pow(2, 31) - 1).toInt

    @volatile private var admissionPercent = 100

    private val rejected = new LongAdder
    private val allowed  = new LongAdder

    /** Returns the current admission percentage representing system availability.
      *
      * The admission percentage dynamically adjusts based on system pressure and measured queuing delays. This value determines what
      * portion of incoming tasks will be accepted versus rejected. The adjustment process follows several principles:
      *
      *   - A value of 100 indicates the system is healthy and accepting all tasks
      *   - Lower values indicate the system is under pressure and selectively rejecting tasks
      *   - Changes occur gradually to maintain system stability
      *   - Values decrease when high queuing delays are detected
      *   - Values increase when the system shows capacity for more load
      *
      * @return
      *   Integer between 0 and 100 representing the percentage of tasks that will be admitted
      */
    def percent(): Int = admissionPercent

    /** Tests if a task should be rejected using random sampling.
      *
      * This method provides probabilistic load shedding based on the current admission percentage. It generates a new random number for
      * each call, making it suitable for tasks where consistent admission decisions aren't required. This approach works well for:
      *
      *   - One-off tasks with no related operations
      *   - Tasks where consistent rejection isn't critical
      *   - High-volume scenarios where perfect distribution isn't necessary
      *   - Cases where the caller doesn't have a natural key to use
      *
      * For tasks requiring consistent admission decisions, prefer using reject(key) or reject(string) instead.
      *
      * @return
      *   true if the task should be rejected, false if it should be admitted
      */
    def reject(): Boolean =
        reject(ThreadLocalRandom.current().nextInt())

    /** Tests if a task should be rejected using the string's hash value.
      *
      * Provides consistent admission decisions by using the string's hash as the sampling key. This method guarantees that identical
      * strings will receive the same admission decision at any given admission percentage. This consistency is valuable for several use
      * cases:
      *
      *   - User IDs or session identifiers to maintain consistent user experience
      *   - Transaction or operation IDs for related task sets
      *   - Service names or endpoints for targeted load shedding
      *   - Any scenario requiring deterministic admission control
      *
      * @param key
      *   String to use for admission decision
      * @return
      *   true if the task should be rejected, false if it should be admitted
      */
    def reject(key: String): Boolean =
        reject(MurmurHash3.stringHash(key))

    /** Tests if a task should be rejected using the provided integer key.
      *
      * Provides consistent admission decisions by using the integer directly as the sampling key. This method guarantees that identical
      * integers will receive the same admission decision at any given admission percentage. The integer is mapped to the admission space
      * through a simple modulo operation, making it efficient for high-volume scenarios.
      *
      * This method is particularly useful for:
      *   - Numeric identifiers like user IDs or request sequence numbers
      *   - Hash values from other sources
      *   - Cases where the caller has already computed a suitable numeric key
      *   - Performance-critical scenarios needing minimal overhead
      *
      * @param key
      *   Integer to use for admission decision
      * @return
      *   true if the task should be rejected, false if it should be admitted
      */
    def reject(key: Int): Boolean = {
        val windowId = (nowMillis.getAsLong() / rotationWindow.toMillis).toInt + 1
        val r        = (key * largePrime * windowId).abs % 100 > admissionPercent
        if (r) rejected.increment()
        else allowed.increment()
        r
    }

    /** Internal task class used for probing queue delays.
      *
      * This probe task measures the time between its creation and execution to detect queuing delays in the scheduler. The measured delays
      * help determine when the system is under pressure and needs to adjust its admission rate.
      */
    final private class ProbeTask extends Task {
        val start = nowMillis.getAsLong()
        def run(startMillis: Long, clock: InternalClock) = {
            // Record the scheduling delay
            measure(nowMillis.getAsLong() - start)
            Task.Done
        }
    }

    /** Initiates a probe measurement by scheduling a timing task.
      *
      * This method is called periodically to collect delay measurements that drive the admission control decisions.
      */
    protected def probe() = schedule(new ProbeTask)

    /** Updates the admission percentage based on system conditions.
      *
      * Adjusts the admission percentage while ensuring it stays within valid bounds (0-100). The adjustment size and direction are
      * determined by the regulator based on observed delays and system load.
      *
      * @param diff
      *   The amount to adjust the admission percentage, positive for increase, negative for decrease
      */
    protected def update(diff: Int): Unit =
        admissionPercent = Math.max(0, Math.min(100, admissionPercent + diff))

    /** Provides current statistics about the admission controller's operation.
      *
      * Returns a snapshot of key metrics including the current admission percentage and counts of allowed and rejected tasks. This
      * information is valuable for monitoring and debugging system behavior.
      *
      * @return
      *   Status object containing current metrics and regulator state
      */
    def status(): AdmissionStatus =
        AdmissionStatus(
            admissionPercent,
            allowed.sum(),
            rejected.sum(),
            regulatorStatus()
        )

    @nowarn("msg=unused")
    private val gauges =
        List(
            statsScope.gauge("percent")(admissionPercent),
            statsScope.counterGauge("allowed")(allowed.sum()),
            statsScope.counterGauge("rejected")(rejected.sum())
        )
}

object Admission {

    val defaultConfig: Config =
        Config(
            collectWindow = Flag("admission.collectWindow", 40),
            collectInterval = Flag("admission.collectIntervalMs", 100).millis,
            regulateInterval = Flag("admission.regulateIntervalMs", 1000).millis,
            jitterUpperThreshold = Flag("admission.jitterUpperThreshold", 100),
            jitterLowerThreshold = Flag("admission.jitterLowerThreshold", 80),
            loadAvgTarget = Flag("admission.loadAvgTarget", 0.8),
            stepExp = Flag("admission.stepExp", 1.5)
        )
}
