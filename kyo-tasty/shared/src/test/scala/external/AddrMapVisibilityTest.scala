package external

/** Negative-compilation test for INV-011: Symbol.TastyOrigin.addrMap is not accessible outside the kyo package.
  *
  * This file lives in package external (outside kyo) so that assertDoesNotCompile verifies the private[kyo] restriction.
  */
class AddrMapVisibilityTest extends kyo.Test:

    "addrMap is inaccessible from outside the kyo package" in {
        // Attempt to call addrMap on a TastyOrigin instance from package external.
        // This must fail to compile because addrMap is private[kyo].
        assertDoesNotCompile("""
            val origin = kyo.Tasty.Symbol.TastyOrigin.empty
            origin.addrMap
        """)
        succeed
    }

end AddrMapVisibilityTest
