package kyo.net

import kyo.*

class NetConfigTest extends Test:

    "default config values" in {
        val config = NetConfig.default
        assert(config.channelCapacity == 4)
        assert(config.readChunkSize == 8.kib)
        assert(config.soRcvBuf == Absent)
        assert(config.soSndBuf == Absent)
        assert(config.peerCloseGrace.duration == 30.seconds)
        assert(config.closeFlushGrace.duration == 30.seconds)
        succeed
    }

    "the companion constants are the defaults the operations apply" in {
        assert(NetConfig.DefaultChannelCapacity == NetConfig.default.channelCapacity)
        assert(NetConfig.DefaultReadChunkSize == NetConfig.default.readChunkSize)
        assert(NetConfig.DefaultPeerCloseGrace == NetConfig.default.peerCloseGrace)
        assert(NetConfig.DefaultCloseFlushGrace == NetConfig.default.closeFlushGrace)
        succeed
    }

    "copy produces the given values" in {
        val config = NetConfig.default.copy(channelCapacity = 8, readChunkSize = 4.kib)
        assert(config.channelCapacity == 8)
        assert(config.readChunkSize == 4.kib)
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
        val updated = base.copy(readChunkSize = 99.bytes)
        assert(updated.readChunkSize == 99.bytes)
        assert(updated.channelCapacity == base.channelCapacity)
        assert(updated.soRcvBuf == base.soRcvBuf)
        assert(updated.soSndBuf == base.soSndBuf)
        succeed
    }

    "soRcvBuf and soSndBuf override exactly their fields" in {
        val base    = NetConfig.default
        val updated = base.copy(soRcvBuf = Present(64.kib), soSndBuf = Present(32.kib))
        assert(updated.soRcvBuf == Present(64.kib))
        assert(updated.soSndBuf == Present(32.kib))
        assert(updated.channelCapacity == base.channelCapacity)
        assert(updated.readChunkSize == base.readChunkSize)
        succeed
    }

    "carries no connect or handshake deadline: those belong to the operations that can act on them" - {
        // Guards the shape this type was reduced to. A connect deadline is a parameter of the connect operations and a handshake deadline is
        // a NetTlsConfig field, so neither can be handed to an operation it does not apply to. Reads the case class's own field names, so
        // re-adding either field to NetConfig fails here rather than silently reintroducing a setting half the call sites ignore.
        // peerCloseGrace and closeFlushGrace are connection shape, not connect/handshake deadlines, so they belong here.
        val fields = NetConfig.default.productElementNames.toList

        "the six fields are the connection shape, socket options, and the two close graces" in {
            assert(fields == List("channelCapacity", "readChunkSize", "soRcvBuf", "soSndBuf", "peerCloseGrace", "closeFlushGrace"))
            succeed
        }

        "no timeout field" in {
            assert(!fields.contains("connectTimeout"))
            assert(!fields.contains("handshakeTimeout"))
            succeed
        }
    }

    "a channel capacity of zero or less is held as given: the pump channels become rendezvous channels" in {
        assert(NetConfig(channelCapacity = 0).channelCapacity == 0)
        assert(NetConfig.default.copy(channelCapacity = -1).channelCapacity == -1)
        succeed
    }

    "byte sizes" - {
        "are ByteSize, not raw ints" in {
            typeCheckFailure("NetConfig.default.copy(readChunkSize = 0)")
            typeCheckFailure("NetConfig.default.copy(soRcvBuf = Present(0))")
            succeed
        }

        "are narrowed at use as kyo-core narrows a stream read buffer: zero is one byte, beyond Int.MaxValue is Int.MaxValue" in {
            assert(NetConfig.bytesAtUse(ByteSize.Zero) == 1)
            assert(NetConfig.bytesAtUse(64.kib) == 65536)
            assert(NetConfig.bytesAtUse(4L.gib) == Int.MaxValue)
            succeed
        }
    }

    "Grace" - {
        "init refuses a zero grace with NetConfigException" in {
            assert(NetConfig.Grace.init(Duration.Zero).failure.map(e => (e.setting, e.value)) == Present(("grace", Duration.Zero.show)))
            succeed
        }

        "check names the setting it refuses" in {
            assert(NetConfig.Grace.check("peerCloseGrace", Duration.Zero).failure.map(_.setting) == Present("peerCloseGrace"))
            succeed
        }

        "init accepts a positive grace and Infinity, which sets no limit" in {
            assert(NetConfig.Grace.init(200.millis).map(_.duration) == Result.succeed(200.millis))
            assert(NetConfig.Grace.init(Duration.Infinity).map(_.duration) == Result.succeed(Duration.Infinity))
            assert(NetConfig.Grace.unlimited.duration == Duration.Infinity)
            succeed
        }

        "a refusal is raised at the caller's frame" in {
            val (refused, here) = (NetConfig.Grace.init(Duration.Zero), summon[Frame])
            assert(refused.failure.map(_.frame.position.lineNumber) == Present(here.position.lineNumber))
            succeed
        }

        "the constructor and copy take a checked grace for both graces, not a raw duration" in {
            typeCheckFailure("NetConfig(peerCloseGrace = Duration.Zero)")
            typeCheckFailure("NetConfig.default.copy(peerCloseGrace = 1.second)")
            typeCheckFailure("NetConfig(closeFlushGrace = Duration.Zero)")
            typeCheckFailure("NetConfig.default.copy(closeFlushGrace = 1.second)")
            succeed
        }

        "an unlimited close-flush grace is held" in {
            assert(NetConfig.default.copy(closeFlushGrace = NetConfig.Grace.unlimited).closeFlushGrace.duration == Duration.Infinity)
            succeed
        }
    }

end NetConfigTest
