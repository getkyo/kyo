package kyo

class BrowserMemoryTest extends BrowserTest:

    override def timeout = 90.seconds

    /** A plain JS array, NOT a typed array: a `Float64Array`'s backing store is external to V8's JS heap, so
      * `Runtime.getHeapUsage` does not see it and it cannot probe these calls. An ordinary array's element store is on
      * the heap, so allocating one is observable here.
      *
      * Three million elements is large enough that its retention dwarfs the allocation noise of the CDP round-trips
      * around it, so a move of this size can only be the array itself.
      */
    private val blobElements = 3_000_000

    /** The floor a [[blobElements]] allocation must clear. Well under the array's real footprint (V8 stores these as
      * small integers, so it costs several times this), because the leaves assert that the allocation is VISIBLE, not
      * that V8 lays it out any particular way.
      */
    private val blobFloor = 8L * 1024 * 1024

    "heapUsage reports the page's occupancy" in {
        withBrowser {
            onPage("<html><body>heap-usage</body></html>") {
                Browser.heapUsage.map { heap =>
                    assert(heap.used > 0, s"expected a non-empty heap but used=${heap.used}")
                    assert(
                        heap.used <= heap.total,
                        s"used (${heap.used}) cannot exceed the heap V8 has reserved (${heap.total})"
                    )
                }
            }
        }
    }

    "heapUsage tracks what the page allocates" in {
        withBrowser {
            onPage("<html><body>heap-grows</body></html>") {
                for
                    before <- Browser.heapUsage
                    _      <- Browser.evalDiscard(s"window.__blob = new Array($blobElements).fill(1)")
                    after  <- Browser.heapUsage
                yield assert(
                    after.used - before.used > blobFloor,
                    s"a $blobElements-element allocation moved the reported heap by only " +
                        s"${(after.used - before.used) / 1048576} MB"
                )
            }
        }
    }

    // The pairing that makes a retention measurement meaningful: after the only reference is dropped, a forced
    // collection must actually reclaim the array, so a later reading reflects what the page still holds rather than
    // what it has merely stopped using.
    "collectGarbage reclaims what the page released" in {
        withBrowser {
            onPage("<html><body>collect-garbage</body></html>") {
                for
                    _        <- Browser.evalDiscard(s"window.__blob = new Array($blobElements).fill(1)")
                    held     <- Browser.collectGarbage.andThen(Browser.heapUsage)
                    _        <- Browser.evalDiscard("window.__blob = null")
                    released <- Browser.collectGarbage.andThen(Browser.heapUsage)
                yield assert(
                    held.used - released.used > blobFloor,
                    s"dropping the only reference to a $blobElements-element array freed only " +
                        s"${(held.used - released.used) / 1048576} MB across a forced collection"
                )
            }
        }
    }

end BrowserMemoryTest
