package kyo.concurrent.scheduler

import kyo.core._
import kyo.ios._
import kyo.locals._
import kyo.resources._
import kyo.concurrent.fibers._

import scala.annotation.tailrec
import scala.util.control.NonFatal

private[kyo] object IOTask {
  private def nullIO[T] = null.asInstanceOf[T > IOs]
  /*inline(2)*/
  def apply[T](
      /*inline(2)*/ v: T > (IOs | Fibers),
      ensures: Set[() => Unit] = Set.empty
  ): IOTask[T] =
    val f = new IOTask[T](v, ensures)
    Scheduler.schedule(f)
    f
}

private[kyo] final class IOTask[T](
    val init: T > (IOs | Fibers),
    private var ensures: Set[() => Unit] = Set.empty
) extends IOPromise[T]
    with Comparable[IOTask[_]]
    with Preempt {
  import IOTask._

  val creationTs = Coordinator.tick()

  private var curr: T > (IOs | Fibers) = init
  private var runtime                  = 0L
  @volatile private var preempting     = false

  def preempt() =
    preempting = true

  override protected def onComplete(): Unit =
    preempt()

  def ensure(f: () => Unit): Unit = ensures += f

  def apply(): Boolean =
    preempting

  @tailrec private def eval(curr: T > (IOs | Fibers)): T > (IOs | Fibers) =
    def finalize() = ensures.foreach(_())
    if (preempting) {
      if (isDone()) {
        finalize()
        nullIO
      } else {
        curr
      }
    } else {
      curr match {
        case kyo: Kyo[IO, IOs, Unit, T, IOs | Fibers] @unchecked if (kyo.effect eq IOs) =>
          eval(kyo((), this, Locals.State.empty))
        case kyo: Kyo[IOPromise, Fibers, Any, T, IOs | Fibers] @unchecked
            if (kyo.effect eq Fibers) =>
          this.interrupts(kyo.value)
          kyo.value.onComplete { (v: Any > IOs) =>
            val io = v(kyo(_, this.asInstanceOf[Safepoint[Fibers]], Locals.State.empty))
            this.become(IOTask(io, ensures))
          }
          nullIO
        case _ =>
          complete(curr.asInstanceOf[T > IOs])
          finalize()
          nullIO
      }
    }

  def run(): Unit = {
    val start = Coordinator.tick()
    try {
      curr = eval(curr)
      preempting = false
    } catch {
      case ex if (NonFatal(ex)) =>
        complete(IOs[T, Nothing](throw ex))
        curr = nullIO
    }
    runtime += Coordinator.tick() - start
  }

  def reenqueue(): Boolean =
    curr != nullIO

  def delay() = Coordinator.tick() - creationTs - runtime

  final def compareTo(other: IOTask[_]): Int =
    (other.runtime - runtime).asInstanceOf[Int]

  override final def toString =
    s"IOTask(id=${hashCode},runtime=$runtime,preempting=$preempting,ensures.size=${ensures.size})"
}
