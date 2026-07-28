package kyo.net.internal

import kyo.*
import kyo.net.Transport
import kyo.net.internal.backend.IoBackendPlatform

/** Shared platform-bootstrap body for the JVM and Native `NetPlatformTransport` objects. Both build the production transport through the
  * capability-probed backend registry (`IoBackendPlatform`, the platform's own); only the platform identity (the scaladoc on each leaf object)
  * differs. Each leaf `NetPlatformTransport` object extends this base, so the lazy `transport` and the `configured` builder live once.
  */
private[kyo] trait NetPlatformTransportBase:
    /** Build the one process-shared transport, marked as such (see [[ProcessSharedTransport]]). This is the ONLY transport builder: a
      * transport is process-lifetime and never closed, so [[kyo.net.NetPlatform.transport]] is the single instance every client and server
      * shares. Its idle carriers therefore carry the leak-check-allowlisted `processSharedTransport` frame rather than trip the stranded-op /
      * fiber-leak gate.
      */
    def configuredProcessLifetime()(using AllowUnsafe, Frame): Transport =
        ProcessSharedTransport.whileBuilding(IoBackendPlatform.transport())
end NetPlatformTransportBase
