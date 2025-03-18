package kyo.kernel.internal

import java.util.concurrent.Executors
import kyo.Test

class TracePoolConcurrencyTest extends Test:

    class TestLocal extends TracePool.Local

    "borrow should never return null under concurrency" in {
        val threadCount         = 8
        val iterationsPerThread = 1000
        val locals              = List.fill(threadCount)(new TestLocal)
        val executor            = Executors.newFixedThreadPool(threadCount)

        @volatile var foundNull    = false
        @volatile var nullLocation = ""

        try
            val futures = locals.map { local =>
                executor.submit {
                    new Runnable:
                        def run(): Unit =
                            val batchSizes = List(
                                1,
                                2,
                                TracePool.localCapacity - 1,
                                TracePool.localCapacity,
                                TracePool.localCapacity + 1,
                                TracePool.localCapacity * 2
                            )

                            for
                                i         <- 1 to iterationsPerThread if !foundNull
                                batchSize <- batchSizes if !foundNull
                            do
                                val traces = List.fill(batchSize)(local.borrow())
                                traces.zipWithIndex.foreach { (trace, idx) =>
                                    if trace == null then
                                        foundNull = true
                                        nullLocation = s"First batch (size $batchSize) at index $idx"
                                }

                                val (toRelease, toKeep) = traces.splitAt(batchSize / 2)
                                toRelease.foreach(local.release)

                                val nextBatchSize = batchSize + 1
                                val moreBorrowed  = List.fill(nextBatchSize)(local.borrow())
                                moreBorrowed.zipWithIndex.foreach { (trace, idx) =>
                                    if trace == null then
                                        foundNull = true
                                        nullLocation = s"Second batch (size $nextBatchSize) at index $idx after releasing ${toRelease.size}"
                                }

                                toKeep.foreach(local.release)
                                moreBorrowed.foreach(local.release)
                            end for
                        end run
                }
            }

            futures.foreach(_.get())
            assert(!foundNull, s"Found null trace! Location: $nullLocation")
        finally
            executor.shutdown()
        end try
    }
end TracePoolConcurrencyTest
