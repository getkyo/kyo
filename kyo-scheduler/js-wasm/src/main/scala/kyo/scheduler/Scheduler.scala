package kyo.scheduler

import org.scalajs.macrotaskexecutor.MacrotaskExecutor
import scala.concurrent.ExecutionContext
import scala.scalajs.js

object Scheduler {
    val get = new Scheduler
}

/** Single-threaded scheduler that runs each task slice as a Node macrotask.
  *
  * With `statusFile` set (the `topStatusFile` flag the JVM and Native schedulers also write), a timer rewrites a one-line snapshot there
  * every `statusFileMs`. The timer shares the event loop with every task, so a slice that never returns stops the rewrites; ci-monitor
  * reports the snapshot's age, which is how a blocked event loop shows from outside the process. The counters are kept only while a status
  * file is configured, so the default path does no extra work per slice.
  */
class Scheduler(statusFile: String, statusFileMs: Int, nowMs: () => Long) {

    def this() = this(topStatusFile(), topStatusFileMs(), () => System.currentTimeMillis())

    private val timeSlice = timeSliceMs()
    private val clock     = new InternalClock()

    val writesStatus: Boolean = statusFile.nonEmpty

    private var scheduled  = 0L
    private var ran        = 0L
    private var preempted  = 0L
    private var maxSliceMs = 0L

    private val statusTimer: js.UndefOr[js.timers.SetIntervalHandle] =
        if (writesStatus) StatusFile.start(statusFile, if (statusFileMs > 0) statusFileMs else 1000, () => statusLine())
        else js.undefined

    def schedule(t: Task): Unit =
        if (writesStatus) scheduleCounted(t)
        else
            MacrotaskExecutor.execute { () =>
                val now = clock.currentMillis()
                if (t.run(now, clock, now + timeSlice) == Task.Preempted)
                    schedule(t)
            }

    private def scheduleCounted(t: Task): Unit = {
        scheduled += 1
        MacrotaskExecutor.execute { () =>
            ran += 1
            val now    = clock.currentMillis()
            val start  = nowMs()
            val result = t.run(now, clock, now + timeSlice)
            val slice  = nowMs() - start
            if (slice > maxSliceMs) maxSliceMs = slice
            if (result == Task.Preempted) {
                preempted += 1
                scheduleCounted(t)
            }
        }
    }

    /** The snapshot ci-monitor appends to its line. Each call resets `maxSliceMs`, so a line reports the longest slice since the previous one. */
    def statusLine(): String = {
        val line =
            s"kyo.sched ts=${nowMs()} platform=js scheduled=$scheduled ran=$ran preempted=$preempted pending=${scheduled - ran} maxSliceMs=$maxSliceMs"
        maxSliceMs = 0
        line
    }

    // The jvm-native scheduler schedules onto a worker OTHER than the caller's so the task is not picked up
    // re-entrantly on the current worker. On JS the scheduler is single-threaded and `schedule` always defers
    // to the macrotask queue (never runs the task inline on the caller's stack), so there is no current worker
    // to exclude: `scheduleExcludingCurrent` is `schedule`.
    def scheduleExcludingCurrent(t: Task): Unit = schedule(t)

    def asExecutionContext: ExecutionContext = MacrotaskExecutor

    def flush(): Unit = {}

    def reject(): Boolean = false

    def reject(key: String): Boolean = false

    def reject(key: Int): Boolean = false

    def notifyInterrupt(): Unit = {}

    def shutdown(): Unit =
        statusTimer.foreach(js.timers.clearInterval)

}
