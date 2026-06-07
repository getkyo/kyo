package kyo

/** Tests for ErrorMode enum: identity, equality, and CanEqual derivation. */
class ErrorModeTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

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
