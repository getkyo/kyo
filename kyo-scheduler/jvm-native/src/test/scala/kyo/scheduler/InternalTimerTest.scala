package kyo.scheduler

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec
import scala.concurrent.duration.*

class InternalTimerTest extends AnyFreeSpec with NonImplicitAssertions {

    "schedule" in withExecutor { exec =>
        val timer = InternalTimer(exec)
        val cdl   = new CountDownLatch(1)
        val task  = timer.schedule(1.nano)(cdl.countDown())
        cdl.await()
        task.cancel()
    }

    "scheduleOnce" in withExecutor { exec =>
        val timer = InternalTimer(exec)
        val cdl   = new CountDownLatch(1)
        val task  = timer.scheduleOnce(1.nano)(cdl.countDown())
        cdl.await()
        task.cancel()
    }

    // Uses its own executor, not TestExecutors.scheduled, because Schedulers created by other
    // test suites occupy threads from the shared pool via BlockingMonitor's submit + parkNanos.
    private val ownExecutor = Executors.newSingleThreadScheduledExecutor()

    private def withExecutor[A](f: ScheduledExecutorService => A): A =
        f(ownExecutor)

}
