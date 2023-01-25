package kyo.concurrent.scheduler

import kyo.core._
import kyo.ios._
import kyo.scopes._

import scala.annotation.tailrec
import scala.util.control.NonFatal

private[kyo] object IOTask {
  private def nullIO[T] = null.asInstanceOf[T > IOs]
  inline def apply[T](inline v: T > IOs): IOTask[T] =
    val f = new IOTask[T](v)
    Scheduler.schedule(f)
    f
}

private[kyo] final class IOTask[T](val init: T > IOs) extends IOPromise[T]
    with Comparable[IOTask[_]]
    with Preempt {
  import IOTask._

  val creationTs = Coordinator.tick()

  private var curr: T > IOs = init
  private var runtime       = 0L
  @volatile private var preempting    = false
  private var ensures       = List.empty[Unit > IOs]

  def preempt() =
    preempting = true

  override protected def onComplete(): Unit =
    preempting = true

  def ensure(f: Unit > IOs): Unit = ensures ::= f

  def apply(): Boolean =
    preempting

  def run(): Boolean =
    val start = Coordinator.tick()
    try {
      preempting = false
      curr = IOs.eval[T](this)(curr)
      preempting = false
    } catch {
      case ex if (NonFatal(ex)) =>
        complete(IOs[T, Nothing](throw ex))
        curr = nullIO
    } finally {
      runtime += Coordinator.tick() - start
    }
    if (curr != nullIO && IOs.isDone(curr)) {
      complete(curr)
      curr = nullIO
      true
    } else if (super.isDone) {
      ensures.foreach(IOs.run(_))
      curr = nullIO
      true
    } else {
      false
    }

  def delay() = Coordinator.tick() - creationTs - runtime

  final def compareTo(other: IOTask[_]): Int =
    (other.runtime - runtime).asInstanceOf[Int]

  override final def toString =
    s"IOTask(id=${hashCode},runtime=$runtime,curr=$curr)"
}
