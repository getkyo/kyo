package kyo.scheduler.regulator

import kyo.scheduler.*
import kyo.scheduler.Task.Result
import org.jctools.queues.MpscUnboundedArrayQueue
import scala.concurrent.duration.*

final class Concurrency(
    loadAvg: () => Double,
    schedule: Task => Unit,
    updateConcurrency: Int => Unit,
    timer: InternalTimer,
    config: Config =
        Config(
            collectWindow = 200, // 2 regulate intervals
            collectInterval = 10.millis,
            regulateInterval = 1000.millis,
            jitterUpperThreshold = 1000000,
            jitterLowerThreshold = 800000,
            loadAvgTarget = 0.8,
            stepExp = 1.3
        )
) extends Regulator(loadAvg, timer, config):

    final private class ProbeTask extends Task(0):
        var start = 0L
        def run(startMillis: Long, clock: InternalClock): Result =
            measure(System.currentTimeMillis() - start)
            start = 0
            probeTasks.add(this)
            Task.Done
        end run
    end ProbeTask

    private val probeTasks = new MpscUnboundedArrayQueue[ProbeTask](3)

    protected def probe() =
        var task = probeTasks.poll()
        if task == null then
            task = new ProbeTask
        task.start = System.currentTimeMillis()
        schedule(task)
    end probe

    protected def update(diff: Int): Unit =
        updateConcurrency(diff)

    override def toString(): String = "Concurrency()"

end Concurrency
