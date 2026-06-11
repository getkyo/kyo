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

    // A genuinely-missing NAMED library no longer throws LibraryNotFound at load time. Because libc and
    // friends legitimately live in the native linker's default lookup (and bundled libraries are resolved
    // earlier via the resource path), the loader returns the default lookup as the final fallback. The
    // absence of a named library therefore surfaces as a symbol-not-found at first `find`, not an exception
    // at load. This test documents and pins that new contract: load succeeds, but the bogus symbol is absent.
    "load of a missing named library returns the default lookup and reports the symbol absent" in {
        val lookup = NativeLoader.load("kyo_ffi_does_not_exist_no_really_not")
        assert(lookup != null)
        assert(lookup.find("kyo_ffi_symbol_that_does_not_exist_anywhere").isPresent == false)
    }
end JvmNativeLoaderTest
