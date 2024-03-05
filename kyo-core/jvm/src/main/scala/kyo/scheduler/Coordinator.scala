package kyo.scheduler

import java.util.concurrent.Executors
import jdk.internal.vm.annotation.Contended
import kyo.Logs
import kyo.scheduler.util.Flag
import kyo.scheduler.util.MovingStdDev
import kyo.scheduler.util.Threads
import scala.util.control.NonFatal

private object Coordinator:

    private val enable        = Flag("coordinator.enable", true)
    private val cycleExp      = Flag("coordinator.cycleExp", 2)
    private val adaptExp      = Flag("coordinator.adaptExp", 8)
    private val loadAvgTarget = Flag("coordinator.loadAvgTarget", 0.8)
    private val jitterMax     = Flag("coordinator.jitterMax", 0.1)
    private val jitterSoftMax = Flag("coordinator.jitterSoftMax", 0.8)
    private val delayCycles   = Flag("coordinator.delayCycles", 2)

    private val cycleTicks = Math.pow(2, cycleExp).intValue()
    private val cycleMask  = cycleTicks - 1

    private val adaptTicks = Math.pow(2, adaptExp).intValue()
    private val adaptMask  = adaptTicks - 1

    @Contended @volatile private[this] var ticks: Long = 0L
    @Contended @volatile private[this] var cycles      = 0L

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
            if j >= jitterMax then
                Scheduler.removeWorker()
            else if j <= jitterSoftMax && l > loadAvgTarget then
                Scheduler.addWorker()
            end if
        end if
    end adapt

    override def toString =
        s"Coordinator(ticks=$ticks,cycles=$cycles,delay.dev=${delayNs.dev()},delay.avg=${delayNs.avg()},jitter=${jitterMs()})"
end Coordinator
