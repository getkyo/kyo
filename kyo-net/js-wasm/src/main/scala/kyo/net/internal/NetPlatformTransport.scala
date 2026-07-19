package kyo.net.internal

import kyo.*
import kyo.net.Transport
import kyo.net.TransportConfig

/** JS platform bootstrap. Lazily initializes a single-driver `JsTransport` (pool size 1, since JS is single-threaded). */
private[kyo] object NetPlatformTransport:
    lazy val transport: Transport =
        // Unsafe: module-level transport initialization runs outside any effect, so there is no AllowUnsafe in scope to thread through.
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        JsTransport.init(
            poolSize = 1,
            channelCapacity = TransportConfig.default.channelCapacity,
            connectTimeout = TransportConfig.default.connectTimeout,
            handshakeTimeout = TransportConfig.default.handshakeTimeout
        )
    end transport

    /** Build a fresh JS transport with a custom config (single-driver, since JS is single-threaded). Caller owns its lifecycle. */
    def configured(config: TransportConfig)(using AllowUnsafe, Frame): Transport =
        JsTransport.init(
            poolSize = 1,
            channelCapacity = config.channelCapacity,
            connectTimeout = config.connectTimeout,
            handshakeTimeout = config.handshakeTimeout
        )

    /** Process-lifetime variant used for the default HTTP client's transport. JS has no Diagnostics-registering I/O drivers (the stranded-op /
      * fiber-leak gate has nothing to allowlist here), so no marker is needed: a plain per-config build.
      */
    def configuredProcessLifetime(config: TransportConfig)(using AllowUnsafe, Frame): Transport =
        configured(config)
end NetPlatformTransport
