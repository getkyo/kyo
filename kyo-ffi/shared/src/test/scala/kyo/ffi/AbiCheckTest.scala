package kyo.ffi

import kyo.ffi.internal.AbiCheck

/** `AbiCheck.verify` failure must name the binding whose generated impl is out of sync, not just print the version numbers. Without the
  * binding name the user can't tell which of N bindings to regenerate.
  *
  * Cross-platform: the helper is shared, so the spec runs identically on JVM, Scala Native, and Scala.js.
  */
class AbiCheckTest extends Test:

    "matching ABI does not throw" in {
        // No exception → success. Matches the runtime ABI version, so this should be a no-op.
        AbiCheck.verify(AbiCheck.runtimeAbi, "kyo.test.OkBindings")
        succeed
    }

    "mismatched ABI throws and the message names the binding" in {
        val badAbi = AbiCheck.runtimeAbi + 1
        val ex     = intercept[IllegalStateException](AbiCheck.verify(badAbi, "kyo.test.MyBindings"))
        val msg    = ex.getMessage
        assert(msg != null)
        assert(msg.contains("kyo.test.MyBindings"))
        assert(msg.contains(badAbi.toString))
        assert(msg.contains(AbiCheck.runtimeAbi.toString))
    }
end AbiCheckTest
