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

    /** The `<os>-<arch>` tag a [[CapabilityOutcome.NotBundled]] names, matching the tag the kyo-ffi loader searches its bundled resources
      * under ("darwin-aarch64", "linux-x86_64", ...) so a reader can map the outcome onto the path that was missed. `os.arch` is read through
      * `sys.props`, which resolves on the JVM and on Scala Native's property table; a runtime that publishes neither reports "unknown" rather
      * than failing the probe, since this is diagnostic text and never a selection input.
      */
    val platform: String =
        val os =
            if kyo.internal.Platform.isLinux then "linux"
            else if kyo.internal.Platform.isMac then "darwin"
            else if kyo.internal.Platform.isBsd then "bsd"
            else if kyo.internal.Platform.isWindows then "windows"
            else "unknown"
        val arch = sys.props.getOrElse("os.arch", "unknown") match
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
      *   - `LibraryNotFound` -> [[CapabilityOutcome.NotBundled]], naming the library the loader could not resolve.
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
            case e: FfiLoadError.LibraryNotFound => CapabilityOutcome.NotBundled(e.libraryId, platform)
            case e: FfiLoadError.AbiMismatch     => CapabilityOutcome.VersionTooOld(e.actual, e.expected)
            case _: FfiLoadError.Unsupported     => CapabilityOutcome.UnsupportedOS
            case e: FfiLoadError.ImplNotFound    => CapabilityOutcome.ProbeFailed(e)
            case _: LinkageError                 => CapabilityOutcome.NotBundled(libraryIds.lastMaybe.getOrElse("unknown"), platform)
            case other                           => CapabilityOutcome.ProbeFailed(other)

    /** Peel the class-initialization wrappers off a throwable so the declared load error underneath is reachable. Recursive because a
      * binding whose initializer touches another binding produces a chain of them.
      */
    @tailrec
    private def unwrapClassInit(thrown: Throwable): Throwable =
        thrown match
            case e: LinkageError if e.getCause != null && !(e.getCause eq e) => unwrapClassInit(e.getCause)
            case other                                                       => other

end CapabilityProbe
