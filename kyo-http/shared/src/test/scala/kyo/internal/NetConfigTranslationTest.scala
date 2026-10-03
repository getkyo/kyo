package kyo.internal

import kyo.*
import kyo.internal.transport.NetConfigTranslation
import kyo.net.NetAddress
import kyo.net.NetConfig
import kyo.net.NetConfigException
import kyo.net.NetPlatform
import kyo.net.NetTlsConfig

class NetConfigTranslationTest extends kyo.test.Test[Any]:

    "toNetTlsConfig" - {

        "copies all 8 shared fields by name" in {
            val http = HttpTlsConfig(
                trustAll = true,
                sniHostname = Present("sni.example"),
                certChainPath = Present("cert.pem"),
                privateKeyPath = Present("key.pem"),
                clientAuth = HttpTlsConfig.ClientAuth.Required,
                trustStorePath = Present("trust.pem"),
                minVersion = HttpTlsConfig.Version.TLS12,
                maxVersion = HttpTlsConfig.Version.TLS13
            )
            val result = NetConfigTranslation.toNetTlsConfig(http, HttpTransportConfig.default.handshakeTimeout).getOrThrow
            assert(result.trustAll == true)
            assert(result.sniHostname == Present("sni.example"))
            assert(result.certChainPath == Present("cert.pem"))
            assert(result.privateKeyPath == Present("key.pem"))
            assert(result.trustStorePath == Present("trust.pem"))
            assert(result.minVersion == NetTlsConfig.Version.TLS12)
            assert(result.maxVersion == NetTlsConfig.Version.TLS13)
            assert(result.clientAuth == NetTlsConfig.ClientAuth.Required)
        }

        "defaults the 2 kyo-net-only fields to Absent and true" in {
            val http = HttpTlsConfig(
                trustAll = true,
                sniHostname = Present("sni.example"),
                certChainPath = Present("cert.pem"),
                privateKeyPath = Present("key.pem"),
                clientAuth = HttpTlsConfig.ClientAuth.Required,
                trustStorePath = Present("trust.pem"),
                minVersion = HttpTlsConfig.Version.TLS12,
                maxVersion = HttpTlsConfig.Version.TLS13
            )
            val result = NetConfigTranslation.toNetTlsConfig(http, HttpTransportConfig.default.handshakeTimeout).getOrThrow
            assert(result.caCertPath == Absent)
            assert(result.hostnameVerification == true)
        }

        "maps ClientAuth case None" in {
            val http   = HttpTlsConfig(clientAuth = HttpTlsConfig.ClientAuth.None)
            val result = NetConfigTranslation.toNetTlsConfig(http, HttpTransportConfig.default.handshakeTimeout).getOrThrow
            assert(result.clientAuth == NetTlsConfig.ClientAuth.None)
        }

        "maps ClientAuth case Optional" in {
            val http   = HttpTlsConfig(clientAuth = HttpTlsConfig.ClientAuth.Optional)
            val result = NetConfigTranslation.toNetTlsConfig(http, HttpTransportConfig.default.handshakeTimeout).getOrThrow
            assert(result.clientAuth == NetTlsConfig.ClientAuth.Optional)
        }

        "maps ClientAuth case Required" in {
            val http   = HttpTlsConfig(clientAuth = HttpTlsConfig.ClientAuth.Required)
            val result = NetConfigTranslation.toNetTlsConfig(http, HttpTransportConfig.default.handshakeTimeout).getOrThrow
            assert(result.clientAuth == NetTlsConfig.ClientAuth.Required)
        }

        "maps Version TLS12 for both minVersion and maxVersion" in {
            val http   = HttpTlsConfig(minVersion = HttpTlsConfig.Version.TLS12, maxVersion = HttpTlsConfig.Version.TLS12)
            val result = NetConfigTranslation.toNetTlsConfig(http, HttpTransportConfig.default.handshakeTimeout).getOrThrow
            assert(result.minVersion == NetTlsConfig.Version.TLS12)
            assert(result.maxVersion == NetTlsConfig.Version.TLS12)
        }

        "maps Version TLS13 for both minVersion and maxVersion" in {
            val http   = HttpTlsConfig(minVersion = HttpTlsConfig.Version.TLS13, maxVersion = HttpTlsConfig.Version.TLS13)
            val result = NetConfigTranslation.toNetTlsConfig(http, HttpTransportConfig.default.handshakeTimeout).getOrThrow
            assert(result.minVersion == NetTlsConfig.Version.TLS13)
            assert(result.maxVersion == NetTlsConfig.Version.TLS13)
        }

        "default input differs from NetTlsConfig.default in the handshake deadline alone" in {
            // The translator no longer decides the handshake deadline: the caller passes it, and kyo-http deliberately passes Infinity while
            // kyo-net's own default is 30s. So the result cannot equal NetTlsConfig.default, and asserting it did would be asserting that the
            // server's Infinity gets discarded. Full equality is still asserted, against the value the caller actually supplied, so any drift
            // in the other ten fields still fails here.
            val handshakeTimeout = HttpTransportConfig.default.handshakeTimeout
            val result           = NetConfigTranslation.toNetTlsConfig(HttpTlsConfig.default, handshakeTimeout)
            assert(handshakeTimeout == Duration.Infinity)
            assert(result == Result.succeed(NetTlsConfig.default.copy(handshakeTimeout = NetTlsConfig.HandshakeTimeout.unlimited)))
        }

        "passing kyo-net's own default deadline reproduces NetTlsConfig.default exactly" in {
            val result = NetConfigTranslation.toNetTlsConfig(HttpTlsConfig.default, NetTlsConfig.default.handshakeTimeout.duration)
            assert(result == Result.succeed(NetTlsConfig.default))
        }

        "a zero handshake deadline is refused with NetConfigException naming the setting" in {
            val result = NetConfigTranslation.toNetTlsConfig(HttpTlsConfig.default, Duration.Zero)
            assert(result.failure.map(_.setting) == Present("handshakeTimeout"))
        }

    }

    "toHttpAddress" - {

        "maps Tcp case field-preserving" in {
            val addr   = NetAddress.Tcp("host.example", 8443)
            val result = NetConfigTranslation.toHttpAddress(addr)
            assert(result == HttpAddress.Tcp("host.example", 8443))
        }

        "maps Unix case field-preserving" in {
            val addr   = NetAddress.Unix("/tmp/server.sock")
            val result = NetConfigTranslation.toHttpAddress(addr)
            assert(result == HttpAddress.Unix("/tmp/server.sock"))
        }

    }

    "toNetConfig" - {

        "copies the two connection-shape fields by name" in {
            val http = HttpTransportConfig.default
                .channelCapacity(7)
                .readChunkSize(2048)
            val result = NetConfigTranslation.toNetConfig(http).getOrThrow
            assert(result.channelCapacity.value == 7)
            assert(result.readChunkSize == 2.kib)
        }

        "maps no deadline: NetConfig carries none, so neither can be dropped here" in {
            // The handshake deadline travels on NetTlsConfig and the connect deadline is the connect operation's own parameter, so this
            // translator has only the connection shape to carry. Asserting the field list keeps a re-added timeout from silently
            // reappearing in a config half the call sites ignore. peerCloseGrace is intentionally not mapped: kyo-http keeps NetConfig's
            // finite default (it wants the reclaim).
            val fields = NetConfigTranslation.toNetConfig(HttpTransportConfig.default).getOrThrow.productElementNames.toList
            assert(fields == List("channelCapacity", "readChunkSize", "soRcvBuf", "soSndBuf", "peerCloseGrace"))
        }

        "does not map maxHeaderSize: kyo.net.NetConfig has no such field (HTTP-parser concern, kept in kyo-http)" in {
            // A custom maxHeaderSize must not leak into the net config, and must not perturb the mapped fields.
            val http   = HttpTransportConfig.default.maxHeaderSize(4096)
            val result = NetConfigTranslation.toNetConfig(http).getOrThrow
            assert(result.channelCapacity.value == HttpTransportConfig.default.channelCapacity)
            assert(result.readChunkSize == HttpTransportConfig.default.readChunkSize.bytes)
        }

        "default input maps the connection-shape fields from HttpTransportConfig.default" in {
            val result = NetConfigTranslation.toNetConfig(HttpTransportConfig.default).getOrThrow
            assert(result.channelCapacity.value == HttpTransportConfig.default.channelCapacity)
            assert(result.readChunkSize == HttpTransportConfig.default.readChunkSize.bytes)
        }

        "a channel capacity below one is refused with NetConfigException naming the setting" in {
            val capacity = NetConfigTranslation.toNetConfig(HttpTransportConfig.default.channelCapacity(0))
            assert(capacity.failure.map(e => (e.setting, e.value)) == Present(("channelCapacity", "0")))
        }

        "a read chunk size is a byte size, narrowed where the transport uses it rather than refused" in {
            assert(NetConfigTranslation.toNetConfig(HttpTransportConfig.default.readChunkSize(0)).map(_.readChunkSize) == Result.succeed(
                ByteSize.Zero
            ))
        }

        "the server handshake deadline reaches the TLS config, including Infinity" in {
            // The regression this guards: kyo-http servers default handshakeTimeout to Infinity while NetTlsConfig.default is 30s, so a
            // translation that dropped the value would silently arm a 30s slowloris reap on every kyo-http server.
            assert(HttpTransportConfig.default.handshakeTimeout == Duration.Infinity)
            val carried = NetConfigTranslation.toNetTlsConfig(HttpTlsConfig.default, HttpTransportConfig.default.handshakeTimeout)
            assert(carried.map(_.handshakeTimeout.duration) == Result.succeed(Duration.Infinity))
            val finite = NetConfigTranslation.toNetTlsConfig(HttpTlsConfig.default, 250.millis)
            assert(finite.map(_.handshakeTimeout.duration) == Result.succeed(250.millis))
        }

    }

    "a refused setting fails the operation on its NetException row, before any socket" - {

        "connect" in {
            import AllowUnsafe.embrace.danger
            val opened = NetConfigTranslation.connect(
                NetPlatform.transport,
                Absent,
                "127.0.0.1",
                1,
                ssl = false,
                HttpTlsConfig.default,
                30.seconds,
                HttpTransportConfig.default.channelCapacity(0)
            )
            Abort.run[kyo.net.NetException](opened.safe.get).map { outcome =>
                assert(outcome.failure.collect { case e: NetConfigException => e.setting } == Present("channelCapacity"))
            }
        }

        "a zero connect deadline" in {
            import AllowUnsafe.embrace.danger
            val opened = NetConfigTranslation.connect(
                NetPlatform.transport,
                Absent,
                "127.0.0.1",
                1,
                ssl = false,
                HttpTlsConfig.default,
                Duration.Zero,
                HttpTransportConfig.default
            )
            Abort.run[kyo.net.NetException](opened.safe.get).map { outcome =>
                assert(outcome.failure.collect { case e: NetConfigException => e.setting } == Present("connectTimeout"))
            }
        }

        "listen" in {
            import AllowUnsafe.embrace.danger
            val bound = NetConfigTranslation.listen(
                NetPlatform.transport,
                Absent,
                "127.0.0.1",
                0,
                16,
                Absent,
                HttpTransportConfig.default.channelCapacity(0)
            )(_ => ())
            Abort.run[kyo.net.NetException](bound.safe.get).map { outcome =>
                assert(outcome.failure.collect { case e: NetConfigException => e.setting } == Present("channelCapacity"))
            }
        }
    }

end NetConfigTranslationTest
