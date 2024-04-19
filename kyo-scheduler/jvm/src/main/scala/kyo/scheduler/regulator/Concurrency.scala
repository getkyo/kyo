package kyo.scheduler.regulator

import java.util.concurrent.ScheduledExecutorService
import kyo.scheduler.*
import kyo.scheduler.Task.Result
import org.jctools.queues.MpscUnboundedArrayQueue

final class Concurrency(
    loadAvg: () => Double,
    schedule: Task => Unit,
    updateConcurrency: Int => Unit,
    executor: ScheduledExecutorService,
    config: Config =
        Config(
            collectWindowExp = 11, // 2^11=2048 ~2 regulate intervals
            collectIntervalMs = 100,
            collectSamples = 1,
            regulateIntervalMs = 1000,
            jitterUpperThreshold = 1000000,
            jitterLowerThreshold = 800000,
            loadAvgTarget = 0.8,
            stepExp = 1.3
        )
) extends Regulator(loadAvg, executor, config):

    final private class ProbeTask extends Task(0):
        var start = 0L
        def run(startMillis: Long, clock: Clock): Result =
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
