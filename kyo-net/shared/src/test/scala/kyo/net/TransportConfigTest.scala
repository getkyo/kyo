package kyo.net

import kyo.*

class TransportConfigTest extends Test:

    "default config values" in {
        val config = TransportConfig.default
        assert(config.channelCapacity == 4)
        assert(config.readChunkSize == 8192)
        assert(config.ioPoolSize == Math.max(1, Runtime.getRuntime.availableProcessors() / 2))
        // handshakeTimeout defaults to Infinity: no server accept-handshake deadline is armed, preserving the original behavior.
        assert(config.handshakeTimeout == Duration.Infinity)
        succeed
    }

    "ioPoolSize default is at least 1" in {
        // max(1, cores / 2) is never below 1 even on a single-core reported runtime
        assert(TransportConfig.default.ioPoolSize >= 1)
        succeed
    }

    "builder methods produce correct values" in {
        val config = TransportConfig.default
            .copy(channelCapacity = 8, readChunkSize = 4096, ioPoolSize = 3, handshakeTimeout = 5.seconds)
        assert(config.channelCapacity == 8)
        assert(config.readChunkSize == 4096)
        assert(config.ioPoolSize == 3)
        assert(config.handshakeTimeout == 5.seconds)
        succeed
    }

    "channelCapacity overrides exactly one field" in {
        val base    = TransportConfig.default
        val updated = base.copy(channelCapacity = 99)
        assert(updated.channelCapacity == 99)
        assert(updated.readChunkSize == base.readChunkSize)
        assert(updated.ioPoolSize == base.ioPoolSize)
        assert(updated.handshakeTimeout == base.handshakeTimeout)
        succeed
    }

    "readChunkSize overrides exactly one field" in {
        val base    = TransportConfig.default
        val updated = base.copy(readChunkSize = 99)
        assert(updated.readChunkSize == 99)
        assert(updated.channelCapacity == base.channelCapacity)
        assert(updated.ioPoolSize == base.ioPoolSize)
        assert(updated.handshakeTimeout == base.handshakeTimeout)
        succeed
    }

    "ioPoolSize overrides exactly one field" in {
        val base    = TransportConfig.default
        val updated = base.copy(ioPoolSize = 99)
        assert(updated.ioPoolSize == 99)
        assert(updated.channelCapacity == base.channelCapacity)
        assert(updated.readChunkSize == base.readChunkSize)
        assert(updated.handshakeTimeout == base.handshakeTimeout)
        succeed
    }

    "handshakeTimeout overrides exactly one field" in {
        val base    = TransportConfig.default
        val updated = base.copy(handshakeTimeout = 10.seconds)
        assert(updated.handshakeTimeout == 10.seconds)
        assert(updated.channelCapacity == base.channelCapacity)
        assert(updated.readChunkSize == base.readChunkSize)
        assert(updated.ioPoolSize == base.ioPoolSize)
        succeed
    }

    "soRcvBuf defaults to Absent" in {
        assert(TransportConfig.default.soRcvBuf == Absent)
        succeed
    }

    "soSndBuf defaults to Absent" in {
        assert(TransportConfig.default.soSndBuf == Absent)
        succeed
    }

    "soRcvBuf and soSndBuf override exactly their fields" in {
        val base    = TransportConfig.default
        val updated = base.copy(soRcvBuf = Present(65536), soSndBuf = Present(32768))
        assert(updated.soRcvBuf == Present(65536))
        assert(updated.soSndBuf == Present(32768))
        assert(updated.channelCapacity == base.channelCapacity)
        assert(updated.readChunkSize == base.readChunkSize)
        assert(updated.ioPoolSize == base.ioPoolSize)
        assert(updated.handshakeTimeout == base.handshakeTimeout)
        succeed
    }

end TransportConfigTest
