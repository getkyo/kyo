package kyo.net.internal.util

import kyo.*
import kyo.net.Test

class HandleIdTest extends Test:

    "HandleId" - {

        "pack-unpack-round-trips" in {
            val id = HandleId.of(fd = 42, generation = 7)
            assert(id.fd == 42)
            assert(id.generation == 7)
            val repacked = HandleId.fromPacked(id.packed)
            assert(repacked.packed == id.packed)
            succeed
        }

        "recycled-fd-ids-differ" in {
            val a = HandleId.next(42)
            val b = HandleId.next(42)
            assert(a.packed != b.packed)
            assert(a.generation != b.generation)
            succeed
        }

        "next for different fds are distinct" in {
            val a = HandleId.next(3)
            val b = HandleId.next(4)
            assert(a.packed != b.packed)
            succeed
        }

        "next fd round-trips" in {
            val id = HandleId.next(99)
            assert(id.fd == 99, s"fd round-trip failed: expected 99, got ${id.fd}")
            succeed
        }

        "generation increases monotonically across sequential next calls" in {
            val a = HandleId.next(0)
            val b = HandleId.next(0)
            assert(b.packed > a.packed, "second HandleId must have a higher packed value than the first")
            succeed
        }

        "fromPacked is the inverse of packed" in {
            val id      = HandleId.next(11)
            val rebuilt = HandleId.fromPacked(id.packed)
            assert(rebuilt.fd == id.fd)
            assert(rebuilt.generation == id.generation)
            assert(rebuilt.packed == id.packed)
            succeed
        }

        "fd=0 is representable" in {
            val id = HandleId.next(0)
            assert(id.fd == 0)
            succeed
        }

        "max fd (Int.MaxValue) round-trips without sign corruption" in {
            val maxFd = Int.MaxValue
            val id    = HandleId.of(maxFd, 0)
            assert(id.fd == maxFd, s"max fd round-trip failed: expected $maxFd, got ${id.fd}")
            succeed
        }
    }

end HandleIdTest
