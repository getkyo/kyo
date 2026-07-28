package kyo.net

import kyo.*

class NetConfigTest extends Test:

    "default config values" in {
        val config = NetConfig.default
        assert(config.channelCapacity == 4)
        assert(config.readChunkSize == 8192)
        assert(config.soRcvBuf == Absent)
        assert(config.soSndBuf == Absent)
        succeed
    }

    "the companion constants are the defaults the operations apply" in {
        assert(NetConfig.DefaultChannelCapacity == NetConfig.default.channelCapacity)
        assert(NetConfig.DefaultReadChunkSize == NetConfig.default.readChunkSize)
        succeed
    }

    "copy produces the given values" in {
        val config = NetConfig.default.copy(channelCapacity = 8, readChunkSize = 4096)
        assert(config.channelCapacity == 8)
        assert(config.readChunkSize == 4096)
        succeed
    }

    "channelCapacity overrides exactly one field" in {
        val base    = NetConfig.default
        val updated = base.copy(channelCapacity = 99)
        assert(updated.channelCapacity == 99)
        assert(updated.readChunkSize == base.readChunkSize)
        assert(updated.soRcvBuf == base.soRcvBuf)
        assert(updated.soSndBuf == base.soSndBuf)
        succeed
    }

    "readChunkSize overrides exactly one field" in {
        val base    = NetConfig.default
        val updated = base.copy(readChunkSize = 99)
        assert(updated.readChunkSize == 99)
        assert(updated.channelCapacity == base.channelCapacity)
        assert(updated.soRcvBuf == base.soRcvBuf)
        assert(updated.soSndBuf == base.soSndBuf)
        succeed
    }

    "soRcvBuf and soSndBuf override exactly their fields" in {
        val base    = NetConfig.default
        val updated = base.copy(soRcvBuf = Present(65536), soSndBuf = Present(32768))
        assert(updated.soRcvBuf == Present(65536))
        assert(updated.soSndBuf == Present(32768))
        assert(updated.channelCapacity == base.channelCapacity)
        assert(updated.readChunkSize == base.readChunkSize)
        succeed
    }

    "carries no connect or handshake deadline: those belong to the operations that can act on them" - {
        // Guards the shape this type was reduced to. A connect deadline is a parameter of the connect operations and a handshake deadline is
        // a NetTlsConfig field, so neither can be handed to an operation it does not apply to. Reads the case class's own field names, so
        // re-adding either field to NetConfig fails here rather than silently reintroducing a setting half the call sites ignore.
        // peerCloseGrace is connection shape, not a connect/handshake deadline, so it belongs here.
        val fields = NetConfig.default.productElementNames.toList

        "the five fields are the connection shape, socket options, and the peer-close reclaim grace" in {
            assert(fields == List("channelCapacity", "readChunkSize", "soRcvBuf", "soSndBuf", "peerCloseGrace"))
            succeed
        }

        "no timeout field" in {
            assert(!fields.contains("connectTimeout"))
            assert(!fields.contains("handshakeTimeout"))
            succeed
        }
    }

    "rejects values that cannot describe a connection" - {
        "channelCapacity must be positive" in {
            assert(intercept[IllegalArgumentException](NetConfig.default.copy(channelCapacity = 0)).getMessage.contains("channelCapacity"))
            assert(intercept[IllegalArgumentException](NetConfig.default.copy(channelCapacity = -1)).getMessage.contains("channelCapacity"))
            succeed
        }

        "readChunkSize must be positive" in {
            assert(intercept[IllegalArgumentException](NetConfig.default.copy(readChunkSize = 0)).getMessage.contains("readChunkSize"))
            succeed
        }

        "a present socket buffer size must be positive" in {
            assert(intercept[IllegalArgumentException](NetConfig.default.copy(soRcvBuf = Present(0))).getMessage.contains("soRcvBuf"))
            assert(intercept[IllegalArgumentException](NetConfig.default.copy(soSndBuf = Present(-1))).getMessage.contains("soSndBuf"))
            succeed
        }

        "an absent socket buffer size is accepted" in {
            val config = NetConfig.default.copy(soRcvBuf = Absent, soSndBuf = Absent)
            assert(config.soRcvBuf == Absent)
            assert(config.soSndBuf == Absent)
            succeed
        }
    }

end NetConfigTest
