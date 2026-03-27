package kyo.internal

import kyo.*
import kyo.AllowUnsafe.embrace.danger

class SpscUnsafeQueueTest extends UnsafeQueueBaseTest:
    def queueName                               = "SpscUnsafeQueue"
    def isBounded                               = true
    def nProducers                              = 1
    def nConsumers                              = 1
    def testSizes                               = Seq(4, 8, 16, 128)
    def makeQueue[A](size: Int): UnsafeQueue[A] = new SpscUnsafeQueue[A](size)

    "SpscUnsafeQueue-specific" - {
        "minCapacityIsFour" in {
            for n <- Seq(1, 2, 3) do
                val q = new SpscUnsafeQueue[Int](n)
                assert(q.capacity >= 4)
        }

        "lookAheadHotPath" in {
            val q = new SpscUnsafeQueue[Int](16)
            // Fill to half, poll all, repeat — exercises lookAhead
            for _ <- 0 until 10 do
                for i <- 0 until 8 do q.offer(i)
                for _ <- 0 until 8 do q.poll()
        }

        "producerConsumerOnSameThread" in {
            val q = new SpscUnsafeQueue[Int](8)
            for i <- 0 until 100000 do
                q.offer(i)
                assert(q.poll() == Maybe(i))
        }
    }
end SpscUnsafeQueueTest
