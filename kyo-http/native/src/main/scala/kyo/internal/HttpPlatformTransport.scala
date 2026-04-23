package kyo.internal

import kyo.*
import kyo.internal.transport.*

/** Native platform bootstrap. Lazily initializes a `NativeTransport` with the OS-appropriate `PollerBackend` (epoll or kqueue). */
private[kyo] object HttpPlatformTransport:
    lazy val transport: Transport[?] =
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        NativeTransport.init(
            backend = PollerBackend.default,
            poolSize = HttpTransportConfig.default.ioPoolSize,
            channelCapacity = HttpTransportConfig.default.channelCapacity,
            readBufferSize = HttpTransportConfig.default.readChunkSize
        )
    end transport
end HttpPlatformTransport
