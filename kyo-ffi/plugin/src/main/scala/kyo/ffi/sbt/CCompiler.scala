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
  *   cl.exe /LD /O2 /W3 /I<dir> <sources> /Fe:<outFile> <linkFlags> <lib>.lib
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
  * strips that suffix when copying into `META-INF/native/{os}-{arch}/`.
  */
private[sbt] object CCompiler {

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
            // cl.exe builds a DLL with /LD; /Fe: sets output exe/dll name.
            // Note: `cl` reads the command line positionally; sources + libs go after flags.
            splitCc(cc) ++ Seq("/LD") ++ translatedFlags ++ staticFlag ++ includeFlags ++
                sources.map(_.getAbsolutePath) ++
                Seq("/Fe:" + outFile.getAbsolutePath) ++
                linkFlags ++ libDirFlags ++ libFlags
        case _ =>
            val includeFlags = includes.flatMap(d => Seq("-I", d.getAbsolutePath))
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
            splitCc(cc) ++ Seq("-shared") ++ cFlags ++ includeFlags ++
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

    def compile(
        cc: String,
        cFlags: Seq[String],
        linkFlags: Seq[String],
        linkLibs: Seq[String],
        sources: Seq[File],
        libraryId: String,
        outputDir: File,
        log: Logger,
        includes: Seq[File] = Nil,
        staticLink: Boolean = false,
        libDirs: Seq[File] = Nil
    ): Seq[File] = {
        val os     = detectOs()
        val arch   = detectArch()
        val family = detectFamily(cc)
        val ext = os match {
            case "linux" | "linux-musl" => "so"
            case "darwin"               => "dylib"
            case "windows"              => "dll"
            case other                  => sys.error(s"Unsupported OS for C compilation: $other")
        }
        val prefix  = if (os == "windows") "" else "lib"
        val outName = s"$prefix$libraryId-$os-$arch.$ext"
        val outFile = new File(outputDir, outName)

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

    def detectArch(): String = sys.props("os.arch") match {
        case "amd64" | "x86_64"  => "x86_64"
        case "aarch64" | "arm64" => "aarch64"
        case other               => sys.error(s"Unsupported arch: $other")
    }
}
