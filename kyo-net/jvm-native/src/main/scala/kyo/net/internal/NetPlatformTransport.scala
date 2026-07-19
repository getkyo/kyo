package kyo.net.internal

import kyo.*
import kyo.net.NetConfig
import kyo.net.Transport
import kyo.net.internal.backend.IoBackendPlatform

/** Shared platform-bootstrap body for the JVM and Native `NetPlatformTransport` objects. Both build the production transport through the
  * capability-probed backend registry (`IoBackendPlatform`, the platform's own); only the platform identity (the scaladoc on each leaf object)
  * differs. Each leaf `NetPlatformTransport` object extends this base, so the lazy `transport` and the `configured` builder live once.
  */
private[kyo] trait NetPlatformTransportBase:
    /** Build a fresh transport with a custom config (the platform-default backend, honoring `-Dkyo.net.backend`). Caller owns its lifecycle. */
    def configured()(using AllowUnsafe, Frame): Transport =
        IoBackendPlatform.transport()

    /** Build the one process-shared transport, marked as such (see [[ProcessSharedTransport]]). Used for [[kyo.net.NetPlatform.transport]],
      * which every client and server shares and which is never closed for the process lifetime, so its idle carriers must carry the
      * leak-check-allowlisted `processSharedTransport` frame rather than trip the stranded-op / fiber-leak gate.
      */
    def configuredProcessLifetime()(using AllowUnsafe, Frame): Transport =
        ProcessSharedTransport.whileBuilding(IoBackendPlatform.transport())
end NetPlatformTransportBase
