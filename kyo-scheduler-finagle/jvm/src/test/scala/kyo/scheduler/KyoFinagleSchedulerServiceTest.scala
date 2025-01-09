package kyo.scheduler

import com.twitter
import com.twitter.finagle.exp.FinagleSchedulerService
import com.twitter.util.Await
import com.twitter.util.Future
import com.twitter.util.FuturePool
import java.util.concurrent.Executors
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class KyoFinagleSchedulerServiceTest extends AnyFreeSpec with NonImplicitAssertions {

    (new KyoFinagleSchedulerService).create(Nil).foreach(twitter.concurrent.Scheduler.setUnsafe)

    def run[A](f: Future[A]) = Await.result(f)

    "uses the default local scheduler for future composition" in run {
        val thread = Thread.currentThread()
        Future(assert(thread == Thread.currentThread()))
    }

    "redirects unboundedPool" in run {
        val thread = Thread.currentThread()
        FuturePool.unboundedPool {
            assert(thread != Thread.currentThread())
            assert(Thread.currentThread().getName().contains("kyo"))
        }
    }

    "redirects interruptibleUnboundedPool" in run {
        val thread = Thread.currentThread()
        FuturePool.interruptibleUnboundedPool {
            assert(thread != Thread.currentThread())
            assert(Thread.currentThread().getName().contains("kyo"))
        }
    }

    "redirects custom FuturePool" in run {
        val thread = Thread.currentThread()
        val exec   = Executors.newCachedThreadPool()
        val pool   = FuturePool(exec)
        pool {
            assert(thread != Thread.currentThread())
            assert(Thread.currentThread().getName().contains("kyo"))
        }
    }
}
