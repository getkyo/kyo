package kyo.net.internal.backend

import kyo.*
import kyo.ffi.FfiLoadError
import kyo.net.Test

/** What a demotion tells its reader: the `<os>-<arch>` it names and the remedy its outcome implies. Asserted on every
  * platform, because the tag is derived from platform APIs that differ in which ones answer, and the remedy depends
  * on which library failed, which is the same question on all four.
  *
  * Shared rather than jvm-native: reading os.arch through `sys.props` answers on the JVM and Native and not on JS
  * or Wasm, so a jvm-native leaf reports green for a tag that reads "<os>-unknown" on half the platforms.
  */
class CapabilityProbeReportTest extends Test:

    "the derived tag names this runtime's os and architecture" in {
        val tag = CapabilityProbe.platform
        assert(tag.contains("-"), s"expected an <os>-<arch> tag, got $tag")
        // The halves are asserted separately: a tag is useless with either one unresolved, and "unknown-x86_64"
        // and "darwin-unknown" both satisfy a shape check.
        assert(!tag.startsWith("unknown-"), s"the os half did not resolve: $tag")
        assert(!tag.endsWith("-unknown"), s"the arch half did not resolve: $tag")
    }

    "the loader's own tag wins over the derived one" in {
        // The loader is the only side that knows the libc flavour, so its tag names the artifact it searched for.
        val thrown =
            new FfiLoadError.LibraryNotFound("kyonet_posix_uring", Chunk("bundled resource"), "not found", null, "linux-musl-x86_64")
        CapabilityProbe.classify(thrown, Chunk("kyonet_posix_uring")) match
            case CapabilityOutcome.NotBundled(id, platform) =>
                assert(id == "kyonet_posix_uring")
                assert(platform == "linux-musl-x86_64", s"the loader's tag must survive, got $platform")
            case other => fail(s"expected NotBundled, got ${other.describe}")
        end match
    }

    "a load error with no tag falls back to the derived one" in {
        val thrown = new FfiLoadError.LibraryNotFound("kyonet_posix_uring", Chunk("bundled resource"), null)
        CapabilityProbe.classify(thrown, Chunk("kyonet_posix_uring")) match
            case CapabilityOutcome.NotBundled(_, platform) => assert(platform == CapabilityProbe.platform)
            case other                                     => fail(s"expected NotBundled, got ${other.describe}")
    }

    /** A system library resolves from the process's own symbol scope and is never packaged, so a LibraryNotFound
      * over one means a SYMBOL the binding declared is absent and the loader's message is the only thing naming
      * it. NotBundled would answer that with a native path or package, and none of them carries a libc.
      */
    "a system library's failure keeps the loader's message instead of naming a native to install" in {
        val thrown = new FfiLoadError.LibraryNotFound(
            "c",
            Chunk("process default scope"),
            "Symbol 'io_uring_setup' is absent from system library 'c' on linux-x86_64; " +
                "this is a missing symbol, not a missing library",
            null
        )
        CapabilityProbe.classify(thrown, Chunk("c")) match
            case CapabilityOutcome.Unavailable(reason) =>
                assert(reason.contains("io_uring_setup"), s"the absent symbol must survive, got $reason")
            case other => fail(s"a system library must not classify as a packaging problem: ${other.describe}")
        end match
    }

    /** koffi is the application's npm dependency. A Node host without it cannot reach any native, and the fix is
      * installing koffi: a KYO_FFI_<ID>_PATH override or a native package leaves it exactly as broken.
      */
    "a missing koffi keeps the loader's install instruction instead of naming a native to install" in {
        val thrown = new FfiLoadError.LibraryNotFound(
            kyo.ffi.internal.KoffiRuntime.LibraryId,
            Chunk("require(\"koffi\")"),
            "the koffi npm package is not installed or not resolvable; install it (npm i koffi) to use the native FFI backend",
            null
        )
        CapabilityProbe.classify(thrown, Chunk("kyonet_posix_uring")) match
            case CapabilityOutcome.Unavailable(reason) =>
                assert(reason.contains("npm i koffi"), s"the install instruction must survive, got $reason")
                assert(!reason.contains("KYO_FFI_KOFFI_PATH"), s"koffi has no path override, got $reason")
            case other => fail(s"a missing koffi must not classify as a packaging problem: ${other.describe}")
        end match
    }

    "a koffi that is installed and fails to load keeps the first line of why" in {
        // The loader's message reads the same whether koffi is absent or present and unloadable, so the cause is the
        // only thing telling the reader which. A `require` failure carries its resolution stack after the first line.
        val loadFailure = new RuntimeException("/lib/ld-linux-x86-64.so.2: version `GLIBC_2.34' not found\nRequire stack:\n- /app/main.js")
        val thrown      = new FfiLoadError.LibraryNotFound(
            kyo.ffi.internal.KoffiRuntime.LibraryId,
            Chunk("require(\"koffi\")"),
            "the koffi npm package is not installed or not resolvable; install it (npm i koffi) to use the native FFI backend",
            loadFailure
        )
        CapabilityProbe.classify(thrown, Chunk("kyonet_posix_uring")) match
            case CapabilityOutcome.Unavailable(reason) =>
                assert(reason.contains("GLIBC_2.34"), s"the load failure must survive, got $reason")
                assert(!reason.contains("Require stack"), s"the reason is one line, got $reason")
            case other => fail(s"expected Unavailable, got ${other.describe}")
        end match
    }

end CapabilityProbeReportTest
