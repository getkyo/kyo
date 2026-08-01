package kyo.ffi.internal

import kyo.*
import kyo.discard
import kyo.ffi.Test
import scala.scalajs.js as sjs

/** Validates the JS-side resolver precedence:
  *
  *   1. `process.env.KYO_FFI_<LIBID>_PATH` wins if defined.
  *   2. `require.resolve('<packagePrefix>/native/<os>-<arch>/lib<id>.<ext>')`, uses the `kyo.ffi.js.packagePrefix` sys prop.
  *   3. Falls back to the bare library id for koffi's system-path search.
  *
  * The test environment has no installed `@kyo/ffi-native` npm package (or the override we set), so path 2 always misses and we can observe
  * path 3 indirectly by asserting the bare id is returned when there is no env override.
  */
class NativeLoaderJsTest extends Test:

    private val libId       = "kyo_test_loader"
    private val envKey      = s"KYO_FFI_${libId.toUpperCase.replace('-', '_')}_PATH"
    private val prefixProp  = "kyo.ffi.js.packagePrefix"
    private val savedPrefix = sys.props.get(prefixProp)

    // Each leaf mutates the resolver env var + package-prefix sys prop; clear/save before the body and restore after,
    // isolating leaves (the kyo-test equivalent of the old beforeEach/afterEach pair).
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        Sync.defer {
            clearEnv(envKey)
            discard(sys.props.remove(prefixProp))
            Scope.ensure {
                clearEnv(envKey)
                savedPrefix match
                    case Some(v) => sys.props.update(prefixProp, v)
                    case None    => discard(sys.props.remove(prefixProp))
                end match
            }.andThen(body)
        }

    "env var KYO_FFI_<ID>_PATH wins over everything else" in {
        // Even with an obviously missing package prefix, the env var short-circuits resolution.
        sys.props.update(prefixProp, "@nope/never-installed")
        setEnv(envKey, "/abs/path/to/libkyo_test_loader.so")
        assert(NativeLoader.jsResolve(libId) == "/abs/path/to/libkyo_test_loader.so")
    }

    "without env var, unresolvable package prefix falls back to the bare id" in {
        sys.props.update(prefixProp, "@nope/never-installed")
        assert(NativeLoader.jsResolve(libId) == libId)
    }

    "default package prefix is also unresolvable in this test env and still falls back to the bare id" in {
        // No env, no override, default is `@kyo/ffi-native` which is not present in node_modules for tests.
        assert(NativeLoader.jsResolve(libId) == libId)
    }

    "envKey computation uppercases and replaces hyphens with underscores" in {
        val id         = "my-lib-x"
        val expectedEv = "KYO_FFI_MY_LIB_X_PATH"
        setEnv(expectedEv, "/override")
        try
            assert(NativeLoader.jsResolve(id) == "/override")
        finally
            clearEnv(expectedEv)
        end try
    }

    // --- system-library resolution (libc and friends) ---

    "resolveSystemLib maps known system libraries to the process default scope (null) on POSIX hosts" in {
        // `null` makes koffi.load bind against the process default symbol scope (RTLD_DEFAULT), which carries
        // libc / libm / pthread on every POSIX platform.
        for os <- List("linux", "darwin", "freebsd", "unknown") do
            assert(NativeLoader.resolveSystemLib("c", os) == Some(null))
            assert(NativeLoader.resolveSystemLib("m", os) == Some(null))
            assert(NativeLoader.resolveSystemLib("pthread", os) == Some(null))
            assert(NativeLoader.resolveSystemLib("dl", os) == Some(null))
            assert(NativeLoader.resolveSystemLib("rt", os) == Some(null))
        end for
    }

    "resolveSystemLib maps the C and math families to the universal CRT on Windows" in {
        // Windows has no RTLD_DEFAULT-style process scope koffi can bind portably; ucrtbase.dll
        // carries the standard C and math symbols. The POSIX-only families have no Windows
        // counterpart and keep the default-scope resolution, failing at symbol lookup.
        assert(NativeLoader.resolveSystemLib("c", "windows") == Some("ucrtbase.dll"))
        assert(NativeLoader.resolveSystemLib("m", "windows") == Some("ucrtbase.dll"))
        assert(NativeLoader.resolveSystemLib("pthread", "windows") == Some(null))
        assert(NativeLoader.resolveSystemLib("dl", "windows") == Some(null))
        assert(NativeLoader.resolveSystemLib("rt", "windows") == Some(null))
    }

    "resolveSystemLib returns None for non-system libraries so they keep bare-name resolution" in {
        assert(NativeLoader.resolveSystemLib("kyo_test_loader", "linux") == None)
        assert(NativeLoader.resolveSystemLib("kyonet_posix_uring", "linux") == None)
        assert(NativeLoader.resolveSystemLib("crypto", "darwin") == None)
    }

    "jsResolve('c') resolves libc to a loadable system resolution, not the unloadable bare name 'c'" in {
        // Before the fix this returned the bare id "c", which koffi.load cannot dlopen on Linux glibc
        // (the loadable SONAME is libc.so.6). POSIX hosts resolve to null (RTLD_DEFAULT); a Windows
        // host resolves to the universal CRT.
        val expected = if kyo.internal.Platform.isWindows then "ucrtbase.dll" else null
        assert(NativeLoader.jsResolve("c") == expected)
    }

    "jsResolve env-var override still wins over system-library resolution for 'c'" in {
        val cEnvKey = "KYO_FFI_C_PATH"
        setEnv(cEnvKey, "/custom/libc.so")
        try
            assert(NativeLoader.jsResolve("c") == "/custom/libc.so")
        finally
            clearEnv(cEnvKey)
        end try
    }

    // --- helpers ---

    private def setEnv(key: String, value: String): Unit =
        sjs.Dynamic.global.process.env.updateDynamic(key)(value)

    private def clearEnv(key: String): Unit =
        // In Node.js, assigning `undefined` to a process.env key stores the literal string "undefined"; you must `delete` instead.
        sjs.special.delete(sjs.Dynamic.global.process.env, key)
end NativeLoaderJsTest
