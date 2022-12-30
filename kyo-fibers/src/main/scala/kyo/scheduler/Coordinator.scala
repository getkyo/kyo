package kyo.scheduler

import java.util.concurrent.Executors
import scala.util.control.NonFatal.apply
import scala.util.control.NonFatal
import scala.util.Random

private object Coordinator {

  private val cycleExp   = 7
  private val cycleTicks = Math.pow(2, cycleExp).intValue()
  private val cycleMask  = cycleTicks - 1

  @volatile private[this] var ticks: Long = 0L

  val p1, p2, p3, p4, p5, p6, p7 = 0L

  @volatile private[this] var cycles = 0L

  private val delay = MovingStdDev(cycleExp)
  private var start = 0L

  val exec = Executors.newCachedThreadPool(ThreadFactory("kyo-coordinator"))

  exec.execute { () =>
    start = System.nanoTime()
    while (true) {
      update()
    }
  }

  def tick(): Long  = ticks
  def cycle(): Long = cycles

  def jitter() =
    delay.dev().doubleValue() / 10000

  private def update() =
    try {
      Thread.sleep(1)
      ticks += 1
      val end = System.nanoTime()
      delay.observe(end - start - 1000000)
      start = end
      if ((ticks & cycleMask) == 0)
        cycles += 1
        exec.execute(() => adapt())
    } catch {
      case ex if NonFatal(ex) =>
        ex.printStackTrace()
    }

  private def adapt() =
    if ((cycles & 7) == 0) {
      println(Scheduler)
      println(this)
    }
    Scheduler.workers.forEach(_.cycle())
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
    s"Clock(ticks=$ticks,cycles=$cycles,delay.dev=${delay.dev()},delay.avg=${delay.avg()})"
}
