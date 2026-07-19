package kyo.net.internal.backend

import kyo.*
import kyo.net.NetBackendUnavailableException
import kyo.net.Transport
import kyo.net.TransportConfig

/** Native `registered` list and selection entry point.
  *
  * Native has NO `nio` floor (no `java.nio` here): a POSIX OS always has epoll (Linux) or kqueue (macOS/BSD), so a readiness backend is the
  * floor per the Backend & TLS matrix. The registry therefore holds only the three posix backends (io_uring 30, epoll 20, kqueue 20). The two
  * readiness backends are OS-exclusive (`isAvailable` probes the real syscall: an `epoll_create1` on Linux, a `kqueue` on macOS/BSD), so even
  * though both carry priority 20 only one is ever available, and `IoBackend.select` returns exactly the backend the OS supports; io_uring
  * (priority 30) is preferred over epoll when the kernel has it.
  *
  * The posix backends, the `Entry` trait, and `PosixEntry` are shared verbatim with the JVM registry from `PosixBackends.scala`; only the
  * platform `registered` list (and its selection entry points) lives here. The Native production transport is the unified `PosixTransport` over
  * the OS-selected driver.
  */
private[net] object IoBackendPlatform:

    /** The registry: posix backends (io_uring 30, epoll 20, kqueue 20) with no floor below them. On a posix host the highest available entry
      * is io_uring on a capable Linux, epoll on any other Linux, kqueue on macOS/BSD.
      */
    val registered: Chunk[Entry] =
        Chunk(PosixEntry(IoUringBackend), PosixEntry(EpollBackend), PosixEntry(KqueueBackend))

    /** The selected Native entry honoring `-Dkyo.net.backend`. On macOS/BSD this is `kqueue`, on Linux `io_uring`/`epoll`. Selection runs
      * through the same shared `IoBackend.select` the JVM and TLS registries use, so adding a backend is a list edit, never a `select` edit.
      */
    def selected(using AllowUnsafe, Frame): Entry =
        IoBackend.select[Entry, NetBackendUnavailableException](
            registered,
            _.name,
            _.priority,
            _.isAvailable,
            forced = Maybe(kyo.net.backend()).filter(_.nonEmpty),
            onUnavailable = NetBackendUnavailableException(_)
        ).getOrThrow

    /** Build the selected Native transport. Selection honors `-Dkyo.net.backend` (a forced-unavailable name fails with
      * [[NetBackendUnavailableException]]); with no forced name it walks the priority gradient and builds the first backend that constructs,
      * falling back to the next when a higher-priority one is available (its cheap probe passed) but fails to build at production scale
      * (io_uring whose production-depth ring cannot init on a restricted host degrades to epoll rather than failing the whole transport).
      */
    def transport(config: TransportConfig)(using AllowUnsafe, Frame): Transport =
        IoBackend.selectAndBuild[Entry, Transport](
            registered,
            _.name,
            _.priority,
            _.isAvailable,
            _.build(config),
            forced = Maybe(kyo.net.backend()).filter(_.nonEmpty),
            onUnavailable = NetBackendUnavailableException(_)
        ).getOrThrow

end IoBackendPlatform
