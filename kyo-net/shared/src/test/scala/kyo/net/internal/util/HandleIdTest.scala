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
    }

end HandleIdTest
