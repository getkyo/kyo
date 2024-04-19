package kyo.scheduler

import kyo.scheduler.InternalTimer.TimerTask
import scala.concurrent.duration.*

class TestTimer extends InternalTimer:
    private var currentTime = 0L
    private var tasks       = List.empty[(Long, () => Unit)]

    override def schedule(interval: Duration)(f: => Unit) =
        val task = () =>
            try f
            finally
                schedule(interval)(f)
                ()
        val scheduledTime = currentTime + interval.toMillis
        tasks = tasks :+ (scheduledTime, task)
        new TestTimerTask(this, task)
    end schedule

    def advance(duration: Duration): Unit =
        val endTime = currentTime + duration.toMillis
        while tasks.headOption.exists(_._1 <= endTime) do
            val (time, task) = tasks.head
            currentTime = time
            task()
            tasks = tasks.tail
        end while
        currentTime = endTime
    end advance

    private class TestTimerTask(timer: TestTimer, task: () => Unit) extends TimerTask:
        override def cancel(): Boolean =
            timer.tasks = timer.tasks.filter(_._2 ne task)
            true
    end TestTimerTask
end TestTimer
