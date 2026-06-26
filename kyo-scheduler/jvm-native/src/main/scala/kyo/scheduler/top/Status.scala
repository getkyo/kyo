package kyo.scheduler.top

case class Status(
    currentWorkers: Int,
    allocatedWorkers: Int,
    loadAvg: Double,
    flushes: Long,
    activeThreads: Int,
    totalThreads: Int,
    workers: Seq[WorkerStatus],
    admission: AdmissionStatus,
    concurrency: ConcurrencyStatus
) {
    private def delta(a: Seq[WorkerStatus], b: Seq[WorkerStatus]) =
        a.zipAll(b, null, null).map {
            case (a, null) => a
            case (null, b) => b
            case (a, b)    => a - b
        }

    infix def -(other: Status): Status =
        Status(
            currentWorkers,
            allocatedWorkers,
            loadAvg,
            flushes - other.flushes,
            activeThreads,
            totalThreads,
            delta(workers, other.workers),
            admission - other.admission,
            concurrency - other.concurrency
        )
}

case class WorkerStatus(
    id: Int,
    running: Boolean,
    mount: String,
    frame: String,
    isBlocked: Boolean,
    isStalled: Boolean,
    executions: Long,
    preemptions: Long,
    completions: Long,
    stolenTasks: Long,
    lostTasks: Long,
    load: Int,
    mounts: Long
) {
    infix def -(other: WorkerStatus): WorkerStatus =
        WorkerStatus(
            id,
            running,
            mount,
            frame,
            isBlocked,
            isStalled,
            executions - other.executions,
            preemptions - other.preemptions,
            completions - other.completions,
            stolenTasks - other.stolenTasks,
            lostTasks - other.lostTasks,
            load,
            mounts - other.mounts
        )
}

/** One scheduler worker that holds work (`load > 0`) at the end-of-run leak probe: its mount thread name and the
  * rendered kyo Trace of the task it is running. `fiberTrace` is the multi-line, newest-first kyo frame render when
  * the task carries a kyo trace (an IOTask-backed fiber), or "" when it does not (a plain `Task.apply` task, a
  * scheduler-internal task), in which case the leak report shows only the JVM thread stack for this worker.
  *
  * @param mount
  *   the worker's mount thread name (empty when the worker is momentarily unmounted)
  * @param fiberTrace
  *   the rendered kyo Trace, newest-first, or "" when the running task carries no kyo trace
  */
case class BusyWorker(mount: String, fiberTrace: String)

case class TaskStatus(
    preempting: Boolean,
    runtime: Int
)

case class RegulatorStatus(
    step: Int,
    measurementsAvg: Double,
    measurementsJitter: Double,
    probesSent: Long,
    probesCompleted: Long,
    adjustments: Long,
    updates: Long
) {
    infix def -(other: RegulatorStatus): RegulatorStatus =
        RegulatorStatus(
            step,
            measurementsAvg,
            measurementsJitter,
            probesSent - other.probesSent,
            probesCompleted - other.probesCompleted,
            adjustments - other.adjustments,
            updates - other.updates
        )
}

case class ConcurrencyStatus(
    regulator: RegulatorStatus
) {
    infix def -(other: ConcurrencyStatus): ConcurrencyStatus =
        ConcurrencyStatus(regulator - other.regulator)
}

case class AdmissionStatus(
    admissionPercent: Int,
    allowed: Long,
    rejected: Long,
    regulator: RegulatorStatus
) {
    infix def -(other: AdmissionStatus): AdmissionStatus =
        AdmissionStatus(
            admissionPercent,
            allowed - other.allowed,
            rejected - other.rejected,
            regulator - other.regulator
        )
}
