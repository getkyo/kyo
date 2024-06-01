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

    private def withExecutor[T](f: ScheduledExecutorService => T): T = {
        val exec = Executors.newSingleThreadScheduledExecutor()
        try f(exec)
        finally exec.shutdown()
    }

}
