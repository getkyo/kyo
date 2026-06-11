package kyo.ffi

/** Cross-platform tests for [[Ffi.Guard]] retain semantics.
  *
  * Verifies that (a) `unsafeRetain` appends to the retained list, (b) closing the guard clears the list so callbacks are no longer
  * anchored, and (c) retention is a no-op after `close()` (matching the idempotency guarantees across all platforms).
  */
class GuardRetainTest extends Test:

    "unsafeRetain" - {
        "appends the ref to the retained list on an open guard" in {
            Ffi.Guard.use { g =>
                val before = g.retainedCount
                g.unsafeRetain(new Object)
                assert((g.retainedCount - before) == 1)
            }
        }

        "multiple retains stack up" in {
            Ffi.Guard.use { g =>
                val before = g.retainedCount
                g.unsafeRetain("a")
                g.unsafeRetain("b")
                g.unsafeRetain("c")
                assert((g.retainedCount - before) == 3)
            }
        }

        "close clears the retained list" in {
            val g = Ffi.Guard.open()
            g.unsafeRetain(new Object)
            g.unsafeRetain(new Object)
            assert(g.retainedCount >= 2)
            g.close()
            assert(g.retainedCount == 0)
        }

        "is a no-op after close, close remains idempotent and the list stays empty" in {
            val g = Ffi.Guard.open()
            g.unsafeRetain(new Object)
            g.close()
            assert(g.retainedCount == 0)
            // Retaining on a closed guard must not revive the list.
            g.unsafeRetain(new Object)
            assert(g.retainedCount == 0)
            // And close remains idempotent.
            g.close()
            assert(g.retainedCount == 0)
        }
    }
end GuardRetainTest
