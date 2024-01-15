package kyo.scheduler

import java.util.concurrent.Executors
import scala.util.Random
import scala.util.control.NonFatal
import scala.util.control.NonFatal.apply

private object Coordinator {

  private val cycleExp      = Flag("coordinator.cycleExp", 8)
  private val loadAvgTarget = Flag("coordinator.loadAvgTarget", 0.8)
  private val jitterMax     = Flag("coordinator.jitterMax", 0.1)
  private val jitterSoftMax = Flag("coordinator.jitterSoftMax", 0.8)

  private val cycleTicks = Math.pow(2, cycleExp).intValue()
  private val cycleMask  = cycleTicks - 1

  @volatile private[this] var ticks: Long = 0L

  private val a1, a2, a3, a4, a5, a6, a7 = 0L // paddding

  @volatile private[this] var cycles = 0L

  private val b1, b2, b3, b4, b5, b6, b7 = 0L // paddding

  private var startNs = 0L
  private val delayNs = new MovingStdDev(cycleExp)

  private val exec = Executors.newCachedThreadPool(Threads("kyo-coordinator"))

  exec.execute { () =>
    startNs = System.nanoTime()
    while (true) {
      update()
    }
  }

  def load(): Unit  = {}
  def tick(): Long  = ticks
  def cycle(): Long = cycles

  private def jitter() =
    delayNs.dev().doubleValue() / 1000000

  private def update() =
    try {
      Thread.sleep(1)
      ticks += 1
      val endNs = System.nanoTime()
      delayNs.observe(endNs - startNs - 1000000)
      startNs = endNs
      if ((ticks & cycleMask) == 0) {
        cycles += 1
        exec.execute(adapt)
      }
    } catch {
      case ex if NonFatal(ex) =>
        ex.printStackTrace()
    }

  private val adapt: Runnable =
    () => {
      // if (cycles % 7 == 0) {
      //   println(this)
      //   println(Scheduler)
      // }
      Scheduler.cycle()
      val j = jitter()
      val l = Scheduler.loadAvg()
      if (j >= jitterMax)
        Scheduler.removeWorker()
      else if (j <= jitterSoftMax && l > loadAvgTarget)
        Scheduler.addWorker()
      else if (l < loadAvgTarget)
        Scheduler.removeWorker()
    }

  override def toString =
    s"Coordinator(ticks=$ticks,cycles=$cycles,delay.dev=${delayNs.dev()},delay.avg=${delayNs.avg()},jitter=${jitter()})"
}
