package kyo.scheduler.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kyo.scheduler.*
import scala.annotation.tailrec

final class SelfCheck(
    scheduler: Scheduler = Scheduler.get,
    executor: Executor = Executors.newCachedThreadPool(Threads("kyo-selfcheck")),
    rejectionThreshold: Double = 0.2,
    taskDurationMs: Int = 1000,
    monitorIntervalMs: Int = 100,
    stepMs: Int = 5000
) {

    private var clients    = 0
    private val admissions = new AtomicLong
    private val rejections = new AtomicLong

    @volatile private var stop = false

    def run(): Unit = {
        @tailrec def loop(): Unit = {
            val rejectionPercent = {
                val a = admissions.getAndSet(0)
                val b = rejections.getAndSet(0)
                if (a > 0) b.toDouble / a
                else if (b > 0) 1d
                else 0d
            }
            println(Map(
                "clients"            -> clients,
                "rejectionPercent"   -> rejectionPercent,
                "rejectionThreshold" -> rejectionThreshold
            ))
            if (rejectionPercent <= rejectionThreshold) {
                clients += 1
                startClient()
                Thread.sleep(stepMs)
                loop()
            } else {
                val cores = Runtime.getRuntime().availableProcessors()
                val high  = cores * 1.5 + 2
                val low   = cores * 0.8
                if (clients > high || clients < low)
                    println(s"Failure: Expected between $low and $high clients for $cores cores but found $clients.")
                else
                    println("Success")
            }
        }
        startMonitor()
        loop()
        stop = true
    }

    def startMonitor(): Unit =
        executor.execute(() =>
            while (!stop) {
                if (scheduler.reject()) rejections.incrementAndGet()
                else admissions.incrementAndGet()
                Thread.sleep(monitorIntervalMs)
            }
        )

    def startClient(): Unit =
        executor.execute(() =>
            while (!stop)
                runClient()
        )

    def runClient(): Unit = {
        val cdl = new CountDownLatch(1)
        val task = Task {
            var acc       = 0d
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < taskDurationMs)
                acc += BigInt(2).pow(1000000).toDouble
            cdl.countDown()
        }
        scheduler.schedule(task)
        cdl.await()
    }
}

object SelfCheck extends App {
    new SelfCheck().run()
}
