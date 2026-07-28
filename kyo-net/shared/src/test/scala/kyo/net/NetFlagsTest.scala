package kyo.net

import kyo.*

/** Covers the flag-level contracts in `NetFlags.scala`.
  *
  * `ioPoolSize` is the transport's multiplexing width and, on io_uring, its ring submission-queue depth. A value below one would leave a
  * transport with nothing to distribute connections across, so it is clamped. The clamp is asserted against the production function rather
  * than a copy of the expression, since a copy would keep passing if the real validator were dropped.
  */
class NetFlagsTest extends Test:

    "ioPoolSize clamp" - {

        "forces a driver count below one up to one" in {
            assert(clampIoPoolSize(0) == 1, "a zero pool would leave a transport with no driver to serve on")
            assert(clampIoPoolSize(-1) == 1)
            assert(clampIoPoolSize(Int.MinValue) == 1)
            succeed
        }

        "leaves a usable count untouched" in {
            assert(clampIoPoolSize(1) == 1)
            assert(clampIoPoolSize(3) == 3)
            assert(clampIoPoolSize(64) == 64)
            assert(clampIoPoolSize(Int.MaxValue) == Int.MaxValue)
            succeed
        }

        "the resolved flag always satisfies the clamp" in {
            // Guards the wiring, not just the function: the default derives from the scheduler's carrier count, which floors to zero on a
            // machine or configuration with fewer than four carriers, and that path must be clamped too.
            assert(ioPoolSize() >= 1, s"the resolved pool size must be at least one, was ${ioPoolSize()}")
            succeed
        }
    }

end NetFlagsTest
