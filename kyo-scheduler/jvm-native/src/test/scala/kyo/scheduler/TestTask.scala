package kyo.scheduler

case class TestTask(
    _preempt: () => Unit = () => {},
    _run: () => Task.Result = () => Task.Done
) extends Task {
    @volatile var executions  = 0
    @volatile var preemptions = 0
    override def doPreempt(): Unit =
        _preempt()
    def run(startMillis: Long, clock: InternalClock) =
        try {
            val r = _run()
            if (r == Task.Preempted)
                preemptions += 1
            r
        } finally
            executions += 1
}
