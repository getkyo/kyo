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
    lazy val transport: Transport =
        // Unsafe: module-level transport initialization runs outside any effect, so there is no AllowUnsafe in scope to thread through.
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        IoBackendPlatform.transport(TransportConfig.default)
    end transport

    /** Build a fresh transport with a custom config (the platform-default backend, honoring `-Dkyo.net.backend`). Caller owns its lifecycle. */
    def configured(config: TransportConfig)(using AllowUnsafe, Frame): Transport =
        IoBackendPlatform.transport(config)
end NetPlatformTransportBase
