package kyo.scheduler

import kyo.scheduler.InternalTimer.TimerTask
import scala.collection.mutable.PriorityQueue
import scala.concurrent.duration.*

class TestTimer extends InternalTimer:
    private var currentTime = 0L
    private val tasks       = new PriorityQueue[TestTimerTask]

    override def schedule(interval: Duration)(f: => Unit) =
        val task = () =>
            try f
            finally
                schedule(interval)(f)
                ()
        val scheduledTime = currentTime + interval.toMillis
        val t             = new TestTimerTask(this, scheduledTime, task)
        tasks.addOne(t)
        t
    end schedule

    def advance(duration: Duration): Unit =
        val endTime = currentTime + duration.toMillis
        while !tasks.isEmpty do
            val task = tasks.head
            if task.time <= endTime then
                currentTime = task.time
                task.run()
                tasks.dequeue()
                ()
            else
                return
            end if
        end while
    end advance

    case class TestTimerTask(timer: TestTimer, time: Long, run: () => Unit) extends TimerTask with Ordered[TestTimerTask]:
        def compare(that: TestTimerTask): Int =
            (that.time - time).toInt
        def cancel(): Boolean = ???
    end TestTimerTask
end TestTimer
