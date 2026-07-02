package kyo.scheduler.top

object Printer {

    def apply(status: Status) = {

        val sb = new StringBuilder()

        sb.append(f"""
            |╔═══════════════════════════════════════════════════════════════════════════════════════════════════╗
            |║ *..*..*.   *.    .. *    *  *  .*.  Kyo Scheduler Top  .  . *   .   *  . * *    .*.   .*.   ...*. ║ 
            |╚═══════════════════════════════════════════════════════════════════════════════════════════════════╝
            |         LoadAvg: ${status.loadAvg}%1.4f                 Flushes: ${status.flushes}             Threads: ${status
                        .activeThreads}/${status.totalThreads} (active/total)
            |=====================================================================================================
            |    Regulator   |   %% | Allow | Reject | Probes | Cmpl  | Adjmts | Updts |    Avg    |  Jitter
            |-----------------------------------------------------------------------------------------------------
            |""".stripMargin)

        // Admission regulator row
        val admission       = status.admission
        val admissionAvg    = f"${admission.regulator.measurementsAvg}%7.1f"
        val admissionJitter = f"${admission.regulator.measurementsJitter}%7.2f"
        sb.append(
            f"    Admission   | ${admission.admissionPercent}%3d | ${admission.allowed}%5d | ${admission.rejected}%6d | ${admission
                    .regulator.probesSent}%6d | ${admission.regulator.probesCompleted}%5d | ${admission.regulator
                    .adjustments}%6d | ${admission.regulator.updates}%5d | $admissionAvg%9s | $admissionJitter%8s\n"
        )

        // Concurrency regulator row
        val concurrency       = status.concurrency
        val concurrencyAvg    = f"${concurrency.regulator.measurementsAvg}%7.1f"
        val concurrencyJitter = f"${concurrency.regulator.measurementsJitter}%7.2f"
        sb.append(
            f"    Concurrency |   - |     - |      - | ${concurrency.regulator.probesSent}%6d | ${concurrency.regulator.probesCompleted}%5d | ${concurrency
                    .regulator.adjustments}%6d | ${concurrency.regulator.updates}%5d | $concurrencyAvg%9s | $concurrencyJitter%8s\n"
        )

        sb.append(f"""
            |=====================================================================================================
            | Worker | Running | Blocked | Stalled | Load  | Exec     | Done    | Preempt | Stolen | Lost | Thread
            |-----------------------------------------------------------------------------------------------------
            |""".stripMargin)

        def print(w: WorkerStatus) =
            if (w ne null) {
                if (w.id == status.currentWorkers)
                    sb.append("------------------------------------- Inactive ------------------------------------------------\n")

                val running = if (w.running) "   🏃  " else "   ⚫  "
                val blocked = if (w.isBlocked) "   🚧  " else "   ⚫  "
                val stalled = if (w.isStalled) "   🐢  " else "   ⚫  "

                sb.append(
                    f" ${w.id}%6d | $running | $blocked%-2s | $stalled%-2s | ${w.load}%5d | ${w.executions}%8d | ${w.completions}%8d | ${w
                            .preemptions}%5d | ${w.stolenTasks}%6d | ${w.lostTasks}%4d | ${w.mount} ${w.frame}\n"
                )
            }

        status.workers.foreach(print)
        sb.append("=====================================================================================================\n")

        sb.toString()
    }

    // One machine-readable line for the CI resource monitor: worker counts (blocked/stalled and the
    // allocated total that balloons toward maxWorkers under blocking), queued load, cumulative task
    // counters, and the admission percent. Absolute values, so the collector snapshots and deltas itself.
    def compact(status: Status): String = {
        val ws      = status.workers.iterator.filter(_ ne null)
        var active  = 0
        var blocked = 0
        var stalled = 0
        var load    = 0L
        var exec    = 0L
        var done    = 0L
        var stolen  = 0L
        var lost    = 0L
        ws.foreach { w =>
            if (w.running) active += 1
            if (w.isBlocked) blocked += 1
            if (w.isStalled) stalled += 1
            load += w.load.toLong
            exec += w.executions
            done += w.completions
            stolen += w.stolenTasks
            lost += w.lostTasks
        }
        f"kyo.sched ts=${java.lang.System.currentTimeMillis()} cur=${status.currentWorkers} alloc=${status.allocatedWorkers}" +
            f" active=$active blocked=$blocked stalled=$stalled load=$load loadAvg=${status.loadAvg}%.4f" +
            f" threads=${status.activeThreads}/${status.totalThreads} exec=$exec done=$done stolen=$stolen lost=$lost" +
            f" admit=${status.admission.admissionPercent}"
    }
}
