package kyo.net.internal

import kyo.*
import kyo.net.Transport
import kyo.net.TransportConfig
import kyo.net.internal.backend.IoBackendPlatform

/** Shared platform-bootstrap body for the JVM and Native `NetPlatformTransport` objects. Both build the production transport through the
  * capability-probed backend registry (`IoBackendPlatform`, the platform's own); only the platform identity (the scaladoc on each leaf object)
  * differs. Each leaf `NetPlatformTransport` object extends this base, so the lazy `transport` and the `configured` builder live once.
  */
private[kyo] trait NetPlatformTransportBase:
    /** Build a fresh transport with a custom config (the platform-default backend, honoring `-Dkyo.net.backend`). Caller owns its lifecycle. */
    def configured(config: TransportConfig)(using AllowUnsafe, Frame): Transport =
        IoBackendPlatform.transport(config)

    /** Build a fresh transport with a custom config, marked as a process-lifetime transport (see [[ProcessSharedTransport]]). Used for the
      * process-wide default HTTP client's transport: it owns a driver pool distinct from the [[transport]] singleton (so closing that client
      * cannot close the shared singleton), but like the singleton it is never closed for the process lifetime, so its idle carriers must carry
      * the same leak-check-allowlisted `processSharedTransport` frame rather than trip the stranded-op / fiber-leak gate.
      */
    def configuredProcessLifetime(config: TransportConfig)(using AllowUnsafe, Frame): Transport =
        ProcessSharedTransport.whileBuilding(IoBackendPlatform.transport(config))
end NetPlatformTransportBase
