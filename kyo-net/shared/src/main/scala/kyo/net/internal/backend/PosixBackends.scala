package kyo.net.internal.backend

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Transport
import kyo.net.internal.posix.EpollBindings
import kyo.net.internal.posix.IoUringBindings
import kyo.net.internal.posix.IoUringDriver
import kyo.net.internal.posix.KqueueBindings
import kyo.net.internal.posix.PollerIoDriver
import kyo.net.internal.posix.PosixConstants
import kyo.net.internal.posix.PosixHandle
import kyo.net.internal.posix.PosixShimBindings
import kyo.net.internal.posix.PosixTransport
import kyo.net.internal.posix.SocketBindings
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.IoDriverPool

/** A selectable registry entry: an [[IoBackend]] that additionally BUILDS the transport it drives. `build` is invoked only once selection
  * wins, so an unavailable entry never constructs a driver.
  *
  * It ADDS `build` to the backend identity rather than wrapping a backend and forwarding `name` / `priority` / `probe` to it, which is what
  * the two per-instance wrapper shapes here used to do. The concrete backends ARE the entries: the posix ones share one `build` on
  * [[PosixIoBackend]], and the JVM Nio floor supplies its own.
  *
  * It lives in `shared`, and every platform's registry entries are [[Entry]]s: the posix backends share one `build` on [[PosixIoBackend]], the
  * JVM Nio floor supplies its own, and the JS/Wasm `NodeBackend` supplies its own (a [[Transport]] from `JsTransport`). `build` is a refinement
  * on top of the shared [[IoBackend]] identity rather than a member of it because the registries are heterogeneous over `Handle` (posix
  * `PosixHandle`, Nio `NioHandle`, Node `JsHandle`), so `IoBackend.selectAndBuild` keeps `build` a parameter each registry's entries satisfy.
  */
private[net] trait Entry extends IoBackend:
    def build()(using AllowUnsafe, Frame): Transport
end Entry

/** The posix backends, shared verbatim by the JVM and Native `IoBackendPlatform` registries. Both platforms produce the same unified drivers
  * over `PosixHandle` (`IoUringDriver.init` / `PollerIoDriver.init`) and wire them into the same `PosixTransport`, so a JVM on a posix host
  * selects exactly the backend Native would: io_uring on a Linux kernel that has it (priority 30), epoll on any other Linux (priority 20),
  * kqueue on macOS/BSD (priority 20). The two OS-exclusive readiness backends both carry priority 20, but each gates on its own OS and then
  * runs the real syscall (an `epoll_create1` on Linux, a `kqueue` on macOS/BSD), so only one is ever available on a given host. The JVM
  * registry adds its `NioBackend` floor (priority 10) on top of these, and the JS/Wasm registry adds a `NodeBackend` floor (priority 10);
  * Native has no floor.
  *
  * `build` is declared once here rather than per backend: every posix backend builds the same way, ioPoolSize independent drivers wrapped in
  * a pool and started all-or-nothing.
  */
private[net] trait PosixIoBackend extends Entry:
    type Handle = PosixHandle

    final def build()(using AllowUnsafe, Frame): Transport =
        // Build ioPoolSize independent drivers, wrap them in the pool, and start them all-or-nothing. Each driver owns its own poller/io_uring fd
        // and carrier fiber; pool.next() distributes new connections round-robin across the drivers, and each connection is then bound to one
        // driver for its lifetime, so per-handle single-driver ownership holds downstream. Both JVM and Native run the scheduler over real OS
        // threads, so each driver's poll loop parks its own carrier thread and ioPoolSize drivers give real cross-core parallelism.
        // On JS one driver: Node is single-threaded (one macrotask loop), and each driver's `@Ffi.blocking` poll runs on a libuv worker
        // (default pool of 4), so one poller keeps a single worker in play and leaves the rest for close/connect (the J libuv-budget design).
        val n       = if kyo.internal.Platform.isJS then 1 else kyo.net.ioPoolSize()
        val drivers = Array.fill(n)(createDriver())
        val pool    = IoDriverPool.init(drivers)
        pool.start() // all-or-nothing: on any driver-start failure this closes the started subset and rethrows.
        PosixTransport.init(pool)
    end build
end PosixIoBackend

private[net] object PosixIoBackend:

    /** Exercise the BUNDLED posix shim read-only against the probe fd the libc gate just produced, so a readiness backend whose syscall
      * exists but whose shim was never staged demotes HERE instead of at the first connection.
      *
      * The libc gate alone is dishonest on exactly the host that matters: `epoll_create1` and `kqueue` come from libc and are present
      * regardless of what the build bundled, so the backend reported available, won selection over the floor, and then died per connection
      * inside `prepareClientSocket`, past the point any fallback could engage. `kyo_posix_get_flags` is the same bundled
      * `kyonet_posix_uring` library the data path calls, so touching it here is what makes the probe cover the whole dependency set.
      *
      * READ-ONLY (`fcntl(fd, F_GETFL, 0)`, no `F_SETFL`) because this file is shared with floor-less Native: a probe must never mutate the
      * fd it borrows, and a host where the only readiness backend is demoted has nothing below it to fall to.
      *
      * The load failure this catches arrives from the binding companion's class initializer, so `CapabilityProbe.classify`'s unwrap is what
      * turns it into [[CapabilityOutcome.NotBundled]] rather than an opaque `ProbeFailed`; callers run this inside `CapabilityProbe.run`.
      */
    private def exerciseShim(fd: Int)(using AllowUnsafe): CapabilityOutcome =
        if Ffi.load[PosixShimBindings].kyo_posix_get_flags(fd) < 0 then
            CapabilityOutcome.Unavailable("the bundled posix shim could not read the probe fd's file-status flags")
        else CapabilityOutcome.Available

    /** The readiness backends' shared probe body: open the OS's readiness descriptor through libc, exercise the bundled shim on it, and close
      * it. The close sits in a `finally` so a shim load that throws on the demotion path does not leak the descriptor it borrowed.
      */
    private[backend] def probeReadiness(openGate: => Int, gate: String)(using AllowUnsafe): CapabilityOutcome =
        val fd = openGate
        if fd < 0 then CapabilityOutcome.Unavailable(s"$gate failed on this host")
        else
            try exerciseShim(fd)
            finally discard(Ffi.load[SocketBindings].close(fd))
        end if
    end probeReadiness

    /** The dependency set both readiness backends load: libc (the gate syscall and the close both come from `SocketBindings`' library id,
      * which is the same "c" the epoll and kqueue bindings declare) and then the bundled shim the data path needs. Bundled native last, the
      * order [[CapabilityDescriptor.libraryIds]] requires.
      */
    private[backend] val readinessLibraryIds: Chunk[String] = Chunk(SocketBindings.library, PosixShimBindings.library)

end PosixIoBackend

private[net] object EpollBackend extends PosixIoBackend:
    def name                      = "epoll"
    def priority                  = 20
    def libraryIds: Chunk[String] = PosixIoBackend.readinessLibraryIds

    private[net] def doProbe(using AllowUnsafe): CapabilityOutcome =
        if !PosixConstants.isLinux then CapabilityOutcome.UnsupportedOS
        else
            CapabilityProbe.run(libraryIds) {
                PosixIoBackend.probeReadiness(Ffi.load[EpollBindings].epoll_create1(0).value, "epoll_create1")
            }

    def createDriver()(using AllowUnsafe, Frame): IoDriver[PosixHandle] =
        PollerIoDriver.init()
end EpollBackend

private[net] object KqueueBackend extends PosixIoBackend:
    def name                      = "kqueue"
    def priority                  = 20
    def libraryIds: Chunk[String] = PosixIoBackend.readinessLibraryIds

    private[net] def doProbe(using AllowUnsafe): CapabilityOutcome =
        if !PosixConstants.isMacOrBsd then CapabilityOutcome.UnsupportedOS
        else
            CapabilityProbe.run(libraryIds) {
                PosixIoBackend.probeReadiness(Ffi.load[KqueueBindings].kqueue().value, "kqueue")
            }

    def createDriver()(using AllowUnsafe, Frame): IoDriver[PosixHandle] =
        PollerIoDriver.init()
end KqueueBackend

/** io_uring backend (Linux >= 5.6 only, priority 30 so it is preferred over epoll when available). Its probe runs the real
  * `kyo_uring_probe_available` shim probe (set up a tiny ring, tear it down); on a kernel without io_uring or a sandbox that blocks it the
  * probe returns a clean `false`, which is reported as [[CapabilityOutcome.Unavailable]] and selection falls through to epoll.
  * `createDriver` produces the completion-native `IoUringDriver`.
  *
  * It needs no separate shim exercise: `kyonet_posix_uring` is the library its own probe already loads, so an unstaged bundle surfaces here
  * as [[CapabilityOutcome.NotBundled]] through the same classification the readiness backends reach through their shim call.
  */
private[net] object IoUringBackend extends PosixIoBackend:
    def name                      = "io_uring"
    def priority                  = 30
    def libraryIds: Chunk[String] = Chunk(IoUringBindings.library)

    private[net] def doProbe(using AllowUnsafe): CapabilityOutcome =
        if !PosixConstants.isLinux then CapabilityOutcome.UnsupportedOS
        else
            CapabilityProbe.run(libraryIds) {
                // Probe at the production queue depth (the same max(256, ioPoolSize * 64) IoUringDriver.init builds), not a fixed 256, so a
                // sandbox that initializes a small ring but rejects the production-depth ring is reported unavailable here rather than
                // selected and failing at build. The probe is memoized, so the pool size this reads is the one in effect at the first
                // selection; that matches the host-static premise the memo rests on, since the pool width is a process-wide flag that does
                // not change once selection has run.
                val depth = math.max(256, kyo.net.ioPoolSize() * 64)
                if Ffi.load[IoUringBindings].kyo_uring_probe_available(depth) then CapabilityOutcome.Available
                else CapabilityOutcome.Unavailable(s"io_uring could not initialize a ring at the production depth $depth")
            }

    def createDriver()(using AllowUnsafe, Frame): IoDriver[PosixHandle] =
        IoUringDriver.init()
end IoUringBackend
