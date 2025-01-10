package kyo.scheduler

import com.twitter
import com.twitter.concurrent.ForkingScheduler
import com.twitter.finagle.exp.FinagleSchedulerService
import com.twitter.util.*
import com.twitter.util.Await
import com.twitter.util.Future
import com.twitter.util.FuturePool
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec
import scala.util.control.NoStackTrace

class KyoFinagleSchedulerServiceTest extends AnyFreeSpec with NonImplicitAssertions {

    val scheduler = (new KyoFinagleSchedulerService).create(List("kyo")).get
    twitter.concurrent.Scheduler.setUnsafe(scheduler)

    def run[A](f: Future[A]) = Await.result(f)

    case class TestException() extends Exception

    "create returns None if the param isn't 'kyo'" in {
        assert((new KyoFinagleSchedulerService).create(List("blah")).isEmpty)
        assert((new KyoFinagleSchedulerService).create(List("kyo", "blah")).isEmpty)
    }

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

    "propagates interrupts to forked tasks" in {
        val p     = new Promise[Unit]
        val cdl   = new CountDownLatch(1)
        val error = TestException()

        val f = scheduler.fork {
            cdl.countDown()
            p
        }
        cdl.await()
        Thread.sleep(100)
        f.raise(error)

        assert(p.isInterrupted == Some(error))
    }

    "locals" - {

        "propagates Local context in fork()" in {
            val local = new Local[String]
            local.let("test-value") {
                run(scheduler.fork(Future {
                    assert(local() == Some("test-value"))
                }))
            }
        }

        "propagates Local context in FuturePool" in {
            val local = new Local[String]
            local.let("test-value") {
                run(FuturePool.unboundedPool {
                    assert(local() == Some("test-value"))
                })
            }
        }

        "maintains Local context across multiple fork() calls" in {
            val local = new Local[String]
            local.let("test-value") {
                run(scheduler.fork(Future {
                    local()
                }).flatMap { prev =>
                    scheduler.fork(Future {
                        assert(local() == prev)
                    })
                })
            }
        }

        "resets Local context after task completion" in {
            val local         = new Local[String]
            val originalValue = "original"
            local.let(originalValue) {
                run(scheduler.fork(Future {
                    local.let("modified") {
                        Future.Done
                    }
                }).ensure {
                    assert(local() == Some(originalValue))
                    ()
                })
            }
        }
    }
}
