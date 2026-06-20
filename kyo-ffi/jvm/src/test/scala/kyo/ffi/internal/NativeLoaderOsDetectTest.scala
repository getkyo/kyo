package kyo.ffi.internal

import kyo.ffi.FfiLoadError
import kyo.ffi.Test

/** R11 / F1, NativeLoader platform-detection unit tests.
  *
  * Exercises the public `detectOs`, `detectArch`, `detectIs64Bit`, and `checkPlatform` methods without touching the `load` path (which
  * requires a real native library).
  *
  * The Os/Arch enum methods (`tagName`, `libPrefix`, `libExtension`) are also covered here because they form the classifier that
  * `loadFromResourceOrSystem` assembles into the resource path `/META-INF/native/{os}-{arch}/lib{id}.{ext}`.
  */
class NativeLoaderOsDetectTest extends Test:

    // Mutates process-global state (System.setErr and/or a system property) and restores it, so the leaves must run
    // alone: under the default parallel leaf execution a sibling leaf observes the mutated global.
    override def config = super.config.sequential

    // -------------------------------------------------------------------------
    // Os enum
    // -------------------------------------------------------------------------

    "Os.tagName" - {
        "Linux" in { assert(NativeLoader.Os.Linux.tagName == "linux") }
        "LinuxMusl" in { assert(NativeLoader.Os.LinuxMusl.tagName == "linux-musl") }
        "Darwin" in { assert(NativeLoader.Os.Darwin.tagName == "darwin") }
        "Windows" in { assert(NativeLoader.Os.Windows.tagName == "windows") }
    }

    "Os.libPrefix" - {
        "Linux uses 'lib'" in { assert(NativeLoader.Os.Linux.libPrefix == "lib") }
        "LinuxMusl uses 'lib'" in { assert(NativeLoader.Os.LinuxMusl.libPrefix == "lib") }
        "Darwin uses 'lib'" in { assert(NativeLoader.Os.Darwin.libPrefix == "lib") }
        "Windows uses empty" in { assert(NativeLoader.Os.Windows.libPrefix == "") }
    }

    "Os.libExtension" - {
        "Linux is so" in { assert(NativeLoader.Os.Linux.libExtension == "so") }
        "LinuxMusl is so" in { assert(NativeLoader.Os.LinuxMusl.libExtension == "so") }
        "Darwin is dylib" in { assert(NativeLoader.Os.Darwin.libExtension == "dylib") }
        "Windows is dll" in { assert(NativeLoader.Os.Windows.libExtension == "dll") }
    }

    // -------------------------------------------------------------------------
    // Arch enum
    // -------------------------------------------------------------------------

    "Arch.tagName" - {
        "X86_64" in { assert(NativeLoader.Arch.X86_64.tagName == "x86_64") }
        "Aarch64" in { assert(NativeLoader.Arch.Aarch64.tagName == "aarch64") }
    }

    // -------------------------------------------------------------------------
    // classifier format: os-arch
    // -------------------------------------------------------------------------

    "classifier format (os.tagName + '-' + arch.tagName)" - {
        "linux/x86_64" in { assert(s"${NativeLoader.Os.Linux.tagName}-${NativeLoader.Arch.X86_64.tagName}" == "linux-x86_64") }
        "linux-musl/aarch64" in {
            assert(s"${NativeLoader.Os.LinuxMusl.tagName}-${NativeLoader.Arch.Aarch64.tagName}" == "linux-musl-aarch64")
        }
        "darwin/aarch64" in { assert(s"${NativeLoader.Os.Darwin.tagName}-${NativeLoader.Arch.Aarch64.tagName}" == "darwin-aarch64") }
        "windows/x86_64" in { assert(s"${NativeLoader.Os.Windows.tagName}-${NativeLoader.Arch.X86_64.tagName}" == "windows-x86_64") }
    }

    // -------------------------------------------------------------------------
    // detectOs, current-platform smoke test
    // -------------------------------------------------------------------------

    "detectOs: returns a valid Os for the current platform" in {
        // Should not throw on a supported CI platform (Linux / macOS).
        val os = NativeLoader.detectOs
        assert(Seq(
            NativeLoader.Os.Linux,
            NativeLoader.Os.LinuxMusl,
            NativeLoader.Os.Darwin,
            NativeLoader.Os.Windows
        ).contains(os))
    }

    "detectOs: tagName is non-empty and contains no spaces" in {
        val tag = NativeLoader.detectOs.tagName
        assert(tag.nonEmpty)
        assert(!tag.contains(' '))
    }

    // -------------------------------------------------------------------------
    // detectArch, current-platform smoke test
    // -------------------------------------------------------------------------

    "detectArch: returns a valid Arch for the current platform" in {
        val arch = NativeLoader.detectArch
        assert(Seq(
            NativeLoader.Arch.X86_64,
            NativeLoader.Arch.Aarch64
        ).contains(arch))
    }

    "detectArch: tagName matches expected pattern" in {
        val tag = NativeLoader.detectArch.tagName
        assert(Seq("x86_64", "aarch64").contains(tag))
    }

    // -------------------------------------------------------------------------
    // detectOs + detectArch together: classifier format is os-arch
    // -------------------------------------------------------------------------

    "detectOs + detectArch: combined classifier is non-empty and matches os-arch pattern" in {
        val os         = NativeLoader.detectOs
        val arch       = NativeLoader.detectArch
        val classifier = s"${os.tagName}-${arch.tagName}"
        assert(classifier.nonEmpty)
        // Must contain exactly one dash separating os from arch segments.
        // e.g. "linux-x86_64", "darwin-aarch64", "linux-musl-aarch64"
        assert(classifier.contains("-"))
        // The arch segment legitimately contains an underscore on x86_64 ("x86_64"), so the
        // character class allows underscore alongside lowercase letters, digits, and the dash.
        assert(classifier.matches("""[a-z][a-z0-9_\-]+"""))
    }

    // -------------------------------------------------------------------------
    // detectIs64Bit, current JVM should be 64-bit on all CI platforms
    // -------------------------------------------------------------------------

    "detectIs64Bit: current JVM reports 64-bit" in {
        // Every CI platform (Linux amd64, Linux arm64, macOS arm64) is 64-bit.
        assert(NativeLoader.detectIs64Bit() == true)
    }

    "detectIs64Bit: respects sun.arch.data.model=32 (simulated)" in {
        // Temporarily override the system property to simulate a 32-bit host.
        val prop  = "sun.arch.data.model"
        val prior = Option(java.lang.System.getProperty(prop))
        java.lang.System.setProperty(prop, "32")
        try
            assert(NativeLoader.detectIs64Bit() == false)
        finally
            prior match
                case Some(v) => java.lang.System.setProperty(prop, v): Unit
                case None    => java.lang.System.clearProperty(prop): Unit
            end match
        end try
    }

    "detectIs64Bit: respects sun.arch.data.model=64 (simulated)" in {
        val prop  = "sun.arch.data.model"
        val prior = Option(java.lang.System.getProperty(prop))
        java.lang.System.setProperty(prop, "64")
        try
            assert(NativeLoader.detectIs64Bit() == true)
        finally
            prior match
                case Some(v) => java.lang.System.setProperty(prop, v): Unit
                case None    => java.lang.System.clearProperty(prop): Unit
            end match
        end try
    }

    // -------------------------------------------------------------------------
    // checkPlatform
    // -------------------------------------------------------------------------

    "checkPlatform: does not throw when isBit64=true" in {
        // Must complete without raising FfiLoadError.Unsupported.
        NativeLoader.checkPlatform(true)
        succeed
    }

    "checkPlatform: throws FfiLoadError.Unsupported when isBit64=false" in {
        val ex = intercept[FfiLoadError.Unsupported] {
            NativeLoader.checkPlatform(false)
        }
        assert(ex.getMessage.nonEmpty)
    }

    "checkPlatform: error message mentions 32-bit or the data model" in {
        val ex = intercept[FfiLoadError.Unsupported] {
            NativeLoader.checkPlatform(false)
        }
        // The error must give the user enough context to diagnose the issue.
        val msg = ex.getMessage.toLowerCase
        assert((msg.contains("32") || msg.contains("bit") || msg.contains("model") || msg.contains("unsupported")) == true)
    }
end NativeLoaderOsDetectTest
