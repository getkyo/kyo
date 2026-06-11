package kyo.internal

import kyo.*
import kyo.internal.transport.*

/** JS platform bootstrap. Lazily initializes a single-driver `JsTransport` (pool size 1 — JS is single-threaded). */
private[kyo] object HttpPlatformTransport:
    lazy val transport: Transport[?] =
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        JsTransport.init(
            poolSize = 1,
            channelCapacity = HttpTransportConfig.default.channelCapacity
        )
    end transport
end HttpPlatformTransport
