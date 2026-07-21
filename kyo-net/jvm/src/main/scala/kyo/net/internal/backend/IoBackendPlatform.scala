package kyo.net.internal.backend

import kyo.*
import kyo.net.NetBackendUnavailableException
import kyo.net.Transport
import kyo.net.internal.NioTransport
import kyo.net.internal.posix.PosixTransport

/** JVM `registered` list and selection entry point.
  *
  * The JVM ships two families of backend that drive DIFFERENT handle types: `NioBackend` produces an `IoDriver[NioHandle]` (the pure-JDK
  * floor), while the posix backends (`IoUringBackend`, `EpollBackend`, `KqueueBackend`) produce an `IoDriver[PosixHandle]` over Panama. They
  * cannot share one `IoDriver`-typed list. The registry therefore represents each entry by its selectable identity (`name` / `priority` /
  * `isAvailable`) plus a `build` thunk that constructs (and starts) the entry's transport. Selection runs
  * over the identity fields via the same shared `IoBackend.select` both registries use; the winner's `build` thunk is then invoked.
  *
  * On a posix host the highest-priority available entry is a posix backend (io_uring on a capable Linux, epoll on any other Linux, kqueue on
  * macOS/BSD), so the production transport is the unified `PosixTransport`. `NioBackend` (priority 10, unconditionally available) is the floor:
  * with it always registered, selection can never fail on a working JDK, and `-Dkyo.net.backend=nio` forces the floor. The posix
  * entries reuse the posix backend objects' identity/probes from `PosixBackends.scala`; only their `build` thunk lives here (it is the
  * driver-start-plus-`PosixTransport.init` sequence the posix tests use).
  */
private[net] object IoBackendPlatform:

    /** The Nio entry builds the existing `NioTransport` exactly as the JVM transport did before the registry held posix backends; this keeps
      * `NioTransport` (and its concrete `NioIoDriver`) reachable as the always-available floor. It is JVM-only: Native has no NIO floor, so this
      * entry has no Native twin (the posix `Entry`/`PosixEntry`/backends are shared from `PosixBackends.scala`).
      */
    private object NioEntry extends Entry:
        def name                                    = NioBackend.name
        def priority                                = NioBackend.priority
        def isAvailable(using AllowUnsafe): Boolean = NioBackend.isAvailable
        def build()(using AllowUnsafe, Frame): Transport =
            NioTransport.init()
    end NioEntry

    /** The registry: posix backends (io_uring 30, epoll 20, kqueue 20) above the always-available Nio floor (10). On a posix host the highest
      * available entry is a posix backend; on a JDK with no usable posix syscall the Nio floor wins.
      */
    val registered: Chunk[Entry] =
        Chunk(PosixEntry(IoUringBackend), PosixEntry(EpollBackend), PosixEntry(KqueueBackend), NioEntry)

    /** The selected JVM entry honoring `-Dkyo.net.backend`. On macOS/BSD this is `kqueue`, on Linux `io_uring`/`epoll`, and `nio` only when
      * forced or when no posix syscall is available. Selection runs through the same shared `IoBackend.select` the TLS and Native registries
      * use, so adding a backend is a list edit, never a `select` edit.
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

    /** Build the selected JVM transport. Selection honors `-Dkyo.net.backend` (a forced-unavailable name fails with
      * [[NetBackendUnavailableException]]); with no forced name it walks the priority gradient and builds the first backend that constructs,
      * falling back to the next when a higher-priority one is available (its cheap probe passed) but fails to build at production scale
      * (io_uring whose production-depth ring cannot init on a restricted host degrades to epoll rather than failing the whole transport).
      */
    def transport()(using AllowUnsafe, Frame): Transport =
        IoBackend.selectAndBuild[Entry, Transport](
            registered,
            _.name,
            _.priority,
            _.isAvailable,
            _.build(),
            forced = Maybe(kyo.net.backend()).filter(_.nonEmpty),
            onUnavailable = NetBackendUnavailableException(_)
        ).getOrThrow

end IoBackendPlatform
