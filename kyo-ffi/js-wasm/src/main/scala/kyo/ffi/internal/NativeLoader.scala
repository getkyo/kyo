package kyo.ffi.internal

import kyo.Chunk
import kyo.ffi.FfiLoadError
import scala.scalajs.js
import scala.util.Try

/** JS NativeLoader. Resolves native library path for koffi: env var override → npm package lookup → bare name fallback. Rejects browsers.
  */
object NativeLoader:

    def load(libraryId: String): String =
        if detectBrowser() then
            throw new FfiLoadError.Unsupported(FfiPlatformErrors.BrowserUnsupportedLoader)
        end if
        jsResolve(libraryId)
    end load

    /** Returns `true` if neither `process` nor `require` is defined (browser heuristic). */
    def detectBrowser(): Boolean =
        val hasProcess = js.typeOf(js.Dynamic.global.selectDynamic("process")) != "undefined"
        val hasRequire = js.typeOf(js.Dynamic.global.selectDynamic("require")) != "undefined"
        !hasProcess && !hasRequire
    end detectBrowser

    def jsResolve(libraryId: String): String =
        // 32-bit host rejection runs on every jsResolve call; the `platformChecked` flag keeps the work to a
        // single successful check process-wide. `process.arch` identifies 32-bit Node targets (e.g. `ia32`, `x32`, `arm`, `mips`).
        ensurePlatformChecked()
        // Each branch is now a REAL presence check, not a blind candidate: an unresolvable library id raises
        // FfiLoadError.LibraryNotFound instead of returning a name koffi.load later fails on cryptically. This is
        // the JS half of the manifest-driven pre-check (the manifest id is the `libraryId` the generated impl was
        // emitted with). JS module init is not permanently poisoned by a throwing initializer the way a JVM
        // `<clinit>` is, so raising the catchable error here from the impl's load path is sufficient.
        val candidates = scala.collection.mutable.Buffer.empty[String]

        // 1. Env-var override (operator-controlled) -- honored only when it points at a file that exists.
        // security: do not set from untrusted input, resolves a filesystem path to load as native code.
        val envKey = s"KYO_FFI_${libraryId.toUpperCase.replace('-', '_')}_PATH"
        val env    = js.Dynamic.global.process.env.selectDynamic(envKey)
        if !js.isUndefined(env) && env != null then
            val p = env.asInstanceOf[String]
            candidates += s"env $envKey=$p"
            if fileExists(p) then return p
        end if

        // 2. Best-effort npm package lookup via require.resolve. `require.resolve` is itself a presence check (it
        // throws when the file is absent), so a resolved path is genuinely present.
        val packagePrefix = sys.props.getOrElse("kyo.ffi.js.packagePrefix", "@kyo/ffi-native")
        val os            = detectOs()
        val arch          = detectArch()
        val ext           = osExt(os)
        val resolvePath   = s"$packagePrefix/native/$os-$arch/lib$libraryId.$ext"
        candidates += s"require.resolve $resolvePath"
        requireResolve(resolvePath) match
            case Some(path) => return path
            case None       => ()

        // 3. Known system libraries (libc, libm, ...) cannot be loaded by their bare name on every host:
        // the bare name "c" is not a loadable object on Linux glibc (the SONAME is `libc.so.6`), and the
        // GNU ld linker script `libc.so` is rejected by dlopen. Resolve these to koffi's process-default
        // scope instead. See `resolveSystemLib`.
        resolveSystemLib(libraryId, os) match
            case Some(resolution) => return resolution
            case None             => ()

        // 4. Bare library name, gated by an actual koffi.load probe: koffi resolves an installed system library
        // (by SONAME / default search path) here, so a name that loads is present. A name that does not load is
        // genuinely absent.
        candidates += s"""koffi.load("$libraryId")"""
        if tryKoffiLoad(libraryId) then return libraryId

        // 5. Nothing resolved: the native is not present for this runtime.
        throw new FfiLoadError.LibraryNotFound(
            libraryId,
            Chunk.from(candidates),
            s"Native library '$libraryId' could not be resolved on this JS runtime. Set KYO_FFI_" +
                s"${libraryId.toUpperCase.replace('-', '_')}_PATH to an absolute path, install the '$packagePrefix' " +
                s"package for $os-$arch, or install the '$libraryId' system library. Tried, in order: " +
                s"${candidates.mkString("; ")}.",
            null
        )
    end jsResolve

    /** `true` when `path` exists on the filesystem (Node `fs.existsSync`); `false` on any error. */
    private def fileExists(path: String): Boolean =
        try NodeFs.existsSync(path)
        catch case _: Throwable => false

    /** `require.resolve(resolvePath)` if `require` is available and the path resolves, else `None`. */
    private def requireResolve(resolvePath: String): Option[String] =
        Try {
            val req = js.Dynamic.global.selectDynamic("require")
            if js.isUndefined(req) || req == null then null
            else
                val r = req.applyDynamic("resolve")(resolvePath)
                if js.isUndefined(r) || r == null then null
                else r.asInstanceOf[String]
            end if
        }.toOption.flatMap(Option(_))

    /** Probe whether koffi can load `name` (an installed system library by SONAME / default search). `false` when
      * koffi is unavailable or the load fails. Used only as the last presence gate; the caller loads for real.
      *
      * koffi is required DYNAMICALLY (`require("koffi")`), not through the static `@JSImport` facade, so this
      * loader keeps no static dependency on the koffi package: a runtime with no koffi installed just makes the
      * probe return `false` instead of failing to load this module.
      */
    private def tryKoffiLoad(name: String): Boolean =
        Try {
            val req = js.Dynamic.global.selectDynamic("require")
            if js.isUndefined(req) || req == null then false
            else
                val koffi = req.asInstanceOf[js.Function1[String, js.Dynamic]]("koffi")
                if js.isUndefined(koffi) || koffi == null then false
                else
                    val lib = koffi.applyDynamic("load")(name)
                    !js.isUndefined(lib) && lib != null
                end if
            end if
        }.getOrElse(false)

    /** koffi-loadable resolution for known system libraries (libc, libm, pthread, dl, rt).
      *
      * Returns `Some(resolution)` for a recognised system library, where `resolution` is the value to hand to `koffi.load(...)`, or `None`
      * for any other id (a bundled / user library that resolves by its bare name or an explicit path).
      *
      * The resolution is `null`, which makes koffi load against the process's default symbol scope (POSIX `RTLD_DEFAULT`, the equivalent of
      * `GetModuleHandle(NULL)` on Windows). This is the JS analogue of the JVM loader's `Linker.nativeLinker().defaultLookup()` fallback and
      * is preferred over hardcoding a versioned SONAME for three reasons:
      *
      *   - Node already links libc / libm / pthread into the running process, so their symbols (`socket`, `epoll_create1`, `kqueue`,
      *     `malloc`, ...) are present in the default scope on every platform without naming a file.
      *   - It is uniform across Linux glibc (`libc.so.6`), Linux musl (`libc.so`), and macOS (`libSystem.B.dylib`); we do not have to detect
      *     the libc flavour or pick the right SONAME per host.
      *   - It avoids the Linux trap where the bare name `"c"` and the `libc.so` linker script both fail `dlopen`.
      *
      * koffi reaches `RTLD_DEFAULT` when `koffi.load` is called with a non-string (here `null`); see koffi's `ffi.cc` (`module =
      * RTLD_DEFAULT`). The per-OS loadable SONAMEs, kept here for the record as the documented alternative, are: Linux glibc `libc.so.6`,
      * Linux musl `libc.so`, macOS `libSystem.B.dylib` (libc/libm/pthread all live in libSystem on darwin).
      *
      * `os` is the [[detectOs]] tag; it is currently unused because the default-scope resolution is platform-uniform, but it is threaded
      * through so a SONAME-per-OS path can be slotted in here without touching `jsResolve`.
      */
    def resolveSystemLib(libraryId: String, os: String): Option[String] =
        // security: only well-known, fixed system-library names map to a system resolution; everything else
        // (including operator-supplied ids) keeps the bare-name / explicit-path resolution above. The
        // CLASSIFICATION (which ids are system) comes from the shared `SystemLibraries` set so the JVM and JS
        // loaders agree on what counts as "absence expected"; only the per-OS RESOLUTION lives here.
        if !SystemLibraries.isSystem(libraryId) then None
        else if (libraryId == "c" || libraryId == "m") && os == "windows" then
            // Windows has no RTLD_DEFAULT-style process scope koffi can bind portably; the universal
            // C runtime carries the standard C and math symbols (abs, floor, memcpy, strlen, getenv,
            // pow, ...) for both families. POSIX-only names (getpid, time) exist there only as their
            // underscore-prefixed CRT variants and fail at symbol lookup.
            Some("ucrtbase.dll")
        else
            // `null` tells koffi to bind against the process default symbol scope (RTLD_DEFAULT). The value is
            // intentionally null, not the bare name, so glibc / musl / macOS are all covered without a SONAME.
            Some(null)
    end resolveSystemLib

    // --- Platform detection ---

    private def detectOs(): String =
        val p = js.Dynamic.global.process.platform
        if js.isUndefined(p) || p == null then "unknown"
        else
            p.asInstanceOf[String] match
                case "darwin"  => "darwin"
                case "linux"   => "linux"
                case "win32"   => "windows"
                case "freebsd" => "freebsd"
                case other     => other
        end if
    end detectOs

    private def detectArch(): String =
        val a = js.Dynamic.global.process.arch
        if js.isUndefined(a) || a == null then "unknown"
        else
            a.asInstanceOf[String] match
                case "x64"   => "x86_64"
                case "arm64" => "aarch64"
                case other   => other
        end if
    end detectArch

    private def osExt(os: String): String = os match
        case "darwin"  => "dylib"
        case "windows" => "dll"
        case _         => "so"

    // --- 32-bit host rejection ---

    @volatile private var platformChecked: Boolean = false

    /** Run the 64-bit host check exactly once per process. */
    private def ensurePlatformChecked(): Unit =
        if !platformChecked then
            checkPlatform(detectArchString())
            platformChecked = true
        end if
    end ensurePlatformChecked

    /** Read `process.arch` if available, else return an empty string (no detection possible → treat as unsupported fallback below). */
    private def detectArchString(): String =
        val a = js.Dynamic.global.process.arch
        if js.isUndefined(a) || a == null then "" else a.asInstanceOf[String]
    end detectArchString

    /** Throw [[kyo.ffi.FfiLoadError.Unsupported]] if `arch` identifies a 32-bit Node target. Exposed for unit tests. */
    def checkPlatform(arch: String): Unit =
        val is32Bit = arch match
            case "ia32" | "x32" | "arm" | "mips" | "mipsel" | "ppc" | "s390" => true
            case _                                                           => false
        if is32Bit then
            val msg = FfiPlatformErrors.unsupported32BitHost(s"process.arch = $arch")
            throw new FfiLoadError.Unsupported(msg)
        end if
    end checkPlatform
end NativeLoader
