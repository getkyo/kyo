package kyo.net.internal

import kyo.*
import kyo.net.Transport

/** JS/Wasm platform bootstrap. Builds the one process-lifetime transport through the capability-probed backend registry (mirroring JVM/Native):
  * a posix Node host selects the koffi `PosixTransport` (kqueue/epoll/io_uring); otherwise the `NodeBackend` floor's `JsTransport` runs.
  */
private[kyo] object NetPlatformTransport:
    /** Build the one process-lifetime [[kyo.net.NetPlatform.transport]] via `IoBackendPlatform.transport()`, so `-Dkyo.net.backend` selects
      * between the native posix transport and Node, degrading to Node when a posix native cannot load. JS has no Diagnostics-registering I/O
      * drivers (the stranded-op / fiber-leak gate has nothing to allowlist here), so no marker is needed.
      */
    def configuredProcessLifetime()(using AllowUnsafe, Frame): Transport =
        kyo.net.internal.backend.IoBackendPlatform.transport()
end NetPlatformTransport
