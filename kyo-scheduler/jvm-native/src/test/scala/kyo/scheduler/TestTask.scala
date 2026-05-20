package kyo.scheduler

case class TestTask(
    _preempt: () => Unit = () => {},
    _run: () => Task.Result = () => Task.Done
) extends Task {
    @volatile var executions  = 0
    @volatile var preemptions = 0
    @volatile var interrupted = false
    override def doPreempt(): Unit =
        _preempt()
    // Real IOTasks derive needsInterrupt from their promise; this test hook lets a
    // BlockingMonitor test drive the monitor's interrupt-dispatch path directly.
    override def needsInterrupt(): Boolean =
        interrupted
    def run(startMillis: Long, clock: InternalClock, deadline: Long) =
        try {
            val r = _run()
            if (r == Task.Preempted)
                preemptions += 1
            r
        } finally
            executions += 1
}
