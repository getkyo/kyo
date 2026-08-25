package kyo.net.internal.backend

import kyo.*
import kyo.net.NetBackendUnavailableException
import kyo.net.Transport

/** JVM `registered` list and selection entry point.
  *
  * The JVM ships two families of backend that drive DIFFERENT handle types: `NioBackend` produces an `IoDriver[NioHandle]` (the pure-JDK
  * floor), while the posix backends (`IoUringBackend`, `EpollBackend`, `KqueueBackend`) produce an `IoDriver[PosixHandle]` over Panama. They
  * cannot share one `IoDriver`-typed list. The registry is therefore typed by the selectable [[Entry]] identity, which leaves each backend's
  * `Handle` abstract; selection reads only that identity through the same shared `IoBackend.select` both registries use, and the winner's own
  * `build` is then invoked.
  *
  * On a posix host the highest-priority available entry is a posix backend (io_uring on a capable Linux, epoll on any other Linux, kqueue on
  * macOS/BSD), so the production transport is the unified `PosixTransport`. `NioBackend` (priority 10, unconditionally available) is the floor:
  * with it always registered, selection can never fail on a working JDK, and `-Dkyo.net.backend=nio` forces the floor.
  */
private[net] object IoBackendPlatform:

    /** The registry: posix backends (io_uring 30, epoll 20, kqueue 20) above the always-available Nio floor (10). On a posix host the highest
      * available entry is a posix backend; on a JDK with no usable posix syscall the Nio floor wins. The backends register themselves, with no
      * wrapper in between: each one is an [[Entry]].
      */
    val registered: Chunk[Entry] =
        Chunk(IoUringBackend, EpollBackend, KqueueBackend, NioBackend)

    /** The selected JVM entry honoring `-Dkyo.net.backend`. On macOS/BSD this is `kqueue`, on Linux `io_uring`/`epoll`, and `nio` only when
      * forced or when no posix syscall is available. Selection runs through the same shared `IoBackend.select` the TLS and Native registries
      * use, so adding a backend is a list edit, never a `select` edit.
      */
    def selected(using AllowUnsafe, Frame): Entry =
        IoBackend.select[Entry, NetBackendUnavailableException](
            registered,
            forced = Maybe(kyo.net.backend()).filter(_.nonEmpty),
            onUnavailable = (forced, report) => NetBackendUnavailableException(forced, report.render)
        ).getOrThrow

    /** Build the selected JVM transport. Selection honors `-Dkyo.net.backend` (a forced-unavailable name fails with
      * [[NetBackendUnavailableException]] carrying the selection report as its cause); with no forced name it walks the priority gradient and
      * builds the first backend that constructs, falling back to the next when a higher-priority one is available (its probe passed) but fails
      * to build at production scale (io_uring whose production-depth ring cannot init on a restricted host degrades to epoll rather than
      * failing the whole transport).
      */
    def transport()(using AllowUnsafe, Frame): Transport =
        IoBackend.selectAndBuild[Entry, Transport](
            registered,
            _.build(),
            forced = Maybe(kyo.net.backend()).filter(_.nonEmpty),
            onUnavailable = (forced, report) => NetBackendUnavailableException(forced, report.render)
        ).getOrThrow

end IoBackendPlatform
