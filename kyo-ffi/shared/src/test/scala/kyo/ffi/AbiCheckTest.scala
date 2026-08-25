package kyo.ffi

import kyo.ffi.internal.AbiCheck

/** `AbiCheck.verify` failure must name the binding whose generated impl is out of sync, not just print the version numbers. Without the
  * binding name the user can't tell which of N bindings to regenerate. The failure is now the typed
  * [[FfiLoadError.AbiMismatch]] carrier (matching `StructAbiCheck`), so a caller catches one `FfiLoadError` type
  * instead of a bare `IllegalStateException`.
  *
  * Cross-platform: the helper is shared, so the spec runs identically on JVM, Scala Native, and Scala.js.
  */
class AbiCheckTest extends Test:

    "matching ABI does not throw" in {
        // No exception → success. Matches the runtime ABI version, so this should be a no-op.
        AbiCheck.verify(AbiCheck.runtimeAbi, "kyo.test.OkBindings")
        succeed
    }

    "mismatched ABI throws AbiMismatch and the message names the binding" in {
        val badAbi = AbiCheck.runtimeAbi + 1
        val ex     = intercept[FfiLoadError.AbiMismatch](AbiCheck.verify(badAbi, "kyo.test.MyBindings"))
        val msg    = ex.getMessage
        assert(msg != null)
        assert(msg.contains("kyo.test.MyBindings"))
        assert(msg.contains(badAbi.toString))
        assert(msg.contains(AbiCheck.runtimeAbi.toString))
        // expected = the runtime ABI, actual = the (wrong) generated ABI.
        assert(ex.expected == AbiCheck.runtimeAbi.toString)
        assert(ex.actual == badAbi.toString)
    }

    "compareVersions orders numerically, component by component, padding the shorter" in {
        assert(AbiCheck.compareVersions("1.0.0", "1.0.0") == 0)
        assert(AbiCheck.compareVersions("1.0.0", "1.1.0") < 0)
        assert(AbiCheck.compareVersions("2.0.0", "1.9.9") > 0)
        // Numeric, not lexical: 10 > 2 even though "1.10" < "1.2" as strings.
        assert(AbiCheck.compareVersions("1.10.0", "1.2.0") > 0)
        // A trailing zero component and a missing one are equal (padding).
        assert(AbiCheck.compareVersions("1.0", "1.0.0") == 0)
        // A pre-release suffix does not derail the numeric comparison.
        assert(AbiCheck.compareVersions("1.0.0-RC1", "1.0.0") == 0)
        assert(AbiCheck.compareVersions("0.20.0", "0.21.0") < 0)
    }

    "verifyRuntimeFloor is a no-op when the runtime version cannot be determined" in {
        // Under test the kyo-ffi runtime version is not resolvable (running from a classes directory), so the
        // floor comparison is skipped rather than guessed: no throw even for an impossibly high floor.
        AbiCheck.verifyRuntimeFloor("kyo.test.MyBindings", "999.0.0")
        succeed
    }
end AbiCheckTest
