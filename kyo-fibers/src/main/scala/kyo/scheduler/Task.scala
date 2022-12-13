package kyo.scheduler

import scala.runtime.AbstractFunction0

class Task(init: Preemptable) extends Comparable[Task] {

  var curr    = init
  var runtime = 0L
  var preempts = 0L

  def run(preempt: () => Boolean): Boolean =
    val start = Coordinator.tick()
    try {
      curr.run(preempt) match {
        case Preemptable.Done =>
          true
        case pending =>
          preempts += 1
          curr = pending
          false
      }
    } finally {
      runtime += Coordinator.tick() - start
    }

  def compareTo(other: Task): Int =
    (runtime - other.runtime).asInstanceOf[Int]

  override def toString = s"Task(id=${hashCode},runtime=$runtime,preempts=$preempts)"
}
