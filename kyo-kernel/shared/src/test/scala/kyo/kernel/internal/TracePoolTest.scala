package kyo.kernel.internal

import java.util.concurrent.Executors
import kyo.Frame
import kyo.Test
import kyo.discard

class TracePoolTest extends Test:

    class TestLocal extends TracePool.Local

    "borrow and release traces" in {
        val local = new TestLocal
        val trace = local.borrow()
        assert(trace != null)
        local.release(trace)
        succeed
    }

    "reuse released traces" in {
        val local  = new TestLocal
        val trace1 = local.borrow()
        local.release(trace1)
        val trace2 = local.borrow()
        assert(trace1 eq trace2)
    }

    "replenish from global pool when local pool is empty" in {
        val local  = new TestLocal
        val traces = List.fill(TracePool.localCapacity + 1)(local.borrow())
        assert(traces.forall(_ != null))
        traces.foreach(local.release)
        succeed
    }

    "clear traces when returning to the pool" in {
        val local = new TestLocal

        for _ <- 0 until TracePool.localCapacity * 1000 do
            val trace = local.borrow()
            trace.frames(0) = Frame.derive
            trace.frames(1) = Frame.derive
            trace.index = 2
            local.release(trace)
        end for

        for _ <- 0 until TracePool.localCapacity * 1000 do
            val recycledTrace = local.borrow()
            assert(recycledTrace.frames.forall(_ == null))
            assert(recycledTrace.index == 0)
        end for
        succeed
    }

    "borrow should never return null" in {
        val local = new TestLocal

        val traces = List.newBuilder[Trace]
        for i <- 1 to TracePool.localCapacity * 2 do
            val trace = local.borrow()
            assert(trace != null, s"borrow returned null on iteration $i")
            traces += trace
        end for

        traces.result().foreach { trace =>
            local.release(trace)
            val borrowed = local.borrow()
            assert(borrowed != null, "borrow returned null after release")
        }
        succeed
    }

    "size management should be correct" in {
        val local = new TestLocal

        val traces = for (_ <- 1 to TracePool.localCapacity) yield local.borrow()

        local.release(traces.head)

        val borrowed = local.borrow()
        assert(borrowed != null, "Failed to borrow after single release")

        traces.tail.foreach(local.release)
        local.release(borrowed)

        val newTraces =
            for (_ <- 1 to TracePool.localCapacity) yield
                val trace = local.borrow()
                assert(trace != null, "Failed to borrow after releasing all traces")
                trace

        assert(newTraces.size == TracePool.localCapacity, s"Expected ${TracePool.localCapacity} traces, got ${newTraces.size}")
    }

    "release should properly clear traces" in {
        val local = new TestLocal
        val trace = local.borrow()

        trace.frames(0) = Frame.derive
        trace.frames(1) = Frame.derive
        trace.index = 2

        local.release(trace)

        val recycled = local.borrow()
        assert(recycled.frames.take(2).forall(_ == null), "Frames were not cleared on release")
        assert(recycled.index == 0, "Index was not reset on release")
    }

    "sequential borrow/release should maintain pool integrity" in {
        val local               = new TestLocal
        var lastBorrowed: Trace = null

        for i <- 1 to TracePool.localCapacity * 3 do
            val trace = local.borrow()
            assert(trace != null, s"Got null trace on iteration $i")

            if lastBorrowed != null then
                local.release(lastBorrowed)
            lastBorrowed = trace
        end for
        if lastBorrowed != null then
            local.release(lastBorrowed)

        val finalTrace = local.borrow()
        assert(finalTrace != null, "Pool was corrupted after sequential operations")
    }

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
end TracePoolTest
