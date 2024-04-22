package kyo.scheduler.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class XSRandomTest extends AnyFreeSpec with Matchers:

    "distribution" in {
        val numThreads = 8
        val numSamples = 1000000
        val numBuckets = 10

        val executor = Executors.newFixedThreadPool(numThreads)
        val latch    = new CountDownLatch(numThreads)

        val buckets = Array.fill(numBuckets)(0)

        (1 to numThreads).foreach { _ =>
            executor.execute(() =>
                val localBuckets = new Array[Int](numBuckets)
                (1 to numSamples).foreach { _ =>
                    val randomInt = XSRandom.nextInt(numBuckets)
                    localBuckets(randomInt) += 1
                }
                buckets.synchronized {
                    localBuckets.zipWithIndex.foreach { case (count, index) =>
                        buckets(index) += count
                    }
                }
                latch.countDown()
            )
        }

        latch.await()
        executor.shutdown()

        val expectedCount = numSamples * numThreads / numBuckets
        val tolerance     = expectedCount * 0.05

        buckets.foreach { count =>
            assert(count >= (expectedCount - tolerance))
            assert(count <= (expectedCount + tolerance))
        }
    }
end XSRandomTest
