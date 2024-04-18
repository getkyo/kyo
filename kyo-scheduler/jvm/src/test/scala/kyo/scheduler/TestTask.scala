package kyo.scheduler

class TestTask(
    _preempt: () => Unit = () => {},
    _run: () => Task.Result = () => Task.Done
) extends Task:
    @volatile var executions  = 0
    @volatile var preemptions = 0

    def preempt(): Unit =
        _preempt()
    def runtime(): Int = 0
    def run() =
        try
            val r = _run()
            if r == Task.Preempted then
                preemptions += 1
            r
        finally
            executions += 1
    end run
end TestTask
