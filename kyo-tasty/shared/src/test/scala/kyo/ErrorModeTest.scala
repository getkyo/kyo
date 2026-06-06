package kyo

/** Tests for ErrorMode enum .
  *
  * Leaves: enum identity and CanEqual derivation.
  */
class ErrorModeTest extends Test:

    import AllowUnsafe.embrace.danger

    // Leaf id:2 -- ErrorMode enum replaces strict: Boolean
    // Given: Classpath.init signatures
    // When: overloads are exercised
    // Then: no overload takes `strict: Boolean`; mode-aware overload accepts ErrorMode
    "ErrorMode.SoftFail and ErrorMode.FailFast are distinct enum cases" in {
        assert(Tasty.ErrorMode.SoftFail != Tasty.ErrorMode.FailFast)
        assert(Tasty.ErrorMode.SoftFail == Tasty.ErrorMode.SoftFail)
        assert(Tasty.ErrorMode.FailFast == Tasty.ErrorMode.FailFast)
    }

    "ErrorMode derives CanEqual" in {
        // Compile-time check: CanEqual derived means == does not require import
        val a: Tasty.ErrorMode = Tasty.ErrorMode.SoftFail
        val b: Tasty.ErrorMode = Tasty.ErrorMode.FailFast
        assert(a != b)
    }

end ErrorModeTest
