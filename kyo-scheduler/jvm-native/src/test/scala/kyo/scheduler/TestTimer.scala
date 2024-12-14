package kyo.scheduler

import kyo.scheduler.InternalTimer.TimerTask
import scala.collection.mutable.PriorityQueue
import scala.concurrent.duration.*

case class TestTimer() extends InternalTimer {
    var currentNanos  = 0L
    private val tasks = new PriorityQueue[TestTimerTask]

    override def schedule(interval: Duration)(f: => Unit): TestTimerTask = {
        val task = () => {
            try f
            finally {
                schedule(interval)(f)
                ()
            }
        }
        val scheduledTime = currentNanos + interval.toNanos
        val t             = new TestTimerTask(this, scheduledTime, task)
        tasks.enqueue(t)
        t
    }

    override def scheduleOnce(delay: Duration)(f: => Unit): TestTimerTask = {
        val task          = () => f
        val scheduledTime = currentNanos + delay.toNanos
        val t             = new TestTimerTask(this, scheduledTime, task)
        tasks.enqueue(t)
        t
    }

    def advance(duration: Duration): Unit =
        currentNanos += duration.toNanos

    def advanceAndRun(duration: Duration): Unit = {
        val endTime = currentNanos + duration.toNanos
        while (!tasks.isEmpty) {
            val task = tasks.head
            if (task.time <= endTime) {
                currentNanos = task.time
                task.run()
                tasks.dequeue()
                ()
            } else
                return
        }
    }

    case class TestTimerTask(timer: TestTimer, time: Long, run: () => Unit) extends TimerTask with Ordered[TestTimerTask] {
        def compare(that: TestTimerTask): Int =
            (that.time - time).toInt
        def cancel(): Boolean = ???
    }
}
