package kyo

class BrowserMemoryTest extends BrowserTest:

    override def timeout = 90.seconds

    // A plain array, not a typed one: a `Float64Array`'s backing store is outside V8's JS heap, so
    // `Runtime.getHeapUsage` does not see it.
    private val blobElements = 3_000_000

    // Well under the array's real footprint: the tests assert the allocation is visible, not its layout.
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
