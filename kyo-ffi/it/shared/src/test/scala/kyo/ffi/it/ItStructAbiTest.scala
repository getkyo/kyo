package kyo.ffi.it

import kyo.ffi.Ffi
import kyo.ffi.FfiLoadError
import kyo.ffi.internal.StructAbiCheck

/** IT spec for the struct-ABI self-check.
  *
  * Two kinds of assertion here:
  *
  *   1. End-to-end: every generated impl that uses a struct runs `StructAbiCheck.verifyByteSize(trait, struct, expected, actual)` at its
  *      companion's class-init. Since `ItStructsBindings` exercises structs on every platform and loads successfully in `ItStructsSpec`,
  *      the check is trivially verified, a divergence would have broken that spec. We assert a concrete side effect here:
  *      `Ffi.load[ItStructsBindings]` returns a non-null impl, which transitively requires every struct-ABI check to have passed silently.
  *   2. Contract: the check surfaces `FfiLoadError.AbiMismatch` with the expected shape when fed a synthetic divergence, the "what generated impls
  *      look like when a user misconfigures `packedStructs`" scenario. Synthetic because every emitter path derives both sides from the
  *      same Scala case-class layout; a real cross-platform disagreement requires hand-editing generated output.
  */
class ItStructAbiTest extends ItTestBase:

    "end-to-end struct ABI self-check" - {
        "ItStructsBindings impl loads successfully, transitively asserting StructAbiCheck passes for every struct" in {
            // Class-init runs `StructAbiCheck.verifyByteSize` for `Circle`, `Center` (nested), and `Packed`. Loading the impl without
            // throwing means each measured platform byte size matched the generator's expected size.
            val b = Ffi.load[ItStructsBindings]
            assert(b != null)
        }
    }

    "synthetic FfiLoadError.AbiMismatch" - {
        "a 16-vs-9 size divergence (forgot to pack) surfaces with a remediation hint" in {
            val ex = intercept[FfiLoadError.AbiMismatch](
                StructAbiCheck.verifyByteSize("kyo.ffi.it.ItStructsBindings", "Packed", 16L, 9L)
            )
            assert(ex.expected == "16")
            assert(ex.actual == "9")
            assert(ex.getMessage.contains("kyo.ffi.it.ItStructsBindings"))
            assert(ex.getMessage.contains("Packed"))
            assert(ex.getMessage.contains("Ffi.Config.packedStructs"))
        }

        "a 9-vs-16 size divergence (over-packed) surfaces with a remediation hint" in {
            // Reverse direction, user listed a struct in `packedStructs` but the C declaration does NOT use `#pragma pack(1)`.
            val ex = intercept[FfiLoadError.AbiMismatch](
                StructAbiCheck.verifyByteSize("kyo.ffi.it.ItStructsBindings", "Packed", 9L, 16L)
            )
            assert(ex.expected == "9")
            assert(ex.actual == "16")
        }
    }
end ItStructAbiTest
