package kyo.internal

import kyo.*
import kyo.internal.transport.NetConfigTranslation
import kyo.net.NetAddress
import kyo.net.NetTlsConfig
import kyo.net.TransportConfig

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
            val result = NetConfigTranslation.toNetTlsConfig(http)
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
            val result = NetConfigTranslation.toNetTlsConfig(http)
            assert(result.caCertPath == Absent)
            assert(result.hostnameVerification == true)
        }

        "maps ClientAuth case None" in {
            val http   = HttpTlsConfig(clientAuth = HttpTlsConfig.ClientAuth.None)
            val result = NetConfigTranslation.toNetTlsConfig(http)
            assert(result.clientAuth == NetTlsConfig.ClientAuth.None)
        }

        "maps ClientAuth case Optional" in {
            val http   = HttpTlsConfig(clientAuth = HttpTlsConfig.ClientAuth.Optional)
            val result = NetConfigTranslation.toNetTlsConfig(http)
            assert(result.clientAuth == NetTlsConfig.ClientAuth.Optional)
        }

        "maps ClientAuth case Required" in {
            val http   = HttpTlsConfig(clientAuth = HttpTlsConfig.ClientAuth.Required)
            val result = NetConfigTranslation.toNetTlsConfig(http)
            assert(result.clientAuth == NetTlsConfig.ClientAuth.Required)
        }

        "maps Version TLS12 for both minVersion and maxVersion" in {
            val http   = HttpTlsConfig(minVersion = HttpTlsConfig.Version.TLS12, maxVersion = HttpTlsConfig.Version.TLS12)
            val result = NetConfigTranslation.toNetTlsConfig(http)
            assert(result.minVersion == NetTlsConfig.Version.TLS12)
            assert(result.maxVersion == NetTlsConfig.Version.TLS12)
        }

        "maps Version TLS13 for both minVersion and maxVersion" in {
            val http   = HttpTlsConfig(minVersion = HttpTlsConfig.Version.TLS13, maxVersion = HttpTlsConfig.Version.TLS13)
            val result = NetConfigTranslation.toNetTlsConfig(http)
            assert(result.minVersion == NetTlsConfig.Version.TLS13)
            assert(result.maxVersion == NetTlsConfig.Version.TLS13)
        }

        "default input equals NetTlsConfig.default" in {
            val result = NetConfigTranslation.toNetTlsConfig(HttpTlsConfig.default)
            assert(result == NetTlsConfig.default)
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

    "toNetTransportConfig" - {

        "copies the four byte-transport fields by name" in {
            val http = HttpTransportConfig.default
                .channelCapacity(7)
                .readChunkSize(2048)
                .ioPoolSize(3)
                .handshakeTimeout(250.millis)
            val result = NetConfigTranslation.toNetTransportConfig(http)
            assert(result.channelCapacity == 7)
            assert(result.readChunkSize == 2048)
            assert(result.ioPoolSize == 3)
            assert(result.handshakeTimeout == 250.millis)
        }

        "carries the finite handshakeTimeout the slowloris guard depends on" in {
            val result = NetConfigTranslation.toNetTransportConfig(HttpTransportConfig.default.handshakeTimeout(1.second))
            assert(result.handshakeTimeout == 1.second)
        }

        "does not map maxHeaderSize: kyo.net.TransportConfig has no such field (HTTP-parser concern, kept in kyo-http)" in {
            // A custom maxHeaderSize must not leak into the net config, and must not perturb the four mapped fields.
            val http   = HttpTransportConfig.default.maxHeaderSize(4096)
            val result = NetConfigTranslation.toNetTransportConfig(http)
            assert(result == TransportConfig.default)
        }

        "default input equals TransportConfig.default" in {
            val result = NetConfigTranslation.toNetTransportConfig(HttpTransportConfig.default)
            assert(result == TransportConfig.default)
        }

    }

end NetConfigTranslationTest
