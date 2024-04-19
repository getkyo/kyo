package kyo.scheduler

class TestTask(
    _preempt: () => Unit = () => {},
    _run: () => Task.Result = () => Task.Done
) extends Task(0):
    @volatile var executions  = 0
    @volatile var preemptions = 0
    override def doPreempt(): Unit =
        _preempt()
    def run(startMillis: Long, clock: Clock) =
        try
            val r = _run()
            if r == Task.Preempted then
                preemptions += 1
            r
        finally
            executions += 1
    end run
end TestTask
