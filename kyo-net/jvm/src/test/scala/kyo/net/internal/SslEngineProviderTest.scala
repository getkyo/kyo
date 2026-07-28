package kyo.net.internal

import kyo.*
import kyo.net.NetTlsConfig
import kyo.net.NetTlsConfigException
import kyo.net.Test

/** JVM-only unit coverage for [[SslEngineProvider]]'s own config-failure branch: a verifying client (default `trustAll = false`,
  * `hostnameVerification = true`) with no reference identity (an empty connect host) must fail closed with [[NetTlsConfigException]], the
  * same typed leaf the BoringSSL/OpenSSL providers and the inline NIO path throw for the identical config (RFC 9525 6.1).
  * [[JdkSslEngineTest]] covers the happy-path handshake; this pins the one throw site `SslEngineProvider.createEngine` owns.
  */
class SslEngineProviderTest extends Test:

    import AllowUnsafe.embrace.danger

    "createEngine with an empty hostname on a verifying client throws NetTlsConfigException" in {
        val config = NetTlsConfig()
        val ex     = intercept[NetTlsConfigException](SslEngineProvider.createEngine(config, "", isServer = false))
        assert(
            ex.getMessage.contains("reference identity"),
            s"must be the reference-identity config failure, got ${ex.getMessage}"
        )
    }

    "createEngine with a non-empty hostname on a verifying client does not throw (positive control)" in {
        val config = NetTlsConfig()
        val engine = SslEngineProvider.createEngine(config, "localhost", isServer = false)
        assert(engine != null, "a verifying client with a real hostname must build an engine")
    }

    "createEngine with an empty hostname on a trustAll client does not throw (no reference identity required)" in {
        val config = NetTlsConfig(trustAll = true)
        val engine = SslEngineProvider.createEngine(config, "", isServer = false)
        assert(engine != null, "a trustAll client never checks a reference identity, so an empty host must not fail")
    }

end SslEngineProviderTest
