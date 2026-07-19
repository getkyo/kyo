package kyo.net

import kyo.*

/** Platform-default Transport instance.
  *
  * Uses the OS-appropriate I/O mechanism for the current platform:
  *   - JVM: the posix transport (io_uring/epoll on Linux, kqueue on macOS/BSD) over Panama FFI, falling back to the pure-JDK NIO Selector floor
  *     when no posix syscall is available or `-Dkyo.net.backend=nio` forces it
  *   - Native: io_uring/epoll (Linux) or kqueue (macOS/BSD)
  *   - JS: Node.js net module
  *
  * The transport is lazily initialized and shared for the lifetime of the process. The selected backend honors `-Dkyo.net.backend`
  * (io_uring/epoll/kqueue/nio on the JVM); see `IoBackendPlatform`.
  */
object NetPlatform:
    /** The default transport for the current platform, built with `TransportConfig.default` and shared for the lifetime of the process.
      *
      * The same instance `transport(TransportConfig.default)` returns: the default config is not a special case, it is simply the config most
      * callers ask for.
      */
    lazy val transport: Transport =
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        transport(TransportConfig.default)
    end transport

    // One transport per distinct config, shared for the lifetime of the process. Keyed by the config value: TransportConfig is a case class, so
    // callers asking for the same settings get the same instance. Never evicted, which is why the key must stay a value: the number of distinct
    // configs in a process is small (typically one), while the number of clients and servers using them is not.
    private val shared = new java.util.concurrent.ConcurrentHashMap[TransportConfig, Transport]()

    /** The process-shared transport for a given [[TransportConfig]].
      *
      * A transport is a multiplexer, not a per-caller object: one instance already carries many listeners and many connections, distributing
      * them round-robin across its drivers. Building a private one per client and per server therefore multiplies the machine's I/O fabric by
      * the number of components rather than by the work they do, and each driver costs a poller or ring descriptor plus a waiter contending for
      * a scheduler carrier. Callers that ask for the same settings share one instance instead.
      *
      * The returned transport is never closed, for the same reason the singleton is not: it belongs to the process, and closing it would take
      * every co-tenant's connections down. Components must not call `close()` on it; they close their own listeners and connections, which is
      * what actually reclaims their resources. Use [[ownedTransport]] when a caller genuinely needs an isolated instance it controls.
      */
    def transport(config: TransportConfig)(using AllowUnsafe, Frame): Transport =
        shared.computeIfAbsent(config, cfg => kyo.net.internal.NetPlatformTransport.configuredProcessLifetime(cfg))

    /** A private transport the caller owns and MUST `close()`.
      *
      * The escape hatch from [[transport]]'s sharing, for callers that need isolation rather than different settings: a test asserting
      * transport-level behavior, or a component that must be able to tear its own I/O fabric down. Since every field of [[TransportConfig]] is
      * applied per connection, per socket, or per operation, differing settings alone are not a reason to reach for this.
      */
    def ownedTransport(config: TransportConfig)(using AllowUnsafe, Frame): Transport =
        kyo.net.internal.NetPlatformTransport.configured(config)
end NetPlatform
