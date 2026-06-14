package kyo.ffi.internal

import kyo.ffi.FfiUnsupported
import scala.scalajs.js
import scala.util.Try

/** JS NativeLoader. Resolves native library path for koffi: env var override → npm package lookup → bare name fallback. Rejects browsers.
  */
object NativeLoader:

    def load(libraryId: String): String =
        if detectBrowser() then
            throw new FfiUnsupported(FfiPlatformErrors.BrowserUnsupportedLoader)
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
        // 1. Env-var override (operator-controlled).
        // security: do not set from untrusted input, resolves a filesystem path to load as native code.
        val envKey = s"KYO_FFI_${libraryId.toUpperCase.replace('-', '_')}_PATH"
        val env    = js.Dynamic.global.process.env.selectDynamic(envKey)
        if !js.isUndefined(env) && env != null then
            return env.asInstanceOf[String]

        // 2. Best-effort npm package lookup via require.resolve. Silently swallow any failure (require missing, package missing, etc.)
        // so we gracefully fall through to the bare-name path.
        val packagePrefix = sys.props.getOrElse("kyo.ffi.js.packagePrefix", "@kyo/ffi-native")
        val os            = detectOs()
        val arch          = detectArch()
        val ext           = osExt(os)
        val resolvePath   = s"$packagePrefix/native/$os-$arch/lib$libraryId.$ext"
        val resolved = Try {
            val req = js.Dynamic.global.selectDynamic("require")
            if js.isUndefined(req) || req == null then null
            else
                val r = req.applyDynamic("resolve")(resolvePath)
                if js.isUndefined(r) || r == null then null
                else r.asInstanceOf[String]
            end if
        }.toOption.flatMap(Option(_))
        resolved match
            case Some(path) => return path
            case None       => ()

        // 3. Known system libraries (libc, libm, ...) cannot be loaded by their bare name on every host:
        // the bare name "c" is not a loadable object on Linux glibc (the SONAME is `libc.so.6`), and the
        // GNU ld linker script `libc.so` is rejected by dlopen. Resolve these to koffi's process-default
        // scope instead. See `resolveSystemLib`.
        resolveSystemLib(libraryId, detectOs()) match
            case Some(resolution) => return resolution
            case None             => ()

        // 4. Fall back to the bare library name.
        libraryId
    end jsResolve

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
        // security: only well-known, fixed system-library names map to the process default scope; everything else
        // (including operator-supplied ids) keeps the bare-name / explicit-path resolution above.
        libraryId match
            case "c" | "m" | "pthread" | "dl" | "rt" =>
                // `null` tells koffi to bind against the process default symbol scope (RTLD_DEFAULT). The value is
                // intentionally null, not the bare name, so glibc / musl / macOS are all covered without a SONAME.
                Some(null)
            case _ =>
                None
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

    /** Throw [[kyo.ffi.FfiUnsupported]] if `arch` identifies a 32-bit Node target. Exposed for unit tests. */
    def checkPlatform(arch: String): Unit =
        val is32Bit = arch match
            case "ia32" | "x32" | "arm" | "mips" | "mipsel" | "ppc" | "s390" => true
            case _                                                           => false
        if is32Bit then
            val msg = FfiPlatformErrors.unsupported32BitHost(s"process.arch = $arch")
            throw new FfiUnsupported(msg)
        end if
    end checkPlatform
end NativeLoader
