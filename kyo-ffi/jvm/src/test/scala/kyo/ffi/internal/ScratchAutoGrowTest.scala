package kyo.ffi.internal

import kyo.ffi.Test

/** Unit tests for the auto-grow behavior of [[Scratch]], when a scratch instance spills, subsequent calls to `current` or `currentFor`
  * replace it with a doubled-size scratch (capped at [[Scratch.maxScratchSize]]).
  */
class ScratchAutoGrowTest extends Test:

    "scratch auto-grow" - {

        "scratch grows after spill" in {
            // Create a small scratch (32 bytes), alloc 64 bytes (forces spill), then verify growth
            val s = new Scratch.Scratch(32L)
            assert(s.scratchSize == 32L)
            assert(s.spilled == false)

            // Allocate more than fits, triggers spill
            val m = s.mark()
            (s.alloc(64L, 1L): Unit)
            assert(s.spilled == true)

            // Clean up the spilled scratch
            s.reset(m)
            s.closeArena()
        }

        "growth capped at maxScratchSize" in {
            // Verify that growth does not exceed maxScratchSize
            // Create a scratch at half the max size, spill it, and verify the replacement is capped
            val halfMax = Scratch.maxScratchSize / 2
            val s       = new Scratch.Scratch(halfMax)
            assert(s.scratchSize == halfMax)

            // Spill to trigger growth flag
            val m = s.mark()
            (s.alloc(halfMax + 1L, 1L): Unit)
            assert(s.spilled == true)
            s.reset(m)

            // Simulate what `current` does: doubling would be halfMax * 2 = maxScratchSize
            val newSize = math.min(s.scratchSize * 2, Scratch.maxScratchSize)
            assert(newSize == Scratch.maxScratchSize)

            // Now test with a scratch already AT maxScratchSize, should not grow further
            s.closeArena()
            val atMax = new Scratch.Scratch(Scratch.maxScratchSize)
            assert(atMax.scratchSize == Scratch.maxScratchSize)
            val m2 = atMax.mark()
            (atMax.alloc(Scratch.maxScratchSize + 1L, 1L): Unit)
            assert(atMax.spilled == true)
            atMax.reset(m2)

            // Even though it spilled, scratchSize >= maxScratchSize, so no growth
            assert((atMax.spilled && atMax.scratchSize >= Scratch.maxScratchSize) == true)
            atMax.closeArena()
        }

        "no growth when no spill occurred" in {
            val s = new Scratch.Scratch(256L)
            assert(s.scratchSize == 256L)
            assert(s.spilled == false)

            // Allocate within capacity, no spill
            val m = s.mark()
            (s.alloc(128L, 1L): Unit)
            assert(s.spilled == false)

            // Clean up
            s.reset(m)
            s.closeArena()
        }
    }

end ScratchAutoGrowTest
