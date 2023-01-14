package kyo.concurrent.scheduler

import java.util.concurrent.Executors
import scala.util.control.NonFatal.apply
import scala.util.control.NonFatal
import scala.util.Random

private object Coordinator {

  private val cycleExp   = 7
  private val cycleTicks = Math.pow(2, cycleExp).intValue()
  private val cycleMask  = cycleTicks - 1

  @volatile private[this] var ticks: Long = 0L

  private val a1, a2, a3, a4, a5, a6, a7 = 0L // paddding

  @volatile private[this] var cycles = 0L

  private val b1, b2, b3, b4, b5, b6, b7 = 0L // paddding

  private var startNs = 0L
  private val delayNs = MovingStdDev(cycleExp)

  private val exec = Executors.newCachedThreadPool(ThreadFactory("kyo-coordinator"))

  exec.execute { () =>
    startNs = System.nanoTime()
    while (true) {
      update()
    }
  }

  def tick(): Long  = ticks
  def cycle(): Long = cycles

  private def jitter() =
    delayNs.dev().doubleValue() / 10000

  private def update() =
    try {
      Thread.sleep(1)
      ticks += 1
      val endNs = System.nanoTime()
      delayNs.observe(endNs - startNs - 1000000)
      startNs = endNs
      if ((ticks & cycleMask) == 0)
        cycles += 1
        exec.execute(() => adapt())
    } catch {
      case ex if NonFatal(ex) =>
        ex.printStackTrace()
    }

  private def adapt() =
    // if ((cycles & 7) == 0) {
    //   println(Scheduler)
    //   println(this)
    // }
    Scheduler.cycle()
    val j = jitter()
    val l = Scheduler.loadAvg()
    if (j >= 0.08)
      Scheduler.removeWorker()
    else if (j <= 0.04 && l > 0.8)
      Scheduler.addWorker()
    else if (l < 0.8)
      Scheduler.removeWorker()
    ()

  override def toString =
    s"Clock(ticks=$ticks,cycles=$cycles,delay.dev=${delayNs.dev()},delay.avg=${delayNs.avg()},jitter=${jitter()})"
}
