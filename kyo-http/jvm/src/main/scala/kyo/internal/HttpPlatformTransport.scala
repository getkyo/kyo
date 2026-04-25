package kyo.internal

import kyo.*
import kyo.internal.transport.*

/** JVM platform bootstrap. Lazily initializes a single `NioTransport` backed by one `NioIoDriver`/`Selector`. */
private[kyo] object HttpPlatformTransport:
    lazy val transport: Transport[?] =
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        NioTransport.init(
            channelCapacity = HttpTransportConfig.default.channelCapacity,
            readBufferSize = HttpTransportConfig.default.readChunkSize
        )
    end transport
end HttpPlatformTransport
