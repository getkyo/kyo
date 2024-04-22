package kyo.scheduler.regulator

import java.util.concurrent.ThreadLocalRandom
import kyo.scheduler.*
import kyo.scheduler.InternalTimer
import org.jctools.queues.MpscUnboundedArrayQueue
import scala.concurrent.duration.*
import scala.util.hashing.MurmurHash3

final class Admission(
    loadAvg: () => Double,
    schedule: Task => Unit,
    nowMillis: () => Long,
    timer: InternalTimer,
    config: Config =
        Config(
            collectWindow = 40, // 4 regulate intervals
            collectInterval = 100.millis,
            regulateInterval = 1000.millis,
            jitterUpperThreshold = 100,
            jitterLowerThreshold = 80,
            loadAvgTarget = 0.8,
            stepExp = 1.5
        )
) extends Regulator(loadAvg, timer, config):

    @volatile private var admissionPercent = 100

    final private class ProbeTask extends Task(0):
        var start = 0L
        def run(startMillis: Long, clock: InternalClock) =
            measure(nowMillis() - start)
            start = 0
            probeTasks.add(this)
            Task.Done
        end run
    end ProbeTask

    private val probeTasks = new MpscUnboundedArrayQueue[ProbeTask](3)

    def percent(): Int = admissionPercent

    protected def probe() =
        var task = probeTasks.poll()
        if task == null then
            task = new ProbeTask
        task.start = nowMillis()
        schedule(task)
    end probe

    protected def update(diff: Int): Unit =
        admissionPercent = Math.max(0, Math.min(100, admissionPercent - diff))

    def reject(key: String): Boolean =
        reject(MurmurHash3.stringHash(key))

    def reject(): Boolean =
        reject(ThreadLocalRandom.current().nextInt())

    def reject(key: Int): Boolean =
        (key.abs % 100) <= admissionPercent

    override def toString = s"Admission($admissionPercent)"

end Admission
