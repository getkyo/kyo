package kyo.net

import kyo.*

/** Covers [[NetTlsConfig]]'s own contract, notably `handshakeTimeout`, which lives here rather than on [[NetConfig]] so it reaches exactly the
  * operations that perform a handshake (`connectTls`, `listenTls`, `upgradeToTls`) and no others. The deadline's runtime behavior is covered by
  * the transport-level handshake tests; what is asserted here is the value contract those tests depend on.
  */
class NetTlsConfigTest extends Test:

    "handshakeTimeout" - {
        "defaults to 30 seconds, so the slowloris guard is armed without configuration" in {
            assert(NetTlsConfig.default.handshakeTimeout == 30.seconds)
            succeed
        }

        "accepts a finite deadline" in {
            assert(NetTlsConfig.default.copy(handshakeTimeout = 150.millis).handshakeTimeout == 150.millis)
            succeed
        }

        "accepts Infinity, which arms no deadline" in {
            assert(NetTlsConfig.default.copy(handshakeTimeout = Duration.Infinity).handshakeTimeout == Duration.Infinity)
            succeed
        }

        "rejects zero and negative deadlines" in {
            assert(
                intercept[IllegalArgumentException](NetTlsConfig.default.copy(handshakeTimeout = Duration.Zero))
                    .getMessage.contains("handshakeTimeout")
            )
            assert(
                intercept[IllegalArgumentException](NetTlsConfig.default.copy(handshakeTimeout = -1.seconds))
                    .getMessage.contains("handshakeTimeout")
            )
            succeed
        }

        "overrides exactly one field" in {
            val base    = NetTlsConfig.default
            val updated = base.copy(handshakeTimeout = 5.seconds)
            assert(updated.handshakeTimeout == 5.seconds)
            assert(updated.trustAll == base.trustAll)
            assert(updated.minVersion == base.minVersion)
            assert(updated.maxVersion == base.maxVersion)
            assert(updated.tlsProvider == base.tlsProvider)
            succeed
        }
    }

end NetTlsConfigTest
