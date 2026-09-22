package kyo.net.internal.backend

import kyo.*
import kyo.ffi.FfiLoadError
import scala.annotation.tailrec

/** Runs a capability probe body and turns whatever it throws into a [[CapabilityOutcome]].
  *
  * Lives in `shared` beside [[CapabilityOutcome]]: `kyo-ffi` (hence [[kyo.ffi.FfiLoadError]]) is available on all four platforms, so the
  * class-initializer unwrap this performs compiles and runs on JVM, Native, JS, and Wasm alike.
  *
  * THE CLASS-INITIALIZER UNWRAP, which is what makes the posix shim probe report the truth. A generated kyo-ffi binding impl resolves its
  * library and every method handle in its COMPANION's static initializer, and the loader raises `FfiLoadError.LibraryNotFound` from inside
  * that initializer. A throwable escaping a static initializer does not reach the caller as itself: the JVM wraps the FIRST touch in
  * `ExceptionInInitializerError(cause = ...)` and answers every touch after with a cause-less `NoClassDefFoundError`. So a plain
  * `catch case e: FfiLoadError.LibraryNotFound` never matches, and a bundled native that was never staged would classify as an opaque
  * [[CapabilityOutcome.ProbeFailed]] instead of the [[CapabilityOutcome.NotBundled]] that demotes the backend with a readable reason.
  * Unwrapping the cause is the whole mechanism.
  *
  * The per-candidate memo is what makes the unwrap sound rather than lucky: each binding class is first touched by exactly one candidate's
  * one probe (on any host only one of epoll/kqueue passes its OS gate, and io_uring touches a different binding class), so the
  * cause-carrying first touch is the touch the memo records. Scala Native does not wrap an initializer failure at all, so there the raw
  * `FfiLoadError` arrives directly; both shapes are handled.
  */
private[net] object CapabilityProbe:

    /** The `<os>-<arch>` tag a [[CapabilityOutcome.NotBundled]] names when the load error carried none, matching the tag the kyo-ffi loader
      * searches its bundled resources under ("darwin-aarch64", "linux-x86_64", ...) so a reader can map the outcome onto the path that was
      * missed.
      *
      * A tag the loader supplied is preferred over this one, which is derived without knowing the libc flavour and so cannot tell a musl
      * host from a glibc one. Diagnostic text and never a selection input, so an unresolved half reports "unknown" rather than failing the
      * probe.
      */
    val platform: String =
        val os =
            if kyo.internal.Platform.isLinux then "linux"
            else if kyo.internal.Platform.isMac then "darwin"
            else if kyo.internal.Platform.isBsd then "bsd"
            else if kyo.internal.Platform.isWindows then "windows"
            else "unknown"
        // Unsafe: reads this host's architecture, which the safe tier exposes only inside Sync while this is a val
        // on a diagnostic path. Read through the platform shim rather than `sys.props`, which carries no os.arch
        // off the JVM and Native: reading it there would name every JS and Wasm host "<os>-unknown" and send the
        // reader after an artifact published under no such name.
        import AllowUnsafe.embrace.danger
        val arch = kyo.internal.SystemPlatformSpecific.osArch() match
            case ""                  => "unknown"
            case "amd64" | "x86_64"  => "x86_64"
            case "aarch64" | "arm64" => "aarch64"
            case other               => other
        s"$os-$arch"
    end platform

    /** Run a probe body, classifying anything it throws. `libraryIds` is the running candidate's declared dependency set, used only by the
      * cause-less repeat case in [[classify]].
      */
    def run(libraryIds: Chunk[String])(body: => CapabilityOutcome): CapabilityOutcome =
        try body
        catch case t: Throwable => classify(t, libraryIds)

    /** Map a throwable raised by a probe onto the outcome that describes it, unwrapping the class-initializer wrapper first.
      *
      * The mapping is total over the FFI load errors, so every load failure reaches a case that says what happened:
      *   - `LibraryNotFound` -> [[CapabilityOutcome.NotBundled]], naming the library the loader could not resolve, when that library is one
      *     kyo delivers. A system library or koffi itself is not, so its failure is [[CapabilityOutcome.Unavailable]] carrying the loader's
      *     message, which is the only text that names the remedy.
      *   - `AbiMismatch` -> [[CapabilityOutcome.VersionTooOld]], carrying the version found and the version required.
      *   - `Unsupported` -> [[CapabilityOutcome.UnsupportedOS]], the runtime-level refusal (a 32-bit host, a browser target). The name reads
      *     as an OS gate and the refusal can be about the runtime or the word size instead, which is a slight misnomer kept deliberately:
      *     both mean "this candidate does not apply on this machine", which is exactly what the selection and the report do with it.
      *   - `ImplNotFound` -> [[CapabilityOutcome.ProbeFailed]]. A missing generated impl is a build problem, not a host degrade, so it stays
      *     in the unclassified bucket where it reads as the anomaly it is.
      *
      * A remaining `LinkageError` is the already-poisoned repeat: the binding class failed its initialization earlier and the runtime no
      * longer carries the cause. There is nothing left to read, so it is reported against the candidate's own bundled native, the id declared
      * LAST in `libraryIds`. Reaching this case at all means a class was first touched outside a memoized probe; the honest answer is still
      * that the binding did not link, which demotes the candidate rather than surfacing an opaque failure.
      */
    def classify(thrown: Throwable, libraryIds: Chunk[String]): CapabilityOutcome =
        unwrapClassInit(thrown) match
            // Neither of these is a native kyo delivers, so NotBundled's remedy (a native path or package) cannot
            // fix them. A system library resolves from the process's own symbol scope, so what failed is a SYMBOL
            // the binding declared and the loader's message is the only place naming which one. koffi is the
            // application's npm dependency, and its loader message names the install that provides it.
            case e: FfiLoadError.LibraryNotFound
                if kyo.ffi.internal.SystemLibraries.isSystem(e.libraryId) || e.libraryId == kyo.ffi.internal.KoffiRuntime.LibraryId =>
                CapabilityOutcome.Unavailable(loaderReason(e))
            // The loader's own tag wins where it supplied one: it searched under it, and it is the only side that
            // knows the libc flavour, so a musl host reads `linux-musl-x86_64` here instead of the glibc tag this
            // object would derive and the glibc artifact that tag would recommend.
            case e: FfiLoadError.LibraryNotFound =>
                CapabilityOutcome.NotBundled(e.libraryId, if e.platformTag.nonEmpty then e.platformTag else platform)
            case e: FfiLoadError.AbiMismatch  => CapabilityOutcome.VersionTooOld(e.actual, e.expected)
            case _: FfiLoadError.Unsupported  => CapabilityOutcome.UnsupportedOS
            case e: FfiLoadError.ImplNotFound => CapabilityOutcome.ProbeFailed(e)
            case _: LinkageError              => CapabilityOutcome.NotBundled(libraryIds.lastMaybe.getOrElse("unknown"), platform)
            case other                        => CapabilityOutcome.ProbeFailed(other)

    /** The loader's message, followed by the first line of its cause when it has one.
      *
      * The cause is what separates a koffi that is not installed from one that is installed and fails to load, a prebuilt addon against
      * the wrong libc, and the loader's own message reads the same for both. Only the first line, because the reason lands in a one-line
      * report and a `require` failure carries its whole resolution stack.
      */
    private def loaderReason(e: FfiLoadError.LibraryNotFound): String =
        val cause = e.getCause
        val line  =
            if cause == null || cause.getMessage == null then ""
            else cause.getMessage.linesIterator.map(_.trim).find(_.nonEmpty).getOrElse("")
        if line.isEmpty then e.getMessage else s"${e.getMessage} ($line)"
    end loaderReason

    /** Peel the class-initialization wrappers off a throwable so the declared load error underneath is reachable. Recursive because a
      * binding whose initializer touches another binding produces a chain of them.
      */
    @tailrec
    private def unwrapClassInit(thrown: Throwable): Throwable =
        thrown match
            case e: LinkageError if e.getCause != null && !(e.getCause eq e) => unwrapClassInit(e.getCause)
            case other                                                       => other

end CapabilityProbe
