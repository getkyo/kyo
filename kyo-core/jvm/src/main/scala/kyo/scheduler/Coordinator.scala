package kyo.scheduler

import java.util.concurrent.Executors
import jdk.internal.vm.annotation.Contended
import kyo.Logs
import kyo.scheduler.util.Flag
import kyo.scheduler.util.MovingStdDev
import kyo.scheduler.util.Threads
import scala.util.control.NonFatal

private object Coordinator:

    private val enable          = Flag("coordinator.enable", true)
    private val cycleExp        = Flag("coordinator.cycleExp", 2)
    private val adaptExp        = Flag("coordinator.adaptExp", 8)
    private val loadAvgTarget   = Flag("coordinator.loadAvgTarget", 0.8)
    private val jitterMaxMs     = Flag("coordinator.jitterMax", 0.1)
    private val jitterSoftMaxMs = Flag("coordinator.jitterSoftMax", 0.8)
    private val delayCycles     = Flag("coordinator.delayCycles", 2)

    private val cycleTicks = Math.pow(2, cycleExp).intValue()
    private val cycleMask  = cycleTicks - 1

    private val adaptTicks = Math.pow(2, adaptExp).intValue()
    private val adaptMask  = adaptTicks - 1

    @Contended @volatile private var ticks: Long = 0L
    @Contended @volatile private var cycles      = 0L

    private val delayNs = new MovingStdDev(cycleExp)

    if enable then
        Executors
            .newSingleThreadExecutor(Threads("kyo-coordinator"))
            .execute { () =>
                while true do
                    update()
            }
    end if

    def load(): Unit         = {}
    def currentTick(): Long  = ticks
    def currentCycle(): Long = cycles

    private def jitterMs() =
        delayNs.dev().doubleValue() / 1000000

    private def update() =
        try
            val startNs = System.nanoTime()
            Thread.sleep(1)
            val endNs = System.nanoTime()
            ticks += 1
            delayNs.observe(endNs - startNs - 1000000)
            if (ticks & cycleMask) == 0 then
                cycles += 1
                Scheduler.cycle(cycles)
            if (ticks & adaptMask) == 0 then
                adapt()
        catch
            case ex if NonFatal(ex) =>
                Logs.logger.error("Kyo coordinator failure", ex)

    private def adapt() =
        if cycles > delayCycles then
            val j = jitterMs()
            val l = Scheduler.loadAvg()
            if j >= jitterMaxMs then
                stats.removeWorker += 1
                Scheduler.removeWorker()
            else if j <= jitterSoftMaxMs && l > loadAvgTarget then
                stats.addWorker += 1
                Scheduler.addWorker()
            end if
        end if
    end adapt

    object stats:
        var addWorker    = 0L
        var removeWorker = 0L
        val s            = Scheduler.stats.scope.scope("coordinator")
        s.initGauge("delay_avg_ns")(delayNs.avg().toDouble)
        s.initGauge("delay_dev_ns")(delayNs.dev().toDouble)
        s.initGauge("jitter_current_ms")(jitterMs())
        s.initGauge("jitter_max_ms")(jitterMaxMs)
        s.initGauge("jitter_soft_max_ms")(jitterSoftMaxMs)
        s.initGauge("current_tick")(currentTick().toDouble)
        s.initGauge("current_cycle")(currentCycle().toDouble)
        s.initGauge("worker_add")(addWorker.toDouble)
        s.initGauge("worker_remove")(removeWorker.toDouble)
    end stats

    override def toString =
        s"Coordinator(ticks=$ticks,cycles=$cycles,delay.dev=${delayNs.dev()},delay.avg=${delayNs.avg()},jitter=${jitterMs()})"
end Coordinator
