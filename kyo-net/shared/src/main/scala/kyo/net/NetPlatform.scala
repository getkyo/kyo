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
    /** The default transport for the current platform, built with `TransportConfig.default` and shared for the lifetime of the process. */
    lazy val transport: Transport =
        kyo.net.internal.NetPlatformTransport.transport

    /** A transport built with a custom [[TransportConfig]] (for example a finite server `handshakeTimeout` for slowloris protection, or a larger
      * `channelCapacity`). It selects the same platform-default backend as [[transport]] (honoring `-Dkyo.net.backend`) but applies the given
      * configuration. Unlike the process-global [[transport]], the returned transport owns its resources and the caller MUST `close()` it.
      */
    def transport(config: TransportConfig)(using AllowUnsafe, Frame): Transport =
        kyo.net.internal.NetPlatformTransport.configured(config)

    /** A per-config transport marked as a process-lifetime transport (never closed by design), for the process-wide default HTTP client. Like
      * the owned `transport(config)` it builds and owns its own driver pool, distinct from the [[transport]] singleton, so closing the default
      * client cannot close that shared singleton. But because this transport is itself never closed (it lives for the process, like the
      * singleton), its idle carriers are allowlisted by the fiber-leak / stranded-op gate rather than reported as a leaked owned transport (see
      * `kyo.net.internal.ProcessSharedTransport`). Internal: only the lazy, never-closed default client uses it.
      */
    private[kyo] def processLifetimeTransport(config: TransportConfig)(using AllowUnsafe, Frame): Transport =
        kyo.net.internal.NetPlatformTransport.configuredProcessLifetime(config)
end NetPlatform
