package kyo.scheduler

import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class SchedulerSingletonTest extends AnyFreeSpec with NonImplicitAssertions {

    "should return same instance across multiple calls" in {
        val scheduler1 = Scheduler.get
        val scheduler2 = Scheduler.get

        assert(scheduler1 eq scheduler2)
    }

    "should maintain single instance across different classloaders" in {
        val cl1 = new URLClassLoader(Array.empty[URL], getClass.getClassLoader)
        val cl2 = new URLClassLoader(Array.empty[URL], getClass.getClassLoader)

        val schedulerClass1 = cl1.loadClass("kyo.scheduler.Scheduler$")
        val schedulerClass2 = cl2.loadClass("kyo.scheduler.Scheduler$")

        val scheduler1 = schedulerClass1.getField("MODULE$").get(null)
        val scheduler2 = schedulerClass2.getField("MODULE$").get(null)

        val instance1 = schedulerClass1.getMethod("get").invoke(scheduler1)
        val instance2 = schedulerClass2.getMethod("get").invoke(scheduler2)

        assert(instance1 eq instance2)
    }

    "should handle concurrent initialization safely" in {
        val threadCount = 32
        val cdl         = new CountDownLatch(threadCount)
        val executor    = Executors.newFixedThreadPool(threadCount)
        val schedulers  = ConcurrentHashMap.newKeySet[Scheduler]()

        try {
            val futures = (1 to threadCount).map { _ =>
                executor.submit(new Runnable {
                    def run(): Unit = {
                        val scheduler = Scheduler.get
                        schedulers.add(scheduler)
                        val taskCdl = new CountDownLatch(1)
                        scheduler.schedule(TestTask(_run = () => {
                            taskCdl.countDown()
                            Task.Done
                        }))
                        taskCdl.await()
                        cdl.countDown()
                    }
                })
            }

            futures.foreach(_.get(5, TimeUnit.SECONDS))

            assert(futures.size == threadCount)
            assert(schedulers.size == 1)
        } finally {
            executor.shutdown()
        }
    }
}
