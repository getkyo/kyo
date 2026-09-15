package kyo.net.internal

import kyo.*
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.NetTlsConfigException
import kyo.net.Test
import scala.scalajs.js as sjs

/** Reproduce-first tests pinning the Node TLS path's configuration failure to [[NetTlsConfigException]], delivered on the failure channel
  * `connectTls` / `listenTls` declare.
  *
  * Two things are under test, and the second is the one a caller actually feels:
  *   - TYPE. The native providers and the JVM Nio floor report an unreadable configured PEM as `NetTlsConfigException`. Node's `readFileSync`
  *     raises its own error ("ENOENT: no such file or directory"), so the same [[NetTlsConfig]] and the same operator typo produced a
  *     different type on this platform than on the others.
  *   - CHANNEL. Both methods return a `Fiber` whose failure channel is `Abort[NetException]`, and their sibling config rejections (a non-node
  *     provider pin, a verifying client with no reference identity) both report through it. A read that throws from inside the option-building
  *     escapes the method instead and reaches the caller as a Panic, which `Abort.run[NetException]` does not catch. So each leaf asserts
  *     `Result.Failure` specifically, never merely "it failed": a Panic here would mean the caller who correctly folds the declared channel
  *     still sees nothing.
  *
  * Anti-flakiness: no leaf reaches a socket. The configured material is read before any Node option object is built, so `listenTls` never
  * binds and `connectTls` never dials; there is no peer, no handshake and no timing. The unreadable path is a fresh temp name that is never
  * created, so the read fails deterministically.
  */
class JsTransportTlsConfigTest extends Test:

    import AllowUnsafe.embrace.danger

    private val fs       = sjs.Dynamic.global.require("fs")
    private val os       = sjs.Dynamic.global.require("os")
    private val nodePath = sjs.Dynamic.global.require("path")

    /** An absolute path that does not exist, so any read of it fails deterministically. */
    private def unreadablePath(): String =
        nodePath.join(os.tmpdir().asInstanceOf[String], "kyo-net-js-tls-missing.pem").asInstanceOf[String] + "-does-not-exist"

    private def writeTempPem(content: String, name: String): String =
        val path = nodePath.join(os.tmpdir().asInstanceOf[String], name).asInstanceOf[String]
        fs.writeFileSync(path, content)
        path
    end writeTempPem

    private lazy val certPath: String = writeTempPem(kyo.net.TlsTestCertShared.certPem, "kyo-js-tlscfg-cert.pem")
    private lazy val keyPath: String  = writeTempPem(kyo.net.TlsTestCertShared.keyPem, "kyo-js-tlscfg-key.pem")

    "a server with an unreadable configured certChainPath fails the listen with NetTlsConfigException" in {
        val transport = JsTransport.init(poolSize = 1)
        val tls       = NetTlsConfig(certChainPath = Present(unreadablePath()), privateKeyPath = Present(keyPath))
        Abort.run[NetException](transport.listenTls("127.0.0.1", 0, 128, tls)(_ => ()).safe.get).map {
            case Result.Failure(_: NetTlsConfigException) => succeed
            case Result.Success(listener) =>
                listener.close()
                fail("a listen with an unreadable configured certChainPath must not bind")
            case other => fail(s"expected Failure(NetTlsConfigException) on the declared channel, got: $other")
        }
    }

    "a verifying client with an unreadable configured caCertPath fails the connect with NetTlsConfigException" in {
        val transport = JsTransport.init(poolSize = 1)
        val tls       = NetTlsConfig(caCertPath = Present(unreadablePath()))
        Abort.run[NetException](transport.connectTls("127.0.0.1", 1, tls).safe.get).map {
            case Result.Failure(_: NetTlsConfigException) => succeed
            case Result.Success(conn) =>
                conn.close()
                fail("a connect with an unreadable configured caCertPath must not succeed")
            case other => fail(s"expected Failure(NetTlsConfigException) on the declared channel, got: $other")
        }
    }

    // Boundary control: configured material that reads fine must still bind, so the guard above cannot be satisfied by rejecting every TLS
    // listen. This is what separates "configured but unreadable" from "configured and usable".
    "a server with readable configured material still binds" in {
        val transport = JsTransport.init(poolSize = 1)
        val tls       = NetTlsConfig(certChainPath = Present(certPath), privateKeyPath = Present(keyPath))
        transport.listenTls("127.0.0.1", 0, 128, tls)(_ => ()).safe.get.map { listener =>
            val bound = listener.port
            listener.close()
            assert(bound > 0, "a listener with readable configured material must bind to a real port")
        }
    }

end JsTransportTlsConfigTest
