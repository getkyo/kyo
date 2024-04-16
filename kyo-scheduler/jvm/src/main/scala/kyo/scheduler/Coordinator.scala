package kyo.scheduler

import Coordinator.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kyo.scheduler.util.Flag
import kyo.scheduler.util.MovingStdDev
import kyo.scheduler.util.Threads
import kyo.stats.internal.MetricReceiver
import org.slf4j.LoggerFactory
import scala.util.control.NonFatal

private object Coordinator:

    private[Coordinator] val log = LoggerFactory.getLogger(getClass)

    case class Config(
        enable: Boolean = Flag("coordinator.enable", true),
        cycleExp: Int = Flag("coordinator.cycleExp", 2),
        adaptExp: Int = Flag("coordinator.adaptExp", 8),
        loadAvgTarget: Double = Flag("coordinator.loadAvgTarget", 0.8),
        jitterMaxMs: Double = Flag("coordinator.jitterMax", 0.1),
        jitterSoftMaxMs: Double = Flag("coordinator.jitterSoftMax", 0.8),
        delayCycles: Int = Flag("coordinator.delayCycles", 2),
        executor: Executor = Executors.newSingleThreadExecutor(Threads("kyo-coordinator"))
    )

    def load(
        sleepMs: Long => Unit,
        addWorker: () => Unit,
        removeWorker: () => Unit,
        cycleWorkers: Long => Unit,
        loadAvg: () => Double,
        config: Config = Config()
    ): Coordinator =
        val c = Coordinator(sleepMs, addWorker, removeWorker, cycleWorkers, loadAvg, config)
        if config.enable then
            config.executor.execute { () =>
                while true do
                    c.update()
            }
        end if
        c
    end load

end Coordinator

private class Coordinator(
    sleepMs: Long => Unit,
    addWorker: () => Unit,
    removeWorker: () => Unit,
    cycleWorkers: Long => Unit,
    loadAvg: () => Double,
    config: Config
):

    import config.*

    private val log = LoggerFactory.getLogger(getClass)

    private val cycleTicks = Math.pow(2, cycleExp).intValue()
    private val cycleMask  = cycleTicks - 1

    private val adaptTicks = Math.pow(2, adaptExp).intValue()
    private val adaptMask  = adaptTicks - 1

    val a1, a2, a3, a4, a5, a6, a7 = 0L // padding

    @volatile private var ticks: Long = 0L

    val b1, b2, b3, b4, b5, b6, b7 = 0L // padding

    @volatile private var cycles = 0L

    val c1, c2, c3, c4, c5, c6, c7 = 0L // padding

    private val delayNs = new MovingStdDev(cycleExp)

    def load(): Unit         = {}
    def currentTick(): Long  = ticks
    def currentCycle(): Long = cycles

    private def jitterMs() =
        delayNs.dev().doubleValue() / 1000000

    private def update() =
        try
            val startNs = System.nanoTime()
            sleepMs(1)
            val endNs = System.nanoTime()
            ticks += 1
            delayNs.observe(endNs - startNs - 1000000)
            if (ticks & cycleMask) == 0 then
                cycles += 1
                cycleWorkers(cycles)
            if (ticks & adaptMask) == 0 then
                adapt()
        catch
            case ex if NonFatal(ex) =>
                log.error("Kyo coordinator failure", ex)

    private def adapt() =
        if cycles > delayCycles then
            val j = jitterMs()
            val l = loadAvg()
            if j >= jitterMaxMs then
                stats.removeWorker += 1
                removeWorker()
            else if j <= jitterSoftMaxMs && l > loadAvgTarget then
                stats.addWorker += 1
                addWorker()
            end if
        end if
    end adapt

    private object stats:
        var addWorker    = 0L
        var removeWorker = 0L
        val scope        = Scheduler.stats.scope :+ "coordinator"
        val receiver     = MetricReceiver.get
        receiver.gauge(scope, "delay_avg_ns")(delayNs.avg().toDouble)
        receiver.gauge(scope, "delay_dev_ns")(delayNs.dev().toDouble)
        receiver.gauge(scope, "jitter_current_ms")(jitterMs())
        receiver.gauge(scope, "jitter_max_ms")(jitterMaxMs)
        receiver.gauge(scope, "jitter_soft_max_ms")(jitterSoftMaxMs)
        receiver.gauge(scope, "current_tick")(currentTick().toDouble)
        receiver.gauge(scope, "current_cycle")(currentCycle().toDouble)
        receiver.gauge(scope, "worker_add")(addWorker.toDouble)
        receiver.gauge(scope, "worker_remove")(removeWorker.toDouble)
    end stats

    override def toString =
        s"Coordinator(ticks=$ticks,cycles=$cycles,delay.dev=${delayNs.dev()},delay.avg=${delayNs.avg()},jitter=${jitterMs()})"
end Coordinator
