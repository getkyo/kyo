package kyo.ffi.internal

import kyo.ffi.FfiLoadError
import kyo.ffi.Test

/** Missing-native diagnosability for the JVM loader.
  *
  * When a native library is not bundled for the running platform, the loader used to fall silently through to the native linker's
  * default lookup, and the generated `find(sym).orElseThrow()` then leaked an opaque `java.util.NoSuchElementException: No value present`
  * from a scheduler worker's class-init. These tests pin the corrected fallback: `NativeLoader.load`'s default-lookup fallback returns a
  * lookup whose failed `find` raises a declared [[FfiLoadError.LibraryNotFound]] naming the library, the `<os>-<arch>`, and the searched
  * resource path (never a `NoSuchElementException`), while symbols the default lookup DOES carry still resolve unchanged.
  */
class NativeLoaderMissingNativeTest extends Test:

    private def platform: String =
        s"${NativeLoader.detectOs.tagName}-${NativeLoader.detectArch.tagName}"

    "load fallback: an absent symbol raises FfiLoadError.LibraryNotFound, not NoSuchElementException" in {
        // A named library that is neither bundled for this platform nor a system library falls through to the wrapped default lookup.
        val id     = "kyo_ffi_missing_native_probe_lib"
        val lookup = NativeLoader.load(id)
        // A symbol the default lookup cannot resolve raises the declared error (pre-fix: the generated `orElseThrow` leaked a bare
        // NoSuchElementException here). `intercept` fails if any other type is thrown, so this also asserts it is NOT NoSuchElementException.
        val ex = intercept[FfiLoadError.LibraryNotFound](lookup.find("kyo_ffi_absent_symbol_zzz"))
        assert(ex.libraryId == id)
        assert(ex.candidates.nonEmpty)
        val msg = ex.getMessage
        assert(msg != null)
        // The message names the library, the platform, and the searched resource path.
        assert(msg.contains(id))
        assert(msg.contains(platform))
        assert(msg.contains(s"/META-INF/native/$platform/"))
    }

    "load fallback: symbols the default lookup carries still resolve unchanged" in {
        // `library = "c"` is libc, a system library; `malloc` lives in the native linker's default lookup on every platform, so the
        // fallback wrapper must return it rather than raising. This is the same guarantee the pre-fix loader gave for real system symbols.
        val lookup = NativeLoader.load("c")
        assert(lookup.find("malloc").isPresent == true)
    }

    "load fallback: a system library's missing symbol is reported as a missing symbol, not a missing library" in {
        // The two message cases are distinguished by whether the id is a known system library. On Linux libc resolves ONLY via the
        // default-lookup wrapper (`dlopen("libc.so")` is rejected: it is a GNU ld linker script), so the wrapper is exercised for "c"
        // there. On macOS `libc.dylib` resolves as a named library and never reaches the wrapper, so this case is Linux-specific.
        if NativeLoader.detectOs == NativeLoader.Os.Linux then
            val lookup = NativeLoader.load("c")
            val ex     = intercept[FfiLoadError.LibraryNotFound](lookup.find("kyo_ffi_absent_libc_symbol_zzz"))
            val msg    = ex.getMessage
            assert(msg.contains("missing symbol"))
            assert(msg.contains("not a missing library"))
        else succeed
        end if
    }
end NativeLoaderMissingNativeTest
