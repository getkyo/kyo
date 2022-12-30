package kyo.scheduler

import kyo.core._
import kyo.frames._
import kyo.ios._

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.util.control.NoStackTrace

import IOPromise._
import scala.util.control.NonFatal

abstract class IOTask[T] extends IOPromise[T]
    with Comparable[IOTask[_]]
    with Preempt {
  import IOTask._

  val creationTs = Coordinator.tick()

  private var curr: T > IOs = uninitialized

  var runtime = 0L

  @volatile private var preempting = false

  def preempt() =
    preempting = true

  override protected def onComplete(): Unit =
    preempting = true

  def apply() = preempting

  def init(): T > IOs

  def run(): Boolean =
    preempting = false
    val start = Coordinator.tick()
    if (curr == uninitialized) {
      curr = init()
    }
    try {
      curr = IOs.eval[T](this)(curr)
      if (super.isDone) {
        curr = uninitialized
        true
      } else if (IOs.isDone(curr)) {
        complete(curr)
        true
      } else {
        false
      }
    } catch {
      case ex if (NonFatal(ex)) =>
        complete(IOs(throw ex))
        curr = uninitialized
        true
    } finally {
      runtime += Coordinator.tick() - start
    }

  final def compareTo(other: IOTask[_]): Int =
    (other.runtime - runtime).asInstanceOf[Int]

  override final def toString =
    s"IOTask(id=${hashCode},runtime=$runtime,curr=$curr)"
}

object IOTask {
  private val uninitialized: Nothing > IOs = null.asInstanceOf[Nothing > IOs]
  inline def apply[T](inline v: => T > IOs): IOTask[T] =
    val f = new IOTask[T] {
      def init() = v
    }
    Scheduler.schedule(f)
    f
}
