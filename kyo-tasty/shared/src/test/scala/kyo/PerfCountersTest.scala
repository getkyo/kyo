package kyo

import kyo.internal.tasty.query.PerfCounters

class PerfCountersTest extends Test:

    // Restore PerfCounters state between tests to avoid cross-test pollution.
    private def withClean[A, S](body: A < (Async & S)): A < (Async & S) =
        Sync.defer(PerfCounters.reset()).andThen(body).map { result =>
            val _ = PerfCounters.reset()
            result
        }

    // Test 1 (B13): snapshot captures consistent ordering under concurrent increments.
    // Writer increments jarOpenCount then entryReadCount (always in that order) in a tight loop.
    // Reader collects 100 snapshots concurrently.
    // A coherent snapshot satisfies entryReadCount >= jarOpenCount - 1 because jarOpenCount is
    // always incremented first; entryReadCount can lag by at most 1 in a window between the two.
    "PerfCountersTest B13: snapshot captures coherent ordering under concurrent increments" in run {
        withClean {
            Async.timeout(5.seconds) {
                for
                    writerFiber <- Fiber.initUnscoped(
                        Sync.defer {
                            var i = 0
                            while i < 200 do
                                PerfCounters.jarOpenCount.incrementAndGet()
                                PerfCounters.entryReadCount.incrementAndGet()
                                i += 1
                            end while
                        }
                    )
                    readerFiber <- Fiber.initUnscoped(
                        Sync.defer {
                            val buf = new scala.collection.mutable.ArrayBuffer[PerfCounters.Snapshot](100)
                            var i   = 0
                            while i < 100 do
                                buf += PerfCounters.snapshot()
                                i += 1
                            end while
                            buf.toSeq
                        }
                    )
                    _         <- writerFiber.get
                    snapshots <- readerFiber.get
                    incoherent = snapshots.filter(s => s.entryReadCount < s.jarOpenCount - 1)
                yield assert(
                    incoherent.isEmpty,
                    s"Found ${incoherent.size} incoherent snapshots (entryReadCount < jarOpenCount - 1): ${incoherent.take(3)}"
                )
                end for
            }
        }
    }

    // Test 2 (B13): reset() returns the pre-reset snapshot and zeroes all counters.
    "PerfCountersTest B13: reset returns pre-reset snapshot and zeroes counters" in run {
        withClean {
            Sync.defer {
                PerfCounters.jarOpenCount.set(42)
                PerfCounters.entryReadCount.set(7)
                PerfCounters.bytesReadTotal.set(1024L)
                PerfCounters.jarConstructTimeNs.set(500L)
                PerfCounters.jarReadTimeNs.set(600L)
                PerfCounters.tastyHeaderTimeNs.set(10L)
                PerfCounters.nameUnpicklerTimeNs.set(20L)
                PerfCounters.sectionIndexTimeNs.set(30L)
                PerfCounters.attributeUnpicklerTimeNs.set(40L)
                PerfCounters.astPass1TimeNs.set(50L)
                PerfCounters.commentsUnpicklerTimeNs.set(60L)
                PerfCounters.positionsUnpicklerTimeNs.set(70L)

                val s = PerfCounters.reset()

                assert(s.jarOpenCount == 42, s"jarOpenCount: expected 42, got ${s.jarOpenCount}")
                assert(s.entryReadCount == 7, s"entryReadCount: expected 7, got ${s.entryReadCount}")
                assert(s.bytesReadTotal == 1024L, s"bytesReadTotal: expected 1024, got ${s.bytesReadTotal}")
                assert(s.jarConstructTimeNs == 500L)
                assert(s.jarReadTimeNs == 600L)
                assert(s.tastyHeaderTimeNs == 10L)
                assert(s.nameUnpicklerTimeNs == 20L)
                assert(s.sectionIndexTimeNs == 30L)
                assert(s.attributeUnpicklerTimeNs == 40L)
                assert(s.astPass1TimeNs == 50L)
                assert(s.commentsUnpicklerTimeNs == 60L)
                assert(s.positionsUnpicklerTimeNs == 70L)

                assert(PerfCounters.jarOpenCount.get() == 0, "jarOpenCount not zeroed after reset")
                assert(PerfCounters.entryReadCount.get() == 0, "entryReadCount not zeroed after reset")
                assert(PerfCounters.bytesReadTotal.get() == 0L, "bytesReadTotal not zeroed after reset")
            }
        }
    }

    // Test 3 (T2): incrementing jarOpenCount and entryReadCount grows the snapshot counters monotonically.
    // Given: fresh PerfCounters (via withClean); jarOpenCount incremented 5 times; entryReadCount incremented 3 times.
    // When: snapshot() is called.
    // Then: snapshot.jarOpenCount == 5 and snapshot.entryReadCount == 3.
    // Pins: T2.
    "PerfCountersTest T2: incJarOpen 5 times and incEntryRead 3 times produces matching snapshot" in run {
        withClean {
            Sync.defer {
                var i = 0
                while i < 5 do
                    PerfCounters.jarOpenCount.incrementAndGet()
                    i += 1
                end while
                var j = 0
                while j < 3 do
                    PerfCounters.entryReadCount.incrementAndGet()
                    j += 1
                end while
                val s = PerfCounters.snapshot()
                assert(s.jarOpenCount == 5, s"Expected jarOpenCount == 5, got ${s.jarOpenCount}")
                assert(s.entryReadCount == 3, s"Expected entryReadCount == 3, got ${s.entryReadCount}")
            }
        }
    }

end PerfCountersTest
