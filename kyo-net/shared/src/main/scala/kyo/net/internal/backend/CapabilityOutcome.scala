package kyo.net.internal.backend

import kyo.net.NetException

/** What a candidate's capability probe found: whether the host can provide the capability, and when it cannot, WHY.
  *
  * This replaces the `Boolean` availability channel the registries carried before. That channel ran every probe under a
  * `catch case _: Throwable => false`, so the reason was discarded at the moment it was known: a backend that demoted because its bundled
  * native was never staged for this host looked exactly like one that demoted because the kernel is too old, and both looked exactly like a
  * backend that does not apply to this OS at all. Selection still needs only "can it serve", which is [[isAvailable]]; every other consumer
  * (the demotion warning, the selection report, the terminal exception's cause) needs the reason, which is the rest of this type.
  *
  * Lives in the SHARED source set and names no FFI type on purpose: kyo-net depends on kyo-ffi from its JVM and Native configurations only,
  * so the selection identity every platform shares must compile on JS and Wasm, where `kyo.ffi` does not exist. The translation from
  * `kyo.ffi.FfiLoadError` into these cases therefore lives in the `jvm-native` `CapabilityProbe`, the only source set that has that
  * hierarchy.
  */
private[net] enum CapabilityOutcome derives CanEqual:

    /** The probe ran clean and the candidate can serve on this host. The only outcome selection accepts. */
    case Available

    /** The candidate's OS or runtime gate does not apply here: epoll on macOS, kqueue on Linux, an FFI binding on a browser target. Benign
      * and expected, so it is recorded in the report but is never an error on its own; it becomes terminal only when NO candidate is
      * available.
      */
    case UnsupportedOS

    /** The probe RAN TO COMPLETION and reported that the host cannot provide the capability: the OS gate passed, the library loaded, nothing
      * threw, and the answer was still no. The io_uring probe returning a clean `false` on a kernel without io_uring, or under a seccomp /
      * RLIMIT_MEMLOCK sandbox, is the canonical case and the commonest real degrade. Distinct from [[UnsupportedOS]], which is "this
      * candidate does not apply here" rather than "it applies and this host cannot serve it".
      */
    case Unavailable(reason: String)

    /** A native library the candidate declares is not staged for this `<os>-<arch>`. This is the incident shape: a readiness backend whose
      * libc gate passes on a host with no bundled shim used to win selection and then die per connection at the first bundled-shim call, so
      * the probe reaches this outcome and the backend demotes at selection time instead. Carries the library id the loader could not resolve
      * and the platform tag it searched for.
      *
      * On the JVM this is usually a missing line in the reader's build rather than a gap in what kyo publishes, which is why
      * [[describe]] names the line: kyo-net ships each platform's native as a CLASSIFIER artifact, and the platform tag here IS the
      * classifier string. Read as a bare statement about kyo's release contents ("not bundled for darwin-aarch64"), it costs hours on the
      * wrong hypothesis, and it silently leaves the application on the NIO floor while every test still passes.
      */
    case NotBundled(libraryId: String, platform: String)

    /** The staged native's ABI or manifest version is below what the binding requires. Defined here so the mapping from the FFI load errors
      * is total; nothing produces it until the packaging manifest carries versions.
      */
    case VersionTooOld(have: String, need: String)

    /** The probe's own syscall or load threw something the classification does not recognize. The unclassified bucket: a probe that reaches
      * here is reporting an unexpected failure, not a known degrade.
      */
    case ProbeFailed(cause: Throwable)

    /** True only for [[Available]]. The gate selection reads; every other case means the candidate is skipped. */
    def isAvailable: Boolean = this match
        case Available => true
        case _         => false

    /** One line for the selection report and for the terminal exception's cause. */
    def describe: String = this match
        case Available           => "available"
        case UnsupportedOS       => "not applicable to this OS/runtime"
        case Unavailable(reason) => s"unavailable ($reason)"
        case NotBundled(id, platform) =>
            s"native library '$id' is not on the classpath for $platform; on the JVM, add kyo-net's $platform classifier artifact, " +
                s"""libraryDependencies += "io.getkyo" %% "kyo-net" % <version> classifier "$platform""""
        case VersionTooOld(have, need) => s"native version $have is below the required $need"
        case ProbeFailed(cause)        => s"probe failed (${NetException.show(cause)})"

end CapabilityOutcome
