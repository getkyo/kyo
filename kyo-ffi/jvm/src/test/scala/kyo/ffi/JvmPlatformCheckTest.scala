package kyo.ffi

import kyo.ffi.internal.NativeLoader

/** Validates the 32-bit-host fail-fast.
  *
  * kyo-ffi's codegen hardcodes 64-bit integer + pointer widths. Rather than silently misalign offsets on 32-bit hosts, `NativeLoader`
  * rejects them with [[FfiLoadError.Unsupported]] at first load. The live CI matrix is 64-bit everywhere, so we cannot flip the real word size
  * mid-test, instead the `checkPlatform` helper is exposed so tests can pass a synthetic `isBit64` flag. `NativeLoader.detectIs64Bit` is
  * exercised on the live JVM and must report `true`.
  */
class JvmPlatformCheckTest extends Test:

    "detectIs64Bit returns true on the CI JVM" in {
        // CI matrix is 64-bit everywhere, Panama's ADDRESS layout reports 8 bytes. A `false` here means the detection logic is broken,
        // not that the CI is 32-bit; we never want to see that outcome.
        assert(NativeLoader.detectIs64Bit() == true)
    }

    "checkPlatform(true) is a no-op" in {
        // Happy path: supplied as true → no throw. This matches the branch the live CI takes at first `load` call.
        NativeLoader.checkPlatform(true)
        succeed
    }

    "checkPlatform(false) throws FfiLoadError.Unsupported" in {
        // Simulated 32-bit host, the helper path tests would take on a real 32-bit JVM at first `load` call.
        val ex = intercept[FfiLoadError.Unsupported] {
            NativeLoader.checkPlatform(false)
        }
        assert(ex.isInstanceOf[RuntimeException])
    }

    "the rejection message names the prefix, the 64-bit requirement, and an external reference" in {
        val ex = intercept[FfiLoadError.Unsupported] {
            NativeLoader.checkPlatform(false)
        }
        val msg = ex.getMessage
        assert(msg != null)
        assert(msg.startsWith("[kyo-ffi]"))
        assert(msg.contains("64-bit"))
        assert(msg.contains("32-bit"))
        assert(msg.contains("not implemented"))
    }

    "the rejection message includes the data model detail on this host" in {
        // On Hotspot-derived JVMs `sun.arch.data.model` is always set. The check surface routes the property value through the message so
        // an operator can confirm the detection triggered on the right signal.
        val dm = sys.props.get("sun.arch.data.model").getOrElse("unknown")
        val ex = intercept[FfiLoadError.Unsupported] {
            NativeLoader.checkPlatform(false)
        }
        assert(ex.getMessage.contains(s"data model: $dm"))
    }
end JvmPlatformCheckTest
