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
    /** The transport for the current platform, built once and shared for the lifetime of the process.
      *
      * There is exactly one, and it serves every caller. A transport is a multiplexer rather than a per-caller object: one instance already
      * carries many listeners and many connections, spread round-robin across its drivers, and each driver costs a poller or ring descriptor
      * plus a carrier waiting on it. Building a private one per client and per server would multiply the machine's I/O fabric by the number of
      * components instead of by the work they do.
      *
      * Differing settings are not a reason to build another: every field of [[NetConfig]] applies to one connection, one socket, or one
      * operation, so callers pass their own config to each [[Transport]] operation and still share this instance.
      *
      * This transport is never closed: it belongs to the process, and closing it would take every co-tenant's connections down. It has no `close()`: the only
      * things a component owns are its listeners and connections, and closing those is what reclaims its resources.
      */
    lazy val transport: Transport =
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        kyo.net.internal.NetPlatformTransport.configuredProcessLifetime()
    end transport

end NetPlatform
