package kyo.ffi.internal

/** The single source of truth for which library ids are OS system libraries whose ABSENCE from the bundled native
  * layout is expected, not a failure.
  *
  * A binding over `library = "c"` (libc) resolves its symbols through the platform linker's process-default scope
  * (the JVM `Linker.nativeLinker().defaultLookup()`, the JS koffi `RTLD_DEFAULT` binding), so no
  * `META-INF/native/<os>-<arch>/lib<id>.<ext>` resource is ever packaged for it. The manifest-driven direct-load
  * pre-check MUST classify these ids identically on the JVM and JS loaders: a system id is never "must be bundled",
  * so the pre-check reports no `LibraryNotFound` for it. Before this object the set was duplicated in the JVM
  * loader's `isSystemLibrary` and the JS loader's `resolveSystemLib`, which could drift and make the two loaders
  * disagree on what counts as "absence expected".
  *
  * The set is `c` (libc), `m` (libm), and the historically-separate `pthread` / `dl` / `rt`, all of which modern
  * glibc, musl, and macOS libSystem fold into the default scope. The per-OS RESOLUTION of a system id (a `null`
  * default-scope handle on POSIX, `ucrtbase.dll` on Windows) stays in each loader; only the CLASSIFICATION lives
  * here so both loaders agree.
  */
object SystemLibraries:

    /** Library ids resolved from the process-default symbol scope on every supported platform. */
    val ids: Set[String] = Set("c", "m", "pthread", "dl", "rt")

    /** `true` when `libraryId` is an OS system library whose bundled-native absence is expected, not an error. */
    def isSystem(libraryId: String): Boolean = ids.contains(libraryId)
end SystemLibraries
