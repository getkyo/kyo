package kyo.ffi

import kyo.ffi.internal.StructAbiCheck

/** Unit tests for [[kyo.ffi.internal.StructAbiCheck]], the runtime self-check each generated impl emits at companion init to detect struct
  * layout disagreements between the code generator's expectation and the platform's measured byte size.
  *
  * Unit-level because the generated-code paths always agree at runtime: both expected and actualSize are derived from the same Scala
  * case-class layout on all three platforms, so provoking a true JVM/Native/JS mismatch requires hand-editing generated output. The check's
  * contract (throw on disagreement, pass silently on agreement) is the testable surface.
  */
class StructAbiCheckTest extends Test:

    "verifyByteSize" - {
        "passes silently when expected == actual" in {
            StructAbiCheck.verifyByteSize("kyo.example.Bindings", "Packed", 16L, 16L)
            succeed
        }

        "throws FfiAbiMismatch when expected != actual" in {
            val ex = intercept[FfiAbiMismatch](
                StructAbiCheck.verifyByteSize("kyo.example.Bindings", "Packed", 16L, 9L)
            )
            assert(ex.traitFqn == "kyo.example.Bindings")
            assert(ex.structName == "Packed")
            assert(ex.expectedSize == 16L)
            assert(ex.actualSize == 9L)
        }

        "error message names the binding, struct, expected + actual sizes, and remediation hint" in {
            val ex = intercept[FfiAbiMismatch](
                StructAbiCheck.verifyByteSize("kyo.example.Bindings", "Packed", 16L, 9L)
            )
            val msg = ex.getMessage
            assert(msg.contains("kyo.example.Bindings"))
            assert(msg.contains("Packed"))
            assert(msg.contains("16"))
            assert(msg.contains("9"))
            assert(msg.contains("packedStructs"))
        }

        "FfiAbiMismatch is a subtype of FfiLoadError.AbiMismatch so a single `catch FfiLoadError` covers every load failure" in {
            val ex = intercept[FfiAbiMismatch](
                StructAbiCheck.verifyByteSize("kyo.example.Bindings", "Packed", 16L, 9L)
            )
            assert(ex.isInstanceOf[FfiLoadError.AbiMismatch])
            assert(ex.isInstanceOf[FfiLoadError])
        }
    }
end StructAbiCheckTest
