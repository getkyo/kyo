package kyo.ffi.internal

import kyo.Chunk
import kyo.ffi.FfiLoadError
import kyo.internal.HostConfig
import kyo.internal.Platform
import kyo.internal.PlatformJs
import scala.scalajs.js
import scala.util.Try

/** JS NativeLoader. Resolves a native library path for koffi: the env var override, then the natives staged beside the linked program, then
  * an npm package when `kyo.ffi.js.packagePrefix` names one, then system libraries and the bare name. Rejects browsers.
  */
object NativeLoader:

    def load(libraryId: String): String =
        if detectBrowser() then
            throw new FfiLoadError.Unsupported(FfiPlatformErrors.BrowserUnsupportedLoader)
        end if
        jsResolve(libraryId)
    end load

    /** Returns `true` when the host is not Node-like (Node, Bun, or Deno with a `process` global): a browser, or any other JS host koffi
      * cannot run on. The loader reads `process` on every path, so a host without it is rejected here with a typed error instead of failing
      * later with a `ReferenceError`.
      */
    def detectBrowser(): Boolean = !Platform.isNodeLike

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
        // HostConfig reads are total: a host whose environment refuses reads (Deno without --allow-env) reads as unset.
        val env = HostConfig.env(envKey)
        if env != null then
            candidates += s"env $envKey=$env"
            if fileExists(env) then return env
        end if

        val os       = detectOs()
        val arch     = detectArch()
        val fileName = s"${libPrefix(os)}$libraryId.${osExt(os)}"

        // 2. Staged beside the linked program, where the kyo FFI plugin's `ffiWithJsNatives` copies the natives the
        // classpath carries (`kyo-ffi/native/<os>-<arch>/`, the layout a Scala.js artifact files them under). Resolved
        // against the program's own location, so it holds from any working directory. `require.resolve` is itself a
        // presence check (it throws when the file is absent), so a resolved path is genuinely present.
        val staged = s"./kyo-ffi/native/$os-$arch/$fileName"
        candidates += s"beside the linked program $staged"
        requireResolve(staged) match
            case Some(path) => return path
            case None       => ()

        // 3. An npm package carrying natives in the same per-platform layout, when `kyo.ffi.js.packagePrefix` names one.
        val packagePrefix = sys.props.get(PackagePrefixProperty)
        packagePrefix match
            case Some(prefix) =>
                val resolvePath = s"$prefix/native/$os-$arch/$fileName"
                candidates += s"require.resolve $resolvePath"
                requireResolve(resolvePath) match
                    case Some(path) => return path
                    case None       => ()
            case None => ()
        end match

        // 4. Known system libraries (libc, libm, ...) cannot be loaded by their bare name on every host:
        // the bare name "c" is not a loadable object on Linux glibc (the SONAME is `libc.so.6`), and the
        // GNU ld linker script `libc.so` is rejected by dlopen. Resolve these to koffi's process-default
        // scope instead. See `resolveSystemLib`.
        resolveSystemLib(libraryId, os) match
            case Some(resolution) => return resolution
            case None             => ()

        // 5. Bare library name, gated by an actual koffi.load probe: koffi resolves an installed system library
        // (by SONAME / default search path) here, so a name that loads is present. A name that does not load is
        // genuinely absent.
        candidates += s"""koffi.load("$libraryId")"""
        if tryKoffiLoad(libraryId) then return libraryId

        // 6. Nothing resolved: the native is not present for this runtime.
        val fromPackage = packagePrefix.fold("")(prefix => s"install the '$prefix' package for $os-$arch, ")
        throw new FfiLoadError.LibraryNotFound(
            libraryId,
            Chunk.from(candidates),
            s"Native library '$libraryId' could not be resolved on this JS runtime. Stage it beside the linked program " +
                s"under kyo-ffi/native/$os-$arch/ (the kyo FFI plugin's ffiWithJsNatives copies it there from the classpath), " +
                s"set $envKey to an absolute path, ${fromPackage}or install the '$libraryId' system library. " +
                s"Tried, in order: ${candidates.mkString("; ")}.",
            null
        )
    end jsResolve

    /** The system property naming an npm package that carries natives under `native/<os>-<arch>/`. Unset by default. */
    val PackagePrefixProperty: String = "kyo.ffi.js.packagePrefix"

    /** `true` when `path` exists on the filesystem (Node `fs.existsSync`); `false` on any error and on a host without `node:fs`. */
    private def fileExists(path: String): Boolean =
        try NodeFs.module.exists(_.existsSync(path))
        catch case _: Throwable => false

    /** `require.resolve(resolvePath)` from the linked application ([[PlatformJs.moduleRequire]]) when the path resolves to a file that
      * exists, else `None`. Node caches a resolution for the life of the process, so a path that resolved once keeps resolving after its
      * file is gone; the existence check keeps this a presence check.
      */
    private def requireResolve(resolvePath: String): Option[String] =
        Try {
            PlatformJs.moduleRequire.fold(null: String) { req =>
                val r = req.resolve(resolvePath)
                if js.isUndefined(r) || r == null then null
                else r.asInstanceOf[String]
            }
        }.toOption.flatMap(Option(_)).filter(fileExists)

    /** Probe whether koffi can load `name` (an installed system library by SONAME / default search). `false` when
      * koffi is unavailable or the load fails. Used only as the last presence gate; the caller loads for real.
      *
      * koffi comes from [[Koffi.dynamic]], the one place it is resolved, so a runtime with no koffi installed makes the
      * probe return `false`, and a runtime with koffi has its async pool configured before this first `koffi.load` locks it.
      */
    private def tryKoffiLoad(name: String): Boolean =
        Try {
            val lib = Koffi.dynamic.applyDynamic("load")(name)
            !js.isUndefined(lib) && lib != null
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

    /** The resource tag of the host operating system, the same tags the JVM loader and the plugin's layout use: musl Linux is
      * `linux-musl`, told apart from glibc Linux by its dynamic loader, because its natives are built against a different libc.
      */
    private def detectOs(): String =
        Platform.os match
            case Platform.Os.Linux =>
                if fileExists("/lib/ld-musl-x86_64.so.1") || fileExists("/lib/ld-musl-aarch64.so.1") then "linux-musl" else "linux"
            case Platform.Os.MacOS   => "darwin"
            case Platform.Os.Windows => "windows"
            case Platform.Os.BSD     => "bsd"
            case _                   => "unknown"

    /** Windows names a shared library without the `lib` prefix, as the plugin's artifacts do. */
    private def libPrefix(os: String): String = if os == "windows" then "" else "lib"

    /** The resource tag of the host architecture; an architecture with no tag keeps Node's own name for the diagnostic. */
    private def detectArch(): String =
        Platform.arch match
            case Platform.Arch.X86_64  => "x86_64"
            case Platform.Arch.Aarch64 => "aarch64"
            case _                     =>
                val raw = detectArchString()
                if raw.isEmpty then "unknown" else raw

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

    /** Node's raw `process.arch`, or an empty string on a host without it (no detection possible, so the check below lets it through). */
    private def detectArchString(): String = PlatformJs.processString("arch")

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
