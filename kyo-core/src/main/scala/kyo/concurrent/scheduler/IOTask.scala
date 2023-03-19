package kyo.concurrent.scheduler

import kyo.concurrent.fibers._
import kyo.core._
import kyo.ios._
import kyo.locals._
import kyo.resources._

import scala.annotation.tailrec
import scala.util.control.NonFatal
import org.jctools.queues.MpmcArrayQueue
import scala.collection.mutable.ArrayDeque

private[kyo] object IOTask {
  private def nullIO[T] = null.asInstanceOf[T > IOs]
  /*inline(2)*/
  def apply[T](
      /*inline(2)*/ v: T > (IOs | Fibers),
      st: Locals.State,
      ensures: ArrayDeque[() => Unit] = buffer(),
      runtime: Int = 0
  ): IOTask[T] =
    val f = new IOTask[T](v, st, ensures, runtime)
    Scheduler.schedule(f)
    f

  private val bufferCache = new MpmcArrayQueue[ArrayDeque[() => Unit]](1000)
  private def buffer(): ArrayDeque[() => Unit] =
    val b = bufferCache.poll()
    if (b == null) ArrayDeque()
    else b

  private var token = 0
  private def avoidUnstableIf(): Boolean =
    token < 20000 && {
      token += 1
      token % 2 == 0
    }
}

private[kyo] final class IOTask[T](
    private var curr: T > (IOs | Fibers),
    private val st: Locals.State,
    private var ensures: ArrayDeque[() => Unit],
    private var runtime: Int
) extends IOPromise[T]
    with Comparable[IOTask[_]]
    with Preempt {
  import IOTask._

  private val creationTs = Coordinator.tick()

  @volatile private var preempting = false

  def preempt() =
    preempting = true

  override protected def onComplete(): Unit =
    preempt()

  def ensure(f: () => Unit): Unit = ensures.addOne(f)

  def apply(): Boolean =
    preempting

  @tailrec private def eval(start: Long, curr: T > (IOs | Fibers)): T > (IOs | Fibers) =
    def finalize() = {
      ensures.foreach(_())
      ensures.clear()
      bufferCache.offer(ensures)
    }
    if (preempting || avoidUnstableIf()) {
      if (isDone()) {
        finalize()
        nullIO
      } else {
        curr
      }
    } else {
      curr match {
        case kyo: Kyo[IO, IOs, Unit, T, IOs | Fibers] @unchecked if (kyo.effect eq IOs) =>
          eval(start, kyo((), this, st))
        case kyo: Kyo[IOPromise, Fibers, Any, T, IOs | Fibers] @unchecked
            if (kyo.effect eq Fibers) =>
          this.interrupts(kyo.value)
          runtime += (Coordinator.tick() - start).asInstanceOf[Int]
          kyo.value.onComplete { (v: Any > IOs) =>
            val io = v(kyo(_, this.asInstanceOf[Safepoint[Fibers]], st))
            this.become(IOTask(io, st, ensures, runtime))
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
      curr = eval(start, curr)
      preempting = false
      if (curr != nullIO) {
        runtime += (Coordinator.tick() - start).asInstanceOf[Int]
      }
    } catch {
      case ex if (NonFatal(ex)) =>
        complete(IOs[T, Nothing](throw ex))
        curr = nullIO
    }
  }

  def reenqueue(): Boolean =
    curr != nullIO

  def delay() = Coordinator.tick() - creationTs - runtime

  final def compareTo(other: IOTask[_]): Int =
    (other.runtime - runtime).asInstanceOf[Int]

  override final def toString =
    s"IOTask(id=${hashCode},runtime=$runtime,preempting=$preempting,ensures.size=${ensures.size})"
}
