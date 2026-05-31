package kyo

import kyo.internal.tasty.query.ClasspathRef

/** Regression tests for the ClasspathRef dedup mechanism.
  *
  * plan: phase-02 update; Symbol.home (ClasspathRef) is removed from Symbol in Phase 02. The original Test 1 (multiple symbols sharing a
  * ClasspathRef) is no longer applicable since Symbol no longer carries a ClasspathRef. Test 2 (HashSet semantics) remains valid.
  */
class ClasspathRefDedupTest extends Test:

    /** Regression test 2: the "previously seen" check returns true exactly once per ClasspathRef.init().
      *
      * Directly exercises `java.util.HashSet[ClasspathRef].add` semantics.
      */
    "HashSet dedup: add returns true exactly once per ClasspathRef instance" in {
        import AllowUnsafe.embrace.danger
        val ref1 = ClasspathRef.init()
        val ref2 = ClasspathRef.init()
        val seen = new java.util.HashSet[ClasspathRef]()

        val firstAdd = seen.add(ref1)
        assert(firstAdd, "Expected first add of ref1 to return true")

        val secondAdd = seen.add(ref1)
        assert(!secondAdd, "Expected second add of ref1 to return false (duplicate)")

        val ref2Add = seen.add(ref2)
        assert(ref2Add, "Expected first add of ref2 to return true")

        assert(seen.size() == 2, s"Expected seen.size() == 2 but got ${seen.size()}")
        succeed
    }

end ClasspathRefDedupTest
