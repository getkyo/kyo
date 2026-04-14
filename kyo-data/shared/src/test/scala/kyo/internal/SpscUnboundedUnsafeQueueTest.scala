package kyo.internal

import kyo.*
import kyo.AllowUnsafe.embrace.danger

class SpscUnboundedUnsafeQueueTest extends UnsafeQueueBaseTest:
    def queueName                               = "SpscUnboundedUnsafeQueue"
    def isBounded                               = false
    def nProducers                              = 1
    def nConsumers                              = 1
    def testSizes                               = Seq(8, 16, 64)
    def makeQueue[A](size: Int): UnsafeQueue[A] = new SpscUnboundedUnsafeQueue[A](size)

    "SpscUnboundedUnsafeQueue-specific" - {
        "chunkTransitionExact" in {
            for cs <- Seq(8, 16) do
                val q                  = new SpscUnboundedUnsafeQueue[Int](cs)
                val effectiveChunkSize = UnsafeQueue.roundToPowerOfTwo(Math.max(8, cs))
                // Offer exactly chunkSize + 1 to force one chunk transition
                for i <- 0 to effectiveChunkSize do q.offer(i)
                for i <- 0 to effectiveChunkSize do
                    assert(q.poll() == Maybe(i), s"chunkSize=$cs, i=$i")
        }

        "manyChunkTransitions" in {
            val q = new SpscUnboundedUnsafeQueue[Int](8)
            val n = 400
            for i <- 0 until n do q.offer(i)
            for i <- 0 until n do
                assert(q.poll() == Maybe(i), s"i=$i")
        }

        "jumpSentinelNotVisible" in {
            val q                  = new SpscUnboundedUnsafeQueue[Int](8)
            val effectiveChunkSize = UnsafeQueue.roundToPowerOfTwo(8)
            // Fill past chunk boundary
            for i <- 0 until effectiveChunkSize * 3 do q.offer(i)
            for i <- 0 until effectiveChunkSize * 3 do
                val r = q.poll()
                assert(r.isDefined, s"i=$i")
                assert(r.get == i)
            end for
        }

        "circularReuseVerification" in {
            // Fill and drain multiple rounds to verify circular chunk reuse works
            // and JUMP sentinels never leak into polled values
            for cs <- Seq(8, 16) do
                val q = new SpscUnboundedUnsafeQueue[Int](cs)
                for round <- 0 until 20 do
                    val n = cs * 2 // fill across chunk boundary
                    for i <- 0 until n do q.offer(round * n + i)
                    for i <- 0 until n do
                        val r = q.poll()
                        assert(r == Maybe(round * n + i), s"cs=$cs, round=$round, i=$i")
                    assert(q.poll().isEmpty, s"cs=$cs, round=$round: not empty after drain")
                end for
        }

        "fullChunkTransitionAtEveryBoundary" in {
            // Fill exactly at chunk boundaries to exercise the look-ahead cold path
            for cs <- Seq(8, 16, 32) do
                val q                  = new SpscUnboundedUnsafeQueue[Int](cs)
                val effectiveChunkSize = UnsafeQueue.roundToPowerOfTwo(Math.max(8, cs))
                // Fill to exact boundaries: 1x, 2x, 3x, 4x chunk size
                for mult <- 1 to 4 do
                    val n = effectiveChunkSize * mult
                    for i <- 0 until n do q.offer(i)
                    for i <- 0 until n do
                        assert(q.poll() == Maybe(i), s"cs=$cs, mult=$mult, i=$i")
                    assert(q.poll().isEmpty)
                end for
        }
    }
end SpscUnboundedUnsafeQueueTest
