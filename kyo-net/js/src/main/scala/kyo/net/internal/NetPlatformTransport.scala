package kyo.net.internal

import kyo.*
import kyo.net.Transport
import kyo.net.TransportConfig

/** JS platform bootstrap. Lazily initializes a single-driver `JsTransport` (pool size 1 — JS is single-threaded). */
private[kyo] object NetPlatformTransport:
    lazy val transport: Transport.Unsafe =
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        JsTransport.init(
            poolSize = 1,
            channelCapacity = TransportConfig.default.channelCapacity
        )
    end transport
end NetPlatformTransport
