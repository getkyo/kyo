package kyo.scheduler

class TestTask(
    run: () => Task.Result
) extends Task:
    @volatile var executions  = 0
    @volatile var preemptions = 0
    def run(startMillis: Long, clock: InternalClock) =
        try
            val r = run()
            if r == Task.Preempted then
                preemptions += 1
            r
        finally
            executions += 1
    end run
end TestTask

object TestTask:
    def apply(run: => Task.Result = Task.Done): TestTask =
        new TestTask(() => run)
