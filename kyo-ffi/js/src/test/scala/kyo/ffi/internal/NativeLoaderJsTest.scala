package kyo.ffi.internal

import kyo.*
import kyo.discard
import kyo.ffi.FfiLoadError
import kyo.ffi.Test
import kyo.internal.PlatformJs
import scala.scalajs.js as sjs

/** Validates the JS-side resolver precedence, now that each branch is a REAL presence check (not a blind
  * candidate):
  *
  *   1. `process.env.KYO_FFI_<LIBID>_PATH` wins WHEN the file it names exists.
  *   2. `./kyo-ffi/native/<os>-<arch>/lib<id>.<ext>` beside the linked program, resolved against the program's location.
  *   3. `require.resolve('<packagePrefix>/native/<os>-<arch>/lib<id>.<ext>')`, only when `kyo.ffi.js.packagePrefix` is set.
  *   4. Known system libraries resolve to the process-default scope.
  *   5. A `koffi.load` probe of the bare id (an installed system library resolves here).
  *   6. None of the above: [[FfiLoadError.LibraryNotFound]].
  *
  * The test environment stages no natives, installs no package and no `koffi`, and the fixture ids name no installed
  * library, so an unresolvable id raises `LibraryNotFound` rather than silently returning a bad name.
  */
class NativeLoaderJsTest extends Test:

    // Resolution is a sequence of real presence checks against a host: it reads process.env, writes candidate files through node:fs, and
    // locates the running program through import.meta.url. A page has none of those, so there is nothing here for it to run.
    override protected def hostFilters = Chunk(kyo.test.HostFilter.NotBrowser)

    private val libId       = "kyo_test_loader"
    private val envKey      = s"KYO_FFI_${libId.toUpperCase.replace('-', '_')}_PATH"
    private val prefixProp  = "kyo.ffi.js.packagePrefix"
    private val savedPrefix = sys.props.get(prefixProp)

    // A path that is guaranteed to exist on the Node host: the running node binary itself.
    private def existingPath: String =
        sjs.Dynamic.global.process.execPath.asInstanceOf[String]

    // Every leaf reads process-wide state other leaves change (the env var, the package-prefix property, a native staged beside
    // the program, the working directory), so a concurrent leaf would resolve through another leaf's state.
    override def config = super.config.sequential

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

    "env var KYO_FFI_<ID>_PATH wins when the file it names exists" in {
        // Even with an obviously missing package prefix, an existing env-path short-circuits resolution.
        sys.props.update(prefixProp, "@nope/never-installed")
        setEnv(envKey, existingPath)
        assert(NativeLoader.jsResolve(libId) == existingPath)
    }

    "env var pointing at a missing file is not honored; an unresolvable id raises LibraryNotFound" in {
        sys.props.update(prefixProp, "@nope/never-installed")
        setEnv(envKey, "/abs/path/that/does/not/exist/libkyo_test_loader.so")
        val ex = intercept[FfiLoadError.LibraryNotFound](NativeLoader.jsResolve(libId))
        assert(ex.libraryId == libId)
        assert(ex.candidates.nonEmpty)
    }

    "without env var, an unresolvable package prefix raises LibraryNotFound (no blind bare-name fallback)" in {
        sys.props.update(prefixProp, "@nope/never-installed")
        val ex = intercept[FfiLoadError.LibraryNotFound](NativeLoader.jsResolve(libId))
        assert(ex.libraryId == libId)
    }

    "with no package prefix set, no package is looked up, and the error names the staging and the variable" in {
        val ex = intercept[FfiLoadError.LibraryNotFound](NativeLoader.jsResolve(libId))
        assert(ex.libraryId == libId)
        assert(!ex.candidates.exists(_.startsWith("require.resolve ")))
        assert(ex.getMessage.contains("ffiWithJsNatives"))
        assert(ex.getMessage.contains(envKey))
    }

    "a native staged beside the linked program resolves with no env override, from any working directory" in {
        // Where the staging puts a native is the path the loader reports looking beside the program; a native placed exactly there
        // must be found after the working directory moves away, which a lookup anchored at the working directory would miss.
        val fs        = PlatformJs.nodeBuiltin("node:fs").get
        val nodeOs    = PlatformJs.nodeBuiltin("node:os").get
        val path      = PlatformJs.nodeBuiltin("node:path").get
        val process   = sjs.Dynamic.global.process
        val programAt = path.dirname(PlatformJs.nodeBuiltin("node:url").get.fileURLToPath(programUrl)).asInstanceOf[String]
        val relative = intercept[FfiLoadError.LibraryNotFound](NativeLoader.jsResolve(libId)).candidates
            .filter(_.startsWith("beside the linked program "))
            .map(_.stripPrefix("beside the linked program "))
        assert(relative.size == 1)
        assert(relative.head.startsWith("./kyo-ffi/native/"))
        val native    = path.resolve(programAt, relative.head).asInstanceOf[String]
        val platform  = path.dirname(native)
        val elsewhere = fs.mkdtempSync(path.join(nodeOs.tmpdir(), "kyo-ffi-cwd-")).asInstanceOf[String]
        val cwd       = process.cwd().asInstanceOf[String]
        // The staging tree sits in the linker's output directory, and the linker refuses to relink over a directory it did not
        // write, so the tree this leaf creates goes away with it. This module's link stages nothing, so the tree is new here.
        val stagingRoot = path.join(programAt, "kyo-ffi").asInstanceOf[String]
        assert(!fs.existsSync(stagingRoot).asInstanceOf[Boolean])
        try
            discard(fs.mkdirSync(platform, sjs.Dynamic.literal(recursive = true)))
            discard(fs.writeFileSync(native, ""))
            discard(process.chdir(elsewhere))
            assert(NativeLoader.jsResolve(libId) == native)
        finally
            discard(process.chdir(cwd))
            discard(fs.rmSync(stagingRoot, sjs.Dynamic.literal(recursive = true, force = true)))
            discard(fs.rmSync(elsewhere, sjs.Dynamic.literal(recursive = true, force = true)))
        end try
    }

    "envKey computation uppercases and replaces hyphens with underscores" in {
        val id         = "my-lib-x"
        val expectedEv = "KYO_FFI_MY_LIB_X_PATH"
        setEnv(expectedEv, existingPath)
        try
            assert(NativeLoader.jsResolve(id) == existingPath)
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

    "jsResolve env-var override still wins over system-library resolution for 'c' when the file exists" in {
        val cEnvKey = "KYO_FFI_C_PATH"
        setEnv(cEnvKey, existingPath)
        try
            assert(NativeLoader.jsResolve("c") == existingPath)
        finally
            clearEnv(cEnvKey)
        end try
    }

    "a native installed where the package route looks resolves with no env override, on a link with no require global" in {
        // This module links as an ES module, which has no `require`, the case of every WasmGC link and any application linked as one.
        // The package route reports the path it looks for; a native placed exactly there must be found.
        val fs     = PlatformJs.nodeBuiltin("node:fs").get
        val nodeOs = PlatformJs.nodeBuiltin("node:os").get
        val path   = PlatformJs.nodeBuiltin("node:path").get
        val root   = fs.mkdtempSync(path.join(nodeOs.tmpdir(), "kyo-ffi-package-")).asInstanceOf[String]
        try
            sys.props.update(prefixProp, root)
            val lookedFor = intercept[FfiLoadError.LibraryNotFound](NativeLoader.jsResolve(libId)).candidates
                .filter(_.startsWith("require.resolve "))
                .map(_.stripPrefix("require.resolve "))
            assert(lookedFor.size == 1)
            val native = lookedFor.head
            discard(fs.mkdirSync(path.dirname(native), sjs.Dynamic.literal(recursive = true)))
            discard(fs.writeFileSync(native, ""))
            // Node resolves a package path to the real file, and a temporary directory can sit behind a symlink (macOS's does).
            assert(NativeLoader.jsResolve(libId) == fs.realpathSync(native).asInstanceOf[String])
        finally discard(fs.rmSync(root, sjs.Dynamic.literal(recursive = true, force = true)))
        end try
    }

    "an env read that throws, as Deno's does without --allow-env, raises LibraryNotFound" in {
        sys.props.update(prefixProp, "@nope/never-installed")
        val ex = withEnvThatThrows(intercept[FfiLoadError.LibraryNotFound](NativeLoader.jsResolve(libId)))
        assert(ex.libraryId == libId)
    }

    // --- helpers ---

    /** The URL of the linked test program: this module links as an ES module on both of its rows. */
    private def programUrl: String =
        kyo.internal.Platform.linkTimeIf(scala.scalajs.LinkingInfo.moduleKind == scala.scalajs.LinkingInfo.ModuleKind.ESModule) {
            sjs.`import`.meta.url.asInstanceOf[String]
        } {
            throw new IllegalStateException("kyo-ffi's JS tests link as an ES module")
        }

    /** `process.env` replaced by a proxy that throws from every trap, as Deno's does without `--allow-env`. */
    private def withEnvThatThrows[A](f: => A): A =
        val process                    = sjs.Dynamic.global.process
        val saved                      = process.env
        val refuse: sjs.Function0[Any] = () => sjs.special.`throw`(sjs.Dynamic.newInstance(sjs.Dynamic.global.Error)("NotCapable"))
        val traps = sjs.Dynamic.literal(get = refuse, has = refuse, ownKeys = refuse, getOwnPropertyDescriptor = refuse)
        process.updateDynamic("env")(sjs.Dynamic.newInstance(sjs.Dynamic.global.Proxy)(sjs.Dynamic.literal(), traps))
        try f
        finally process.updateDynamic("env")(saved)
        end try
    end withEnvThatThrows

    private def setEnv(key: String, value: String): Unit =
        sjs.Dynamic.global.process.env.updateDynamic(key)(value)

    private def clearEnv(key: String): Unit =
        // In Node.js, assigning `undefined` to a process.env key stores the literal string "undefined"; you must `delete` instead.
        sjs.special.delete(sjs.Dynamic.global.process.env, key)
end NativeLoaderJsTest
