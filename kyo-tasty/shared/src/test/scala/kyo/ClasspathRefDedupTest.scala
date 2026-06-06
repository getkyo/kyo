package kyo

/** ClasspathRef and the dedup mechanism were deleted. This test class is retained as a placeholder.
  *
  * The original test verified HashSet semantics on ClasspathRef instances. ClasspathRef was a write-once forward-reference slot for
  * Tasty.Classpath that is no longer needed since Symbol no longer carries a ClasspathRef and the entire ClasspathRef type was
  * deleted in. The equivalent behavior is covered by ClasspathIndexImmutabilityTest and ClasspathPureAccessorTest.
  */
class ClasspathRefDedupTest extends Test:

    // ClasspathRef deleted; original dedup tests are no longer applicable.
    // The immutability properties are verified by ClasspathIndexImmutabilityTest.
    "ClasspathRef dedup test replaced by ClasspathIndexImmutabilityTest" in {
        succeed
    }

end ClasspathRefDedupTest
