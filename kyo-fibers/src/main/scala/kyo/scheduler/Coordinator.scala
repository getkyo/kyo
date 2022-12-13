package kyo.scheduler

import java.util.concurrent.Executors
import scala.util.control.NonFatal.apply
import scala.util.control.NonFatal
import scala.util.Random

object Coordinator {

  private val cycleTicks            = 128
  private val cycleMask             = cycleTicks - 1
  @volatile private var ticks: Long = 0L
  @volatile private var cycles      = 0L
  private val delay                 = MovingStdDev(7)
  private var start                 = 0L

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
    delay().doubleValue() / 10000

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
    if((cycles & 15) == 0) println(Scheduler)
    val j = jitter()
    if (j >= 0.08)
      Scheduler.concurrency(Math.max(1, Scheduler.concurrency.get() - 1))
    else if (j <= 0.04 && Scheduler.loadAvg() > 0.8)
      Scheduler.concurrency(Scheduler.concurrency.get() + 1)

  override def toString =
    s"Clock(ticks=$ticks,cycles=$cycles,jitter=${jitter()},delay=${delay.avgg()})"
}
