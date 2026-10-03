package kyo.net

import kyo.*

/** Covers [[NetTlsConfig]]'s own contract, notably `handshakeTimeout`, which lives here rather than on [[NetConfig]] so it reaches exactly the
  * operations that perform a handshake (`connectTls`, `listenTls`, `upgradeToTls`) and no others. The deadline's runtime behavior is covered by
  * the transport-level handshake tests; what is asserted here is the value contract those tests depend on.
  */
class NetTlsConfigTest extends Test:

    "handshakeTimeout" - {
        "defaults to 30 seconds, so the slowloris guard is armed without configuration" in {
            assert(NetTlsConfig.default.handshakeTimeout.duration == 30.seconds)
            assert(NetTlsConfig.default.handshakeTimeout == NetTlsConfig.HandshakeTimeout.default)
            succeed
        }

        "accepts a finite deadline" in {
            assert(NetTlsConfig.HandshakeTimeout.init(150.millis).map(_.duration) == Result.succeed(150.millis))
            succeed
        }

        "accepts Infinity, which arms no deadline" in {
            assert(NetTlsConfig.HandshakeTimeout.init(Duration.Infinity).map(_.duration) == Result.succeed(Duration.Infinity))
            assert(NetTlsConfig.HandshakeTimeout.unlimited.duration == Duration.Infinity)
            succeed
        }

        "refuses a zero deadline with NetConfigException naming the setting" in {
            assert(NetTlsConfig.HandshakeTimeout.init(Duration.Zero).failure.map(_.setting) == Present("handshakeTimeout"))
            succeed
        }

        "a refusal is raised at the caller's frame" in {
            val (refused, here) = (NetTlsConfig.HandshakeTimeout.init(Duration.Zero), summon[Frame])
            assert(refused.failure.map(_.frame.position.lineNumber) == Present(here.position.lineNumber))
            succeed
        }

        "the constructor and copy take a checked deadline, not a raw duration" in {
            typeCheckFailure("NetTlsConfig(handshakeTimeout = Duration.Zero)")
            typeCheckFailure("NetTlsConfig.default.copy(handshakeTimeout = 5.seconds)")
            succeed
        }

        "overrides exactly one field" in {
            val base    = NetTlsConfig.default
            val updated = base.copy(handshakeTimeout = NetTlsConfig.HandshakeTimeout.unlimited)
            assert(updated.handshakeTimeout.duration == Duration.Infinity)
            assert(updated.trustAll == base.trustAll)
            assert(updated.minVersion == base.minVersion)
            assert(updated.maxVersion == base.maxVersion)
            assert(updated.tlsProvider == base.tlsProvider)
            succeed
        }
    }

end NetTlsConfigTest
