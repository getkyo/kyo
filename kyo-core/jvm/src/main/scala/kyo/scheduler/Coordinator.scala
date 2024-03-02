package kyo.scheduler

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import jdk.internal.vm.annotation.Contended
import scala.concurrent.duration.*
import scala.util.control.NonFatal

private object Coordinator:

    private val enable        = Flag("coordinator.enable", true)
    private val cycleExp      = Flag("coordinator.cycleExp", 8)
    private val loadAvgTarget = Flag("coordinator.loadAvgTarget", 0.8)
    private val jitterMax     = Flag("coordinator.jitterMax", 0.1)
    private val jitterSoftMax = Flag("coordinator.jitterSoftMax", 0.8)
    private val delayCycles   = Flag("coordinator.delayCycles", 2)

    private val cycleTicks = Math.pow(2, cycleExp).intValue()
    private val cycleMask  = cycleTicks - 1

    @Contended @volatile private[this] var ticks: Long = 0L
    @Contended @volatile private[this] var cycles      = 0L

    private var startNs = 0L
    private val delayNs = new MovingStdDev(cycleExp)

    if enable then
        val exec = Executors.newCachedThreadPool(Threads("kyo-coordinator"))

        exec.execute { () =>
            startNs = System.nanoTime()
            while true do
                update(exec)
        }
    end if

    def load(): Unit  = {}
    def tick(): Long  = ticks
    def cycle(): Long = cycles

    private def jitter() =
        delayNs.dev().doubleValue() / 1000000

    private def update(exec: Executor) =
        try
            Thread.sleep(1)
            ticks += 1
            val endNs = System.nanoTime()
            delayNs.observe(endNs - startNs - 1000000)
            startNs = endNs
            if (ticks & cycleMask) == 0 then
                cycles += 1
                exec.execute(adapt)
        catch
            case ex if NonFatal(ex) =>
                ex.printStackTrace()

    private val adapt: Runnable =
        () =>
            if cycles > delayCycles then
                // if (cycles % 7 == 0) {
                //   println(this)
                //   println(Scheduler)
                // }
                Scheduler.cycle()
                val j = jitter()
                val l = Scheduler.loadAvg()
                if j >= jitterMax then
                    Scheduler.removeWorker()
                else if j <= jitterSoftMax && l > loadAvgTarget then
                    Scheduler.addWorker()
                else if l < loadAvgTarget then
                    Scheduler.removeWorker()
                end if
            end if

    override def toString =
        s"Coordinator(ticks=$ticks,cycles=$cycles,delay.dev=${delayNs.dev()},delay.avg=${delayNs.avg()},jitter=${jitter()})"
end Coordinator
