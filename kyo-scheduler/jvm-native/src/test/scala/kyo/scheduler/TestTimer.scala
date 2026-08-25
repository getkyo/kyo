package kyo.scheduler

import java.util.concurrent.atomic.AtomicBoolean
import kyo.scheduler.InternalTimer.TimerTask
import scala.collection.mutable.PriorityQueue
import scala.concurrent.duration.*

case class TestTimer() extends InternalTimer {
    var currentNanos  = 0L
    private val tasks = new PriorityQueue[TestTimerTask]

    override def schedule(interval: Duration)(f: => Unit): TestTimerTask = {
        // A recurring schedule enqueues a fresh occurrence per run, so cancellation lives in a flag shared
        // by the whole chain: the caller's handle stays valid for every occurrence, even later ones.
        val cancelled = new AtomicBoolean(false)
        def enqueue(): TestTimerTask = {
            val task = () => {
                try f
                finally {
                    if (!cancelled.get()) {
                        enqueue()
                        ()
                    }
                }
            }
            val t = new TestTimerTask(this, currentNanos + interval.toNanos, task, cancelled)
            tasks.enqueue(t)
            t
        }
        enqueue()
    }

    override def scheduleOnce(delay: Duration)(f: => Unit): TestTimerTask = {
        val cancelled     = new AtomicBoolean(false)
        val task          = () => f
        val scheduledTime = currentNanos + delay.toNanos
        val t             = new TestTimerTask(this, scheduledTime, task, cancelled)
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
                val _ = tasks.dequeue()
                if (!task.cancelled.get()) task.run()
            } else
                return
        }
    }

    case class TestTimerTask(timer: TestTimer, time: Long, run: () => Unit, cancelled: AtomicBoolean)
        extends TimerTask with Ordered[TestTimerTask] {
        def compare(that: TestTimerTask): Int =
            (that.time - time).toInt
        def cancel(): Boolean = cancelled.compareAndSet(false, true)
    }
}
