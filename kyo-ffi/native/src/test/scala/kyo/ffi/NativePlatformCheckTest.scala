package kyo.ffi

import kyo.ffi.internal.NativeLoader

/** Validates the 32-bit-host fail-fast on Scala Native.
  *
  * The live CI matrix is 64-bit everywhere. We cross-compile for `x86_64-linux-gnu` / `aarch64-*` targets and cannot flip pointer width
  * mid-test. `NativeLoader.checkPlatform` therefore exposes a synthetic `isBit64` knob so tests cover both outcomes; the 64-bit code path
  * is also exercised live via `detectIs64Bit` (must report `true` on CI).
  */
class NativePlatformCheckTest extends Test:

    "detectIs64Bit returns true on the CI host" in {
        // `sizeof[Ptr[Byte]]` reports 8 bytes on every supported Native target. A `false` here means the detection logic broke, not that
        // CI is 32-bit.
        assert(NativeLoader.detectIs64Bit() == true)
    }

    "checkPlatform(true) is a no-op" in {
        NativeLoader.checkPlatform(true)
        succeed
    }

    "checkPlatform(false) throws FfiUnsupported" in {
        val ex = intercept[FfiUnsupported] {
            NativeLoader.checkPlatform(false)
        }
        assert(ex.isInstanceOf[RuntimeException])
    }

    "the rejection message names the prefix, 64-bit requirement, and sizeof(Ptr[Byte])" in {
        val ex = intercept[FfiUnsupported] {
            NativeLoader.checkPlatform(false)
        }
        val msg = ex.getMessage
        assert(msg != null)
        assert(msg.startsWith("[kyo-ffi]"))
        assert(msg.contains("64-bit"))
        assert(msg.contains("32-bit"))
        assert(msg.contains("not implemented"))
        // The detail slot surfaces the observed pointer size so an operator can see what the loader measured.
        assert(msg.contains("sizeof(Ptr[Byte])"))
    }
end NativePlatformCheckTest
