package kyo.net.internal.util

import kyo.*
import kyo.net.Test
import kyo.test.AllocationProbe

/** Allocation contract for the queue behind the poller's interest-change FIFO.
  *
  * The driver drains that FIFO to empty once per poll cycle, so the queue exists to keep that cycle off the allocator.
  * These pin what that costs: nothing at all for a burst the chunk holds, and a chunk rather than a node per command for
  * one that outgrows it. JVM-only, because the per-thread allocation counter is a HotSpot extension.
  */
class MpscLongQueueAllocationTest extends Test:

    "a burst the chunk holds allocates nothing" in {
        // The poller's burst is one interest change per fiber doing I/O in the cycle, which is what ChunkCapacity is sized
        // for. A cycle that fits leaves the producer walking the chunk as a ring, so it never reaches the allocator.
        val burst = 64
        val q     = new MpscLongQueue()
        AllocationProbe.assertBoundedPerOp(warmupIters = 64, measuredIters = 64, maxBytesPerOp = 0.0) {
            var i = 0
            while i < burst do
                q.offer(i.toLong)
                i += 1
            while q.poll() != MpscLongQueue.Empty do ()
        }
        succeed
    }

    "a burst past the chunk allocates a chunk, not a command" in {
        // Four chunks' worth with nothing drained until the end, so the queue links three fresh chunks per cycle. The bound
        // is per command offered: three chunks spread over the commands they carry measures about 6 bytes each, where the
        // node this queue allocated per command before cost around 40. The bound sits between the two, so a regression to
        // per-command allocation fails it while the headroom absorbs a different object header size.
        val burst = MpscLongQueue.ChunkCapacity * 4
        val q     = new MpscLongQueue()
        AllocationProbe.assertBoundedPerOp(warmupIters = 16, measuredIters = 16, maxBytesPerOp = burst * 12.0) {
            var i = 0
            while i < burst do
                q.offer(i.toLong)
                i += 1
            while q.poll() != MpscLongQueue.Empty do ()
        }
        succeed
    }

end MpscLongQueueAllocationTest
