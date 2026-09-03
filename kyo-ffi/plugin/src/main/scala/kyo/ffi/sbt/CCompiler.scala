package kyo.ffi.sbt

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import sbt.util.Logger
import sys.process._

/** Driver for invoking a C compiler to produce a platform-native shared library.
  *
  * Supports four compiler families, detected from the `cc` command:
  *   - `cl.exe` (contains `cl.exe` or ends in `/cl` or `\cl`) → MSVC (Windows).
  *   - `zig cc` (contains `zig`) → Zig's drop-in cc (gcc/clang-compatible flags).
  *   - clang (contains `clang`) → Clang.
  *   - otherwise → GCC (the safe default for `cc`).
  *
  * For POSIX-style compilers (gcc / clang / zig) the flag shape is:
  * {{{
  *   cc -shared -O2 -fPIC -Wall -I<dir> <sources> -o <outFile> <linkFlags> -l<lib>
  * }}}
  *
  * For MSVC the flag shape is:
  * {{{
  *   cl.exe /LD /O2 /W3 /I<dir> <sources> /Fo:<objectDir> /Fe:<outFile> <linkFlags> <lib>.lib
  * }}}
  *
  * When `staticLink` is true the named `linkLibs` are folded statically into the produced
  * shared library (so the artifact carries no runtime dependency on them) while libc and
  * other implicit libraries stay dynamic:
  *   - gcc / clang / zig: wrap the libs in `-Wl,-Bstatic <libs> -Wl,-Bdynamic` (the GNU ld /
  *     lld static toggle). A bare `-static` is NOT used: it forces libc.a into the `-shared`
  *     object and GNU ld then fails on `__fini_array_*` / `_dl_debug_state`.
  *   - MSVC: link the named `.lib` and add `/MT` to statically link the CRT.
  *   - With no `linkLibs`, `staticLink` is a no-op (nothing to fold). darwin's ld64 has no
  *     `-Bstatic`; the shims that use `staticLink` declare their static libs only on linux,
  *     so this toggle is only ever emitted under GNU ld / lld.
  *
  * The output filename embeds os+arch for unambiguous CI artifact naming; `Packager`
  * strips that suffix when copying into `META-INF/native/{os}-{arch}/`. That os/arch pair is the
  * BUILD TARGET (`ffiTargetOsArch`, defaulting to the host), threaded in as a parameter: this
  * object owns the target resolver and the artifact-naming vocabulary every producer reads, so a
  * cross-built artifact is named and packaged for the platform it actually runs on.
  */
private[sbt] object CCompiler {

    /** The OS tags the plugin compiles for. Shared with `NativeLoader.Os.tagName` (kyo-ffi/jvm),
      * which resolves the packaged resource path at runtime; keep the two in sync.
      */
    val supportedOs: Seq[String] = Seq("linux", "linux-musl", "darwin", "windows")

    /** The CPU-architecture tags the plugin compiles for. Shared with `NativeLoader.Arch.tagName`. */
    val supportedArch: Seq[String] = Seq("x86_64", "aarch64")

    /** Every `<os>-<arch>` tag an artifact can be named for. */
    val supportedOsArchTags: Seq[String] =
        for {
            os   <- supportedOs
            arch <- supportedArch
        } yield s"$os-$arch"

    sealed trait Family
    case object Gcc   extends Family
    case object Clang extends Family
    case object Msvc  extends Family
    case object ZigCc extends Family

    def detectFamily(cc: String): Family = {
        val lower = cc.toLowerCase
        if (lower.contains("cl.exe") || lower.endsWith("/cl") || lower.endsWith("\\cl") || lower == "cl") Msvc
        else if (lower.contains("zig")) ZigCc
        else if (lower.contains("clang")) Clang
        else Gcc
    }

    /** Link-lib flags for a POSIX (gcc/clang/zig) link. When `staticLink` is set and there
      * are libs to fold, wrap them in the GNU ld / lld static toggle so just those archives
      * are linked statically while libc + implicit libs stay dynamic. With no libs, or when
      * not static, emits plain `-l` flags. Shared by the shared-lib link (`buildCommand`)
      * and the Scala Native archive link (`ffiNativeLinkingOptions`).
      */
    def foldedLinkLibFlags(linkLibs: Seq[String], staticLink: Boolean): Seq[String] =
        if (staticLink && linkLibs.nonEmpty)
            Seq("-Wl,-Bstatic") ++ linkLibs.map(l => s"-l$l") ++ Seq("-Wl,-Bdynamic")
        else
            linkLibs.map(l => s"-l$l")

    /** Link flags for a vendored third-party static archive search path (POSIX gcc / clang / zig).
      *
      * Differs from `foldedLinkLibFlags` in that the archives live under explicit `libDirs`
      * (a staged `-L` tree) rather than on the default library path, and the GNU vs ld64 static
      * toggle is OS-dependent:
      *   - linux / other GNU ld / lld: emit `-L<dir>` for each `libDir`, then fold the named
      *     libs in the `-Wl,-Bstatic … -Wl,-Bdynamic` window so just those archives go static
      *     while libc stays dynamic (same toggle as `foldedLinkLibFlags`).
      *   - darwin (ld64): there is NO `-Bstatic`. A `-L<dir> -l<name>` there prefers a `.dylib`
      *     over the `.a` and would link dynamically, so each lib is linked by its full archive
      *     path `<libDir>/lib<name>.a` instead. The first existing libDir wins per lib; ld64
      *     pulls only the referenced objects out of the archive.
      *
      * When `staticLink` is false, or there are no libs, this is the same plain `-L<dir>` +
      * `-l<name>` shape on every OS. Used by `buildCommand` (the shared-lib link) and
      * `ffiNativeLinkingOptions` (Scala Native's final archive link).
      */
    def vendoredArchiveLinkFlags(
        libDirs: Seq[File],
        linkLibs: Seq[String],
        staticLink: Boolean,
        os: String
    ): Seq[String] = {
        if (linkLibs.isEmpty) return Nil
        val isDarwin = os == "darwin"
        if (staticLink && isDarwin) {
            // ld64: link each archive by full path. No -L / -Bstatic; the `.a` path is explicit.
            linkLibs.map { lib =>
                val archiveName = s"lib$lib.a"
                val resolved    = libDirs.map(d => new File(d, archiveName)).find(_.exists())
                resolved.map(_.getAbsolutePath).getOrElse {
                    // Not yet staged (e.g. command dumped before build-boringssl ran): fall back
                    // to the first declared libDir so the emitted command still names a concrete
                    // path. The link fails loudly at compile time if the archive is truly absent.
                    libDirs.headOption.map(d => new File(d, archiveName).getAbsolutePath).getOrElse(archiveName)
                }
            }
        } else {
            val searchFlags = libDirs.map(d => s"-L${d.getAbsolutePath}")
            searchFlags ++ foldedLinkLibFlags(linkLibs, staticLink)
        }
    }

    // Vendored static-archive link flags for the Scala Native final link, where the bundled C objects are placed AFTER
    // nativeConfig.linkingOptions on the clang command. GNU ld and ld64 are single-pass over archives, so an archive that precedes the
    // object referencing it has its members discarded as unreferenced and the link fails with "undefined reference" for every SSL_/crypto_
    // symbol the shim calls. Force-loading the archives makes the link order-independent: every member is pulled in regardless of position.
    //   - linux / GNU ld / lld: -L<dir> plus -Wl,--whole-archive -l<name> ... -Wl,--no-whole-archive.
    //   - darwin / ld64 (no --whole-archive): -Wl,-force_load,<libDir>/lib<name>.a per archive (full path, first existing libDir wins).
    // staticLink=false or no libs falls back to the plain vendoredArchiveLinkFlags shape (nothing to force-load).
    def vendoredArchiveForceLoadFlags(
        libDirs: Seq[File],
        linkLibs: Seq[String],
        staticLink: Boolean,
        os: String
    ): Seq[String] = {
        if (linkLibs.isEmpty || !staticLink) {
            vendoredArchiveLinkFlags(libDirs, linkLibs, staticLink, os)
        } else if (os == "darwin") {
            linkLibs.map { lib =>
                val archiveName = s"lib$lib.a"
                val resolved    = libDirs.map(d => new File(d, archiveName)).find(_.exists())
                val path = resolved.map(_.getAbsolutePath).getOrElse(
                    libDirs.headOption.map(d => new File(d, archiveName).getAbsolutePath).getOrElse(archiveName)
                )
                s"-Wl,-force_load,$path"
            }
        } else {
            val searchFlags = libDirs.map(d => s"-L${d.getAbsolutePath}")
            searchFlags ++ (Seq("-Wl,--whole-archive") ++ linkLibs.map(l => s"-l$l") ++ Seq("-Wl,--no-whole-archive"))
        }
    }

    /** Build the full command line (for either POSIX-style or MSVC). Pure, no IO.
      *
      * `libDirs` and `os` support vendored third-party static archives (e.g. staged
      * BoringSSL): when `libDirs` is non-empty the link routes through
      * `vendoredArchiveLinkFlags`, which on darwin links each archive by full path
      * (`<libDir>/lib<name>.a`, ld64 has no `-Bstatic`) and on linux emits `-L<dir>` plus the
      * `-Wl,-Bstatic … -Wl,-Bdynamic` fold. With an empty `libDirs` (the io_uring case) the
      * link keeps the original `foldedLinkLibFlags` shape unchanged. `includeDirs` add extra
      * `-I` dirs for the vendored headers (the staged `include/` tree).
      */
    def buildCommand(
        cc: String,
        family: Family,
        cFlags: Seq[String],
        linkFlags: Seq[String],
        linkLibs: Seq[String],
        sources: Seq[File],
        includes: Seq[File],
        outFile: File,
        staticLink: Boolean,
        libDirs: Seq[File] = Nil,
        os: String = ""
    ): Seq[String] = family match {
        case Msvc =>
            val translatedFlags = cFlags.flatMap(translateFlagMsvc)
            val includeFlags    = includes.map(d => "/I" + d.getAbsolutePath)
            val staticFlag      = if (staticLink) Seq("/MT") else Nil
            val libDirFlags     = libDirs.map(d => "/LIBPATH:" + d.getAbsolutePath)
            val libFlags        = linkLibs.map(l => l + ".lib")
            val objectDirFlag   = "/Fo:" + outFile.getAbsoluteFile.getParentFile.getAbsolutePath + File.separator
            // cl.exe builds a DLL with /LD; /Fo: keeps intermediate objects beside the target DLL and
            // /Fe: sets its name. `/LIBPATH:` is a linker option: cl silently ignores it on the compiler
            // command line, so the search dirs and the named import libs must follow `/link`, otherwise
            // the linker cannot find a vendored .lib (LNK1181). The libs found via the LIB env (winsock,
            // the CRT) resolve either way.
            val linkerArgs = linkFlags ++ libDirFlags ++ libFlags
            splitCc(cc) ++ Seq("/LD") ++ translatedFlags ++ staticFlag ++ includeFlags ++
                sources.map(_.getAbsolutePath) ++
                Seq(objectDirFlag, "/Fe:" + outFile.getAbsolutePath) ++
                (if (linkerArgs.nonEmpty) Seq("/link") ++ linkerArgs else Nil)
        case _ =>
            val includeFlags = includes.flatMap(d => Seq("-I", d.getAbsolutePath))
            // A Windows DLL has no PIC, and clang targeting *-windows-msvc REJECTS -fPIC rather than
            // ignoring it, so a gcc-style compile for Windows has to drop it the way translateFlagMsvc
            // already does for cl. This is reachable because the windows-arm64 producer compiles with
            // clang: the image's MinGW gcc emits x64 objects and cannot serve that pole.
            val targetCFlags =
                if (os == "windows") cFlags.filterNot(_ == "-fPIC") else cFlags
            // staticLink folds the named libs into the .so via the GNU ld / lld static toggle,
            // leaving libc + implicit libraries dynamic. A bare `-static` is invalid here: it
            // pulls libc.a into a `-shared` link and ld fails on `__fini_array_*`. With no
            // linkLibs there is nothing to fold, so staticLink is a no-op.
            //
            // When the libs come from a vendored `libDirs` tree (BoringSSL), route through
            // `vendoredArchiveLinkFlags`: on darwin link each `.a` by full path (no -Bstatic);
            // on linux emit `-L<dir>` + the `-Bstatic` fold.
            val linkLibFlags =
                if (libDirs.nonEmpty) vendoredArchiveLinkFlags(libDirs, linkLibs, staticLink, os)
                else foldedLinkLibFlags(linkLibs, staticLink)
            // linkFlags carry the dynamic C++ runtime a vendored C++ archive references (e.g. BoringSSL's
            // -lstdc++ / -lc++). GNU ld resolves -l references left-to-right, so the C++ runtime MUST come
            // AFTER the static archives that need it; placing it before leaves the archives' C++ symbols
            // (std::bad_variant_access, __cxa_*, vtables) undefined and the loadable lib fails to dlopen.
            // The Native archive link (ffiNativeLinkingOptions) already appends linkFlags after the
            // archives; this matches that order. linkFlags is empty for every other library, so the order
            // is a no-op there.
            splitCc(cc) ++ Seq("-shared") ++ targetCFlags ++ includeFlags ++
                sources.map(_.getAbsolutePath) ++
                Seq("-o", outFile.getAbsolutePath) ++
                linkLibFlags ++ linkFlags
    }

    /** Translate a gcc/clang-style flag to its MSVC equivalent. Unknown flags pass
      * through unchanged. Returns a Seq so a single source flag may expand to 0 or more.
      */
    def translateFlagMsvc(flag: String): Seq[String] = flag match {
        case "-shared"                               => Seq("/LD")
        case "-fPIC"                                 => Nil // PIC is irrelevant on Windows DLLs
        case "-O0"                                   => Seq("/Od")
        case "-O1"                                   => Seq("/O1")
        case "-O2"                                   => Seq("/O2")
        case "-O3"                                   => Seq("/O2")
        case "-Wall"                                 => Seq("/W3")
        case "-Wextra"                               => Seq("/W4")
        case f if f.startsWith("-I") && f.length > 2 => Seq("/I" + f.substring(2))
        case f if f.startsWith("-l") && f.length > 2 => Seq(f.substring(2) + ".lib")
        case other                                   => Seq(other)
    }

    /** Split a compiler command-line-ish setting into its argv. Supports `"zig cc"`
      * (two tokens) and single-command variants like `/usr/local/bin/gcc`.
      */
    private[sbt] def splitCc(cc: String): Seq[String] = {
        val trimmed = cc.trim
        if (trimmed.isEmpty) Seq("cc")
        else trimmed.split("\\s+").toSeq
    }

    /** Shared-library filename extension for a target OS. The one os→ext mapping in the plugin:
      * `compile`, `ffiDumpCcCommand` and the artifact-name parser all read it here, so the
      * diagnostic task can never disagree with what the compile actually produces (it used to
      * hard-error on `linux-musl`).
      */
    def libExtension(os: String): String = os match {
        case "linux" | "linux-musl" => "so"
        case "darwin"               => "dylib"
        case "windows"              => "dll"
        case other                  => sys.error(s"Unsupported OS for C compilation: $other")
    }

    /** Shared-library filename prefix for a target OS: `lib` everywhere but Windows. */
    def libPrefix(os: String): String = if (os == "windows") "" else "lib"

    /** The compile-output filename for `libraryId` on a target os/arch:
      * `lib<id>-<os>-<arch>.<ext>` (POSIX) or `<id>-<os>-<arch>.dll` (Windows). `Packager` strips
      * the `-<os>-<arch>` suffix when staging into the resource layout.
      */
    def artifactName(libraryId: String, os: String, arch: String): String =
        s"${libPrefix(os)}$libraryId-$os-$arch.${libExtension(os)}"

    /** Split an `<os>-<arch>` tag into its parts, at the LAST hyphen because the OS itself carries
      * one (`linux-musl-x86_64` is `linux-musl` + `x86_64`). A tag outside the supported matrix is a
      * hard error: naming an artifact for a platform no runtime looks up is the silent failure this
      * whole path exists to prevent.
      */
    def parseOsArch(tag: String): (String, String) = {
        val cut = tag.lastIndexOf('-')
        val parsed =
            if (cut <= 0) None
            else {
                val os   = tag.substring(0, cut)
                val arch = tag.substring(cut + 1)
                if (supportedOs.contains(os) && supportedArch.contains(arch)) Some((os, arch)) else None
            }
        parsed.getOrElse(
            sys.error(s"[kyo-ffi-plugin] Unsupported os-arch '$tag'. Supported: ${supportedOsArchTags.mkString(", ")}.")
        )
    }

    /** Resolve the `(os, arch)` a build produces natives for: the explicit `<os>-<arch>` override
      * when set (`ffiTargetOsArch`), otherwise the build host. Every producer reads the target
      * through here (the output filename, the packaged resource directory, the stripped suffix, the
      * OS-specific link libs), so an unset build behaves exactly as the host-only build did and an
      * override moves all of them together.
      */
    def resolveTargetOsArch(explicit: Option[String]): (String, String) =
        explicit match {
            case Some(tag) => parseOsArch(tag)
            case None      => (detectOs(), detectArch())
        }

    /** Split a filename produced by `artifactName` back into `(libraryId, os, arch)`. `None` when
      * the name carries no recognized `<os>-<arch>` suffix, or carries one whose extension does not
      * match its OS. Used to attribute a staged prebuilt (`ffiPrebuiltDir`) to its library and to
      * its own platform, rather than to the build host's.
      */
    def parseArtifactName(name: String): Option[(String, String, String)] = {
        val dot = name.lastIndexOf('.')
        if (dot < 0) None
        else {
            val base = name.substring(0, dot)
            val ext  = name.substring(dot + 1)
            // Longest tag first: `linux-musl-x86_64` must win over a shorter suffix match.
            supportedOsArchTags.sortBy(-_.length).find(tag => base.endsWith("-" + tag)).flatMap { tag =>
                val (os, arch) = parseOsArch(tag)
                val stem       = base.substring(0, base.length - tag.length - 1)
                val prefix     = libPrefix(os)
                if (ext != libExtension(os)) None
                else if (!stem.startsWith(prefix) || stem.length == prefix.length) None
                else Some((stem.substring(prefix.length), os, arch))
            }
        }
    }

    /** Compile `sources` into a shared library for the target `(os, arch)`.
      *
      * `os` / `arch` are the BUILD TARGET, not the host: they decide the output filename, its
      * extension, and the link shape (`buildCommand`'s darwin-vs-GNU-ld archive handling). The
      * caller resolves them through `resolveTargetOsArch`, so an unset `ffiTargetOsArch` compiles
      * for the host exactly as before. Making the target foreign does NOT by itself make the
      * compiler emit foreign code: the toolchain flags for that (e.g. `-arch x86_64` on darwin)
      * come from `ffiCFlags`.
      */
    def compile(
        cc: String,
        cFlags: Seq[String],
        linkFlags: Seq[String],
        linkLibs: Seq[String],
        sources: Seq[File],
        libraryId: String,
        os: String,
        arch: String,
        outputDir: File,
        log: Logger,
        includes: Seq[File] = Nil,
        staticLink: Boolean = false,
        libDirs: Seq[File] = Nil
    ): Seq[File] = {
        val family  = detectFamily(cc)
        val outFile = new File(outputDir, artifactName(libraryId, os, arch))

        val cmd = buildCommand(
            cc = cc,
            family = family,
            cFlags = cFlags,
            linkFlags = linkFlags,
            linkLibs = linkLibs,
            sources = sources,
            includes = includes,
            outFile = outFile,
            staticLink = staticLink,
            libDirs = libDirs,
            os = os
        )
        log.info(s"[kyo-ffi-plugin] ${cmd.mkString(" ")}")
        val exitCode = Process(cmd).!
        if (exitCode != 0) sys.error(s"[kyo-ffi-plugin] C compilation failed (exit=$exitCode)")
        Seq(outFile)
    }

    /** The BUILD HOST's OS tag. Reached only through `resolveTargetOsArch` on the producing paths,
      * which is what makes the host a default rather than an assumption baked into each producer.
      */
    def detectOs(): String = detectOsWith(sys.props("os.name"), p => Files.exists(Paths.get(p)))

    /** Test-visible OS detection, takes the raw `os.name` and a predicate for filesystem probing so
      * musl detection can be unit-tested without actually running on Alpine. Mirrors the runtime
      * detection in `NativeLoader.detectOs` (kyo-ffi/jvm); keep the two in sync.
      */
    private[sbt] def detectOsWith(osName: String, fileExists: String => Boolean): String = {
        val name = osName.toLowerCase
        if (name.contains("mac")) "darwin"
        else if (name.contains("linux")) {
            // Probe for musl libc loader, Alpine and other musl-based distros ship one of
            // `/lib/ld-musl-x86_64.so.1` / `/lib/ld-musl-aarch64.so.1`. Matches the Panama
            // runtime loader so packaged libraries land in the same resource subtree.
            if (fileExists("/lib/ld-musl-x86_64.so.1") || fileExists("/lib/ld-musl-aarch64.so.1")) "linux-musl"
            else "linux"
        } else if (name.contains("windows")) "windows"
        else sys.error(s"Unsupported OS: $name")
    }

    /** The BUILD HOST's CPU-architecture tag (see `detectOs`). */
    def detectArch(): String = sys.props("os.arch") match {
        case "amd64" | "x86_64"  => "x86_64"
        case "aarch64" | "arm64" => "aarch64"
        case other               => sys.error(s"Unsupported arch: $other")
    }
}
