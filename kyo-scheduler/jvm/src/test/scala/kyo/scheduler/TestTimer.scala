package kyo.scheduler

import kyo.scheduler.InternalTimer.TimerTask
import scala.collection.mutable.PriorityQueue
import scala.concurrent.duration.*

class TestTimer extends InternalTimer:
    var currentNanos  = 0L
    private val tasks = new PriorityQueue[TestTimerTask]

    override def schedule(interval: Duration)(f: => Unit) =
        val task = () =>
            try f
            finally
                schedule(interval)(f)
                ()
        val scheduledTime = currentNanos + interval.toNanos
        val t             = new TestTimerTask(this, scheduledTime, task)
        tasks.addOne(t)
        t
    end schedule

    override def scheduleOnce(delay: Duration)(f: => Unit) =
        val task          = () => f
        val scheduledTime = currentNanos + delay.toNanos
        val t             = new TestTimerTask(this, scheduledTime, task)
        tasks.addOne(t)
        t
    end scheduleOnce

    def advance(duration: Duration): Unit =
        currentNanos += duration.toNanos

    def advanceAndRun(duration: Duration): Unit =
        val endTime = currentNanos + duration.toNanos
        while !tasks.isEmpty do
            val task = tasks.head
            if task.time <= endTime then
                currentNanos = task.time
                task.run()
                tasks.dequeue()
                ()
            else
                return
            end if
        end while
    end advanceAndRun

    case class TestTimerTask(timer: TestTimer, time: Long, run: () => Unit) extends TimerTask with Ordered[TestTimerTask]:
        def compare(that: TestTimerTask): Int =
            (that.time - time).toInt
        def cancel(): Boolean = ???
    end TestTimerTask
end TestTimer
