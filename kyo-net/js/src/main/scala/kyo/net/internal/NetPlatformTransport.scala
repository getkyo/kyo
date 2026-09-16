package kyo.net.internal

import kyo.*
import kyo.net.NetException
import kyo.net.Transport

/** JS/Wasm platform bootstrap. The process transport is a [[DeferredTransport]]: it selects its backend through the capability-probed registry
  * (mirroring JVM/Native) the first time an operation needs one, loading the registry as a module of its own where the link can split, so a
  * page never fetches the backends it cannot run. On a posix Node host selection picks the koffi `PosixTransport` (kqueue/epoll/io_uring);
  * otherwise the `NodeBackend` floor's `JsTransport`.
  */
private[kyo] object NetPlatformTransport:
    /** Build the one process-lifetime [[kyo.net.NetPlatform.transport]]. Selection honors `-Dkyo.net.backend` and degrades to Node when a posix
      * native cannot load, exactly as `IoBackendPlatform.transport()` does, because that is what the transport calls once it loads. JS has no
      * Diagnostics-registering I/O drivers (the stranded-op / fiber-leak gate has nothing to allowlist here), so no marker is needed.
      */
    def configuredProcessLifetime()(using AllowUnsafe, Frame): Transport =
        new DeferredTransport

    /** `transport` once its backend is loaded, so its capabilities can be read. */
    def loaded(transport: Transport)(using AllowUnsafe, Frame): Fiber.Unsafe[Transport, Abort[NetException]] =
        transport match
            case deferred: DeferredTransport => deferred.loaded
            case other                       => Fiber.Unsafe.fromResult(Result.succeed(other))
end NetPlatformTransport
