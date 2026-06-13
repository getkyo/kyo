package kyo.ffi

import kyo.ffi.internal.NativeLoader

/** Validates the 32-bit-host fail-fast on Scala.js (Node).
  *
  * Node identifies architecture via `process.arch`. 32-bit values include `ia32`, `x32`, `arm`, `mips`, `mipsel`, `ppc`, `s390`. 64-bit
  * values are `x64`, `arm64`, `riscv64`, `ppc64`, `ppc64le`, `s390x`, `loong64`. `NativeLoader.checkPlatform(arch: String)` is exposed so
  * tests can cover every name without spawning Node builds for each arch.
  */
class JsPlatformCheckTest extends Test:

    "checkPlatform accepts every 64-bit arch name" in {
        // These are the arch names the JS ecosystem produces on 64-bit hosts. All must pass through without throwing.
        val sixtyFour = Seq("x64", "arm64", "riscv64", "ppc64", "ppc64le", "s390x", "loong64")
        for arch <- sixtyFour do
            // checkPlatform must pass through without throwing on a 64-bit arch name.
            NativeLoader.checkPlatform(arch)
        end for
        succeed
    }

    "checkPlatform rejects every known 32-bit arch name" in {
        // Every 32-bit arch name Node has ever produced. All must throw FfiUnsupported.
        val thirtyTwo = Seq("ia32", "x32", "arm", "mips", "mipsel", "ppc", "s390")
        for arch <- thirtyTwo do
            val ex = intercept[FfiUnsupported] {
                NativeLoader.checkPlatform(arch)
            }
            assert(ex.getMessage.contains(s"process.arch = $arch"))
        end for
    }

    "checkPlatform accepts empty / unknown arch strings (best-effort)" in {
        // Undetectable arch (e.g. missing `process.arch`, a runtime without Node's process shim) is treated as best-effort pass. We'd
        // rather hit a downstream koffi error on a genuinely unsupported runtime than false-positive on a future 64-bit name we haven't
        // enumerated.
        NativeLoader.checkPlatform("")
        NativeLoader.checkPlatform("some-future-arch-name")
        succeed
    }

    "the rejection exception hierarchy and message surface" in {
        val ex = intercept[FfiUnsupported] {
            NativeLoader.checkPlatform("ia32")
        }
        assert(ex.isInstanceOf[RuntimeException])
        val msg = ex.getMessage
        assert(msg != null)
        assert(msg.startsWith("[kyo-ffi]"))
        assert(msg.contains("64-bit"))
        assert(msg.contains("32-bit"))
        assert(msg.contains("not implemented"))
    }
end JsPlatformCheckTest
