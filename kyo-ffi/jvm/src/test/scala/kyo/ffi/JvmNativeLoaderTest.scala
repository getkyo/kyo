package kyo.ffi

import kyo.ffi.internal.NativeLoader

class JvmNativeLoaderTest extends Test:

    "detectOs" in {
        val os = NativeLoader.detectOs
        assert(Seq(NativeLoader.Os.Linux, NativeLoader.Os.LinuxMusl, NativeLoader.Os.Darwin, NativeLoader.Os.Windows).contains(os))
    }

    "detectArch" in {
        val a = NativeLoader.detectArch
        assert(Seq(NativeLoader.Arch.X86_64, NativeLoader.Arch.Aarch64).contains(a))
    }

    "libPrefix/libExtension match OS" in {
        import NativeLoader.Os.*
        assert(Linux.libPrefix == "lib")
        assert(Linux.libExtension == "so")
        assert(Darwin.libExtension == "dylib")
        assert(Windows.libPrefix == "")
        assert(Windows.libExtension == "dll")
    }

    // Regression for the Linux libc loader bug: declaring `library = "c"` (libc, a system library)
    // must succeed on every platform. On Linux, `dlopen("libc.so")` fails because `libc.so` is a GNU ld
    // linker script, not a loadable object (the SONAME is `libc.so.6`); the loader now falls back to the
    // native linker's default lookup, which carries libc symbols without naming a versioned SONAME.
    // `malloc` exists in libc on both Linux and macOS, so this guards the fallback on both.
    "load(\"c\") resolves libc via the native linker default lookup" in {
        val lookup = NativeLoader.load("c")
        assert(lookup != null)
        assert(lookup.find("malloc").isPresent == true)
    }

    // A genuinely-missing NAMED library surfaces a declared FfiLoadError.LibraryNotFound at first `find`, not the
    // opaque NoSuchElementException the generated `orElseThrow` used to leak. `NativeLoader.load` returns a wrapping
    // lookup over the native linker's default lookup (libc and friends still resolve there); a symbol that lookup
    // cannot resolve, for a non-system library id, raises LibraryNotFound naming the library and the searched path.
    // The corrected fallback contract is covered in depth by NativeLoaderMissingNativeTest; pinned here too.
    "load of a missing named library raises FfiLoadError.LibraryNotFound at first find" in {
        val lookup = NativeLoader.load("kyo_ffi_does_not_exist_no_really_not")
        assert(lookup != null)
        val ex = intercept[FfiLoadError.LibraryNotFound](lookup.find("kyo_ffi_symbol_that_does_not_exist_anywhere"))
        assert(ex.getMessage.contains("kyo_ffi_does_not_exist_no_really_not"))
    }
end JvmNativeLoaderTest
