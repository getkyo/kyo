package kyo.ffi.sbt

import sbt._

/** Multi-library configuration (DESIGN §3.4 / §10.1).
  *
  * Each `FfiLibrary` declares one shared library with its own set of C sources,
  * headers, link libs, flags and static-link preference. Use
  * `ffiLibraries := Seq(FfiLibrary(...), FfiLibrary(...))` when a single module
  * hosts multiple `Ffi` traits, each targeting a different native library.
  *
  * In single-library mode (the common case), leave `ffiLibraries` empty and
  * use the top-level settings (`ffiLibraryId`, `ffiCSources`, ...).
  *
  * @param id
  *   library identifier, matches the binding trait's resolved `library` and the
  *   output artifact's base name (e.g. `"kyo_tcp"` → `libkyo_tcp.so`).
  * @param cSources
  *   C source files to compile for this library.
  * @param cHeaders
  *   C header files, tracked as rebuild-trigger inputs and their parent dirs
  *   become `-I` include directories.
  * @param includeDirs
  *   extra `-I` include directories for a vendored third-party library whose
  *   headers are not tracked individually (e.g. the staged BoringSSL
  *   `include/` tree). Folded into the compile command after `cHeaders`-derived
  *   dirs and the global `ffiIncludes`.
  * @param libDirs
  *   `-L` library-search directories for the vendored archives named in
  *   `linkLibs`. On GNU ld / lld (linux) `linkLibs` are resolved via `-l<name>`
  *   inside the `-Wl,-Bstatic` window when `staticLink` is set, with each
  *   `libDir` emitted as `-L<dir>`. On darwin's ld64 (no `-Bstatic`) the
  *   library is instead linked by its full archive path
  *   (`<libDir>/lib<name>.a`), so the static fold needs no toggle. On Native,
  *   `libDirs` are surfaced as `-L<dir>` linking options so Scala Native's
  *   final clang link resolves the archives named by `linkLibs`.
  * @param linkLibs
  *   system libraries to link against on every OS (no `lib` prefix, no
  *   extension).
  * @param linkLibsByOs
  *   OS-specific link libraries, keyed by the platform name `CCompiler.detectOs`
  *   resolves (`"linux"`, `"darwin"`, `"windows"`). The `"linux"` key also
  *   covers `linux-musl`. Merged with `linkLibs` for the building OS only, so a
  *   library that links a Linux-only system lib (e.g. `uring`) leaves the macOS
  *   and Windows builds untouched. A binding whose C is header-gated to a stub
  *   on the absent OS therefore links with no dangling `-l` reference there.
  * @param cFlags
  *   additional C flags (appended to global `ffiCFlags`).
  * @param linkFlags
  *   additional linker flags (appended to global `ffiLinkFlags`).
  * @param staticLink
  *   when true, statically link third-party libs for this library.
  * @param dependsOn
  *   ids of other libraries this library depends on. Used to topologically
  *   order C compilation so a library that `#include`s another's header (or
  *   links against its symbols) is built afterwards. Unknown ids are errors;
  *   cycles are errors.
  */
final case class FfiLibrary(
    id: String,
    cSources: Seq[File],
    cHeaders: Seq[File] = Nil,
    includeDirs: Seq[File] = Nil,
    libDirs: Seq[File] = Nil,
    linkLibs: Seq[String] = Nil,
    linkLibsByOs: Map[String, Seq[String]] = Map.empty,
    cFlags: Seq[String] = Nil,
    linkFlags: Seq[String] = Nil,
    staticLink: Boolean = false,
    dependsOn: Seq[String] = Nil
) {

    /** Effective link libraries for the OS being built: the always-on `linkLibs`
      * plus the entry in `linkLibsByOs` for `os` (the value `CCompiler.detectOs`
      * returns). `linux-musl` resolves the `linux` key. Order is stable:
      * always-on libs first, then OS-specific, deduplicated.
      */
    def resolvedLinkLibs(os: String): Seq[String] = {
        val key        = if (os == "linux-musl") "linux" else os
        val osSpecific = linkLibsByOs.getOrElse(key, Nil)
        (linkLibs ++ osSpecific).distinct
    }
}
