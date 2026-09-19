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
  *   OS-specific link libraries, keyed by the resolved TARGET platform name
  *   (`ffiTargetOsArch`, defaulting to the host `CCompiler.detectOs`:
  *   `"linux"`, `"darwin"`, `"windows"`). The `"linux"` key also
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
  * @param osTargets
  *   the OS names whose shared library this library is built and bundled for;
  *   empty (the default) means every OS. A binding that only exists to be called
  *   on one OS names it here, so a build on any other host neither compiles a
  *   shared library for it nor claims it in the native manifest. Without it, C
  *   that is `#ifdef`-guarded to same-signature stubs off its OS still compiles
  *   everywhere, and the build ships a working-looking artifact for a platform
  *   that can never call it while the platform that can may have none at all.
  *
  *   Entries are EXACT `CCompiler.supportedOs` names and are validated against
  *   that list: `"linux"` and `"linux-musl"` are separate targets, and naming
  *   only `"linux"` excludes musl. They are separate everywhere else in the
  *   system (the artifact tags, the staging directory names, `NativeLoader.Os`,
  *   the state and native manifests), and folding them together here would make
  *   every `Seq("linux")` declaration silently claim a musl support its author
  *   never attested. `linkLibsByOs` and `compilerByOs` still fold, because a musl
  *   toolchain genuinely is "linux" for link-flag purposes.
  *
  *   Scala Native is unaffected: it compiles every declared C source into the
  *   binary on every OS, which is what keeps the stub symbols resolvable there.
  * @param osArchTargets
  *   the os-arch tags this library is built and bundled for, narrowing
  *   `osTargets` where availability splits WITHIN an OS; empty (the default)
  *   means every arch of every OS `osTargets` allows. Both are needed because
  *   they answer different questions: `osTargets` is about C that only means
  *   something on one OS, while this is about a third-party library that exists
  *   for some architectures of an OS and not others.
  *
  *   `kyo_doltlite` is the case it was added for. DoltLite publishes a win-x64
  *   library and no win-arm64 one, and its build is autoconf driven through
  *   MSYS2/MinGW, which the Windows-on-ARM image has no native toolchain for
  *   (its MinGW gcc emits x86_64, and MSVC cannot drive the autoconf build).
  *   Declaring `Seq("windows")` in `osTargets` would have dropped the working
  *   x64 native along with the impossible ARM one.
  *
  *   Entries are EXACT `CCompiler.supportedOsArchTags` names, validated the same
  *   way `osTargets` is and for the same reason: a typo would make the library
  *   vanish from every platform rather than fail.
  * @param dependsOn
  *   ids of other libraries this library depends on. Used to topologically
  *   order C compilation so a library that `#include`s another's header (or
  *   links against its symbols) is built afterwards. Unknown ids are errors;
  *   cycles are errors.
  * @param system
  *   the library a Scala Native consumer's machine may provide for this one's C, published with the Native
  *   artifact and resolved in the consumer's build (see [[FfiSystemLibrary]]). Independent of `linkLibs`, which
  *   say what THIS build links: an unstaged or header-less build host must not decide what a consumer can use.
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
    dependsOn: Seq[String] = Nil,
    compilerByOs: Map[String, String] = Map.empty,
    osTargets: Seq[String] = Nil,
    osArchTargets: Seq[String] = Nil,
    system: Option[FfiSystemLibrary] = None
) {

    /** Whether this library's shared library is built and bundled on `os` (the resolved TARGET os).
      * True for every OS when `osTargets` is empty, which is the default.
      *
      * Matching is EXACT: `linux-musl` does NOT resolve the `linux` key. A library that builds on
      * both names both. See `osTargets` for why the two are kept distinct here while `linkLibsByOs`
      * and `compilerByOs` still fold musl onto `linux`.
      */
    def buildsOn(os: String): Boolean =
        osTargets.isEmpty || osTargets.contains(os)

    /** Whether this library is built and bundled for the full os-arch tag `osArch`.
      *
      * The predicate to ask wherever the arch is known, which is everywhere a target is resolved:
      * `buildsOn` alone answers for the OS and cannot see a library that exists for one arch of an OS
      * and not another. Both narrow, so a tag has to clear `osTargets` and `osArchTargets` alike.
      */
    def buildsOnTarget(osArch: String): Boolean =
        buildsOn(CCompiler.parseOsArch(osArch)._1) &&
            (osArchTargets.isEmpty || osArchTargets.contains(osArch))

    /** The `osTargets` entries that are not `CCompiler.supportedOs` names. A typo makes `buildsOn`
      * false on every OS, which is silent: the library is skipped everywhere, recorded `absent` in
      * every manifest, and required of nothing by the release guard. Callers validate at resolution
      * time so the mistake is an error instead of a disappearance.
      */
    def unknownOsTargets: Seq[String] =
        osTargets.filterNot(CCompiler.supportedOs.contains)

    /** The `osArchTargets` entries that are not `CCompiler.supportedOsArchTags` names, validated by the
      * same callers and for the same reason as [[unknownOsTargets]].
      */
    def unknownOsArchTargets: Seq[String] =
        osArchTargets.filterNot(CCompiler.supportedOsArchTags.contains)

    /** Effective link libraries for the OS being built: the always-on `linkLibs`
      * plus the entry in `linkLibsByOs` for `os` (the resolved TARGET os,
      * `ffiTargetOsArch` defaulting to the host `CCompiler.detectOs`).
      * `linux-musl` resolves the `linux` key. Order is stable:
      * always-on libs first, then OS-specific, deduplicated.
      */
    def resolvedLinkLibs(os: String): Seq[String] = {
        val key        = if (os == "linux-musl") "linux" else os
        val osSpecific = linkLibsByOs.getOrElse(key, Nil)
        (linkLibs ++ osSpecific).distinct
    }

    /** The preprocessor macro that tells this library's C its link libraries are on the link:
      * `KYO_FFI_LINKED_<ID>`, the id upper-cased with every other character as `_` (the spelling
      * `KYO_FFI_<ID>_PATH` uses). A shim gates the code that calls into an external library on it and
      * compiles a stub in its `#else`.
      *
      * The gate cannot be header presence. On Scala Native the C ships as source and compiles in the
      * consumer's build, where `__has_include(<openssl/ssl.h>)` answers yes on any machine with the
      * headers while nothing puts `-lssl` on that link: the binary then fails to link on symbols the
      * consumer never wrote. The macro is emitted by the same build that emits the link flags, so the
      * two cannot disagree, and a build that emits neither compiles the stub and links.
      */
    def linkedDefine: String = FfiLibrary.linkedDefineFor(id)

    /** `-D<linkedDefine>` when this library declares link libraries for `os`, empty otherwise. */
    def linkedDefineFlags(os: String): Seq[String] =
        if (resolvedLinkLibs(os).nonEmpty) Seq(s"-D$linkedDefine") else Nil

    /** The C compiler this library requires for the OS being built, overriding the global
      * `ffiCCompiler` for that OS only. `linux-musl` resolves the `linux` key. Absent (the default,
      * empty map) means use the global compiler, so a library that does not set `compilerByOs`
      * behaves exactly as before on every OS. A vendored library that only builds under a specific
      * toolchain (e.g. Aeron, which supports Windows only under MSVC) names it here for that OS.
      */
    def compilerFor(os: String): Option[String] = {
        val key = if (os == "linux-musl") "linux" else os
        compilerByOs.get(key)
    }
}

object FfiLibrary {

    /** [[FfiLibrary.linkedDefine]] for a library known only by `id`, as a consumer reading a published declaration knows it. */
    def linkedDefineFor(id: String): String =
        "KYO_FFI_LINKED_" + id.map(c => if (c.isLetterOrDigit) c.toUpper else '_')
}
