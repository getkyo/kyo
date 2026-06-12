package kyo.net.internal.backend

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Transport
import kyo.net.TransportConfig
import kyo.net.internal.posix.EpollBindings
import kyo.net.internal.posix.IoUringBindings
import kyo.net.internal.posix.IoUringDriver
import kyo.net.internal.posix.KqueueBindings
import kyo.net.internal.posix.PollerIoDriver
import kyo.net.internal.posix.PosixConstants
import kyo.net.internal.posix.PosixHandle
import kyo.net.internal.posix.PosixTransport
import kyo.net.internal.posix.SocketBindings
import kyo.net.internal.transport.IoDriver

/** The posix backends and their registry entry, shared verbatim by the JVM and Native `IoBackendPlatform` registries. Both platforms produce the
  * same unified drivers over `PosixHandle` (`IoUringDriver.init` / `PollerIoDriver.init`) and wire them into the same `PosixTransport`, so a JVM
  * on a posix host selects exactly the backend Native would: io_uring on a Linux kernel that has it (priority 30), epoll on any other Linux
  * (priority 20), kqueue on macOS/BSD (priority 20). The two OS-exclusive readiness backends both carry priority 20, but `isAvailable` runs the
  * real syscall (an `epoll_create1` on Linux, a `kqueue` on macOS/BSD), so only one is ever available on a given host. The JVM registry adds its
  * `NioBackend` floor (priority 10) on top of these; Native has no NIO floor.
  */
private[net] trait PosixIoBackend extends IoBackend:
    type Handle = PosixHandle
end PosixIoBackend

private[net] object EpollBackend extends PosixIoBackend:
    def name     = "epoll"
    def priority = 20
    def isAvailable(using AllowUnsafe): Boolean =
        PosixConstants.isLinux && probe {
            val ep = Ffi.load[EpollBindings]
            val fd = ep.epoll_create1(0).value
            if fd >= 0 then
                discard(Ffi.load[SocketBindings].close(fd))
                true
            else false
            end if
        }
    def createDriver(config: TransportConfig)(using AllowUnsafe, Frame): IoDriver[PosixHandle] =
        PollerIoDriver.init(config)
end EpollBackend

private[net] object KqueueBackend extends PosixIoBackend:
    def name     = "kqueue"
    def priority = 20
    def isAvailable(using AllowUnsafe): Boolean =
        PosixConstants.isMacOrBsd && probe {
            val kq = Ffi.load[KqueueBindings]
            val fd = kq.kqueue().value
            if fd >= 0 then
                discard(Ffi.load[SocketBindings].close(fd))
                true
            else false
            end if
        }
    def createDriver(config: TransportConfig)(using AllowUnsafe, Frame): IoDriver[PosixHandle] =
        PollerIoDriver.init(config)
end KqueueBackend

/** io_uring backend (Linux >= 5.6 only, priority 30 so it is preferred over epoll when available). `isAvailable` runs the real
  * `kyo_uring_probe_available` shim probe (set up a tiny ring, tear it down); on a kernel without io_uring or a sandbox that blocks it, the
  * probe returns false and selection falls through to epoll. `createDriver` produces the completion-native `IoUringDriver`.
  */
private[net] object IoUringBackend extends PosixIoBackend:
    def name     = "io_uring"
    def priority = 30
    def isAvailable(using AllowUnsafe): Boolean =
        // Probe at the production queue depth (the same max(256, ioPoolSize * 64) IoUringDriver.init builds), not a fixed 256, so a sandbox that
        // initializes a small ring but rejects the production-depth ring is reported unavailable here rather than selected and failing at build.
        val depth = math.max(256, TransportConfig.default.ioPoolSize * 64)
        PosixConstants.isLinux && probe(Ffi.load[IoUringBindings].kyo_uring_probe_available(depth))
    end isAvailable
    def createDriver(config: TransportConfig)(using AllowUnsafe, Frame): IoDriver[PosixHandle] =
        IoUringDriver.init(config)
end IoUringBackend

/** Run a capability probe, returning `false` (never throwing) if the syscall is unavailable: on a non-host OS the kyo-ffi binding for the
  * missing header is a runtime stub that throws, so the probe is wrapped to honor `IoBackend.isAvailable`'s MUST-NOT-throw contract.
  */
private def probe(test: => Boolean): Boolean =
    try test
    catch case _: Throwable => false

/** A selectable registry entry: the identity `IoBackend.select` reads (`name` / `priority` / `isAvailable`), plus a `build` thunk that
  * constructs and starts the entry's transport. `build` is invoked only once selection wins, so an unavailable entry never constructs a driver.
  * The two effect-carrying methods (`isAvailable` needs `AllowUnsafe`, `build` needs `AllowUnsafe, Frame`) cannot be case-class fields, so
  * `Entry` is a trait whose concrete instances are the posix entries here and the JVM-only Nio entry in the JVM `IoBackendPlatform`.
  */
private[net] trait Entry:
    def name: String
    def priority: Int
    def isAvailable(using AllowUnsafe): Boolean
    def build(config: TransportConfig)(using AllowUnsafe, Frame): Transport
end Entry

/** A registry entry over a posix backend: `build` constructs the unified driver, starts its event loop, and wires it into `PosixTransport.init`
  * (the same construct-then-`start()` sequence `PosixTransportTest` uses for a real round-trip).
  */
final private[net] class PosixEntry(backend: PosixIoBackend) extends Entry:
    def name                                    = backend.name
    def priority                                = backend.priority
    def isAvailable(using AllowUnsafe): Boolean = backend.isAvailable
    def build(config: TransportConfig)(using AllowUnsafe, Frame): Transport =
        val driver = backend.createDriver(config)
        discard(driver.start())
        PosixTransport.init(config, driver)
    end build
end PosixEntry
