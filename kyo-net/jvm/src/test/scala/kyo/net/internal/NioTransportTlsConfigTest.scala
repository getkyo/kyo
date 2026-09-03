package kyo.net.internal

import kyo.*
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.NetTlsConfigException
import kyo.net.Test

/** Reproduce-first tests pinning the JDK floor's TLS configuration failure to [[NetTlsConfigException]], the same type the native providers
  * report ([[SslLibProvider]], covered by [[BoringSslProviderConfiguredPemTest]]).
  *
  * The control is the EXCEPTION TYPE, not fail-closed posture. Both tiers already fail closed: `NioTransport.createSslContext` opens the
  * configured CA with a plain `FileInputStream` and lets the failure propagate rather than degrading to the system trust store. What diverged
  * is what a caller catches. For one identical [[NetTlsConfig]] and one identical operator typo, the native providers raise
  * `NetTlsConfigException` while the JDK floor raised whatever the JDK threw: `FileNotFoundException` for a missing path,
  * `CertificateException` for a file holding no certificate, `InvalidKeySpecException` for an unusable key.
  *
  * That makes the failure type depend on which provider the HOST selected, which no caller controls: the posix hosts reach BoringSSL while a
  * host without it falls to the Nio floor, so the same misconfiguration is catchable on one machine and escapes the same `catch` on another.
  * `NetTlsConfigException` documents itself as covering exactly these cases ("a PEM file could not be read, the SSL context/engine could not
  * be initialized"), so the floor owes that type too.
  *
  * Anti-flakiness: the `createSslContext` leaves are synchronous and in-memory (no socket, no handshake, no clock), and the unreadable path is
  * a fresh temp name that is never created, so the read fails deterministically rather than racing a cleanup. The connect leaf binds a real
  * loopback listener, but it never reaches a handshake: the engine build fails first, so the outcome does not depend on timing or on the peer
  * speaking TLS, and nothing waits on a duration.
  */
class NioTransportTlsConfigTest extends Test:

    import AllowUnsafe.embrace.danger

    /** An absolute path that does not exist (a fresh temp name that is never created), so any read of it fails deterministically. */
    private def unreadablePath(): String =
        val f = java.io.File.createTempFile("kyo-net-nio-tls-missing", ".pem")
        discard(f.delete())
        f.getAbsolutePath + "-does-not-exist"
    end unreadablePath

    /** A path that EXISTS and is readable but holds no certificate. Distinct from [[unreadablePath]]: that one fails at the file open, this one
      * opens fine and fails inside the certificate factory, which is a different JDK exception and so a separate leaf.
      */
    private def certificateFreePath(): String =
        val f = java.io.File.createTempFile("kyo-net-nio-tls-noncert", ".pem")
        java.nio.file.Files.write(f.toPath, "NOT A CERTIFICATE\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        f.deleteOnExit()
        f.getAbsolutePath
    end certificateFreePath

    "a verifying client with an unreadable configured caCertPath fails with NetTlsConfigException" in {
        Sync.defer {
            val path = unreadablePath()
            val ex = intercept[NetTlsConfigException](discard(NioTransport.createSslContext(
                NetTlsConfig(caCertPath = Present(path)),
                isServer = false
            )))
            assert(
                ex.getMessage.contains(path),
                s"the failure must name the configured path that could not be loaded; got: ${ex.getMessage}"
            )
        }
    }

    // Separate from the unreadable-path leaf: the file opens, so the JDK raises CertificateException from the factory rather than
    // FileNotFoundException from the open. Both are config errors and both must reach the caller as the same type.
    "a verifying client with a readable caCertPath holding no certificate fails with NetTlsConfigException" in {
        Sync.defer {
            val path = certificateFreePath()
            val ex = intercept[NetTlsConfigException](discard(NioTransport.createSslContext(
                NetTlsConfig(caCertPath = Present(path)),
                isServer = false
            )))
            assert(
                ex.getMessage.contains(path),
                s"the failure must name the configured path that could not be loaded; got: ${ex.getMessage}"
            )
        }
    }

    "a server with an unreadable configured certChainPath fails with NetTlsConfigException" in {
        Sync.defer {
            val path   = unreadablePath()
            val config = NetTlsConfig(certChainPath = Present(path), privateKeyPath = Present(TlsTestCert.keyPath))
            val ex     = intercept[NetTlsConfigException](discard(NioTransport.createSslContext(config, isServer = true)))
            assert(
                ex.getMessage.contains(path),
                s"the failure must name the configured path that could not be loaded; got: ${ex.getMessage}"
            )
        }
    }

    "a server with an unreadable configured privateKeyPath fails with NetTlsConfigException" in {
        Sync.defer {
            val path   = unreadablePath()
            val config = NetTlsConfig(certChainPath = Present(TlsTestCert.certPath), privateKeyPath = Present(path))
            val ex     = intercept[NetTlsConfigException](discard(NioTransport.createSslContext(config, isServer = true)))
            assert(
                ex.getMessage.contains(path),
                s"the failure must name the configured path that could not be loaded; got: ${ex.getMessage}"
            )
        }
    }

    // The key is read as PKCS#8 and parsed by a fixed RSA KeyFactory, so a readable key file that is not a usable PKCS#8 RSA key fails inside
    // the parse (InvalidKeySpecException or a Base64 IllegalArgumentException) rather than at the open. Same config error, same type owed.
    "a server with a readable but unparseable privateKeyPath fails with NetTlsConfigException" in {
        Sync.defer {
            val path   = certificateFreePath()
            val config = NetTlsConfig(certChainPath = Present(TlsTestCert.certPath), privateKeyPath = Present(path))
            val ex     = intercept[NetTlsConfigException](discard(NioTransport.createSslContext(config, isServer = true)))
            assert(
                ex.getMessage.contains(path),
                s"the failure must name the configured path that could not be loaded; got: ${ex.getMessage}"
            )
        }
    }

    // Boundary control: a NOT-configured path keeps the JDK default trust store. "No CA configured" must never become a rejection, which is
    // what separates this from the fail-closed cases above.
    "a verifying client with no configured caCertPath still builds a context on the JDK default trust store" in {
        Sync.defer {
            val ctx = NioTransport.createSslContext(NetTlsConfig(), isServer = false)
            assert(ctx != null, "a verifying client with no caCertPath must still build a context")
        }
    }

    // Positive control on the server path: valid material must still load, so the wrapping above cannot be satisfied by rejecting everything.
    "a server with a valid configured cert and key still builds a context" in {
        Sync.defer {
            val config = NetTlsConfig(certChainPath = Present(TlsTestCert.certPath), privateKeyPath = Present(TlsTestCert.keyPath))
            val ctx    = NioTransport.createSslContext(config, isServer = true)
            assert(ctx != null, "valid configured server material must still build a context")
        }
    }

    /** Create a transport and register its driver to close at leaf-scope exit. An owned transport (unlike the process-shared one) must be
      * closed, or its never-exiting selector poll loop keeps a scheduler worker busy and the suite fails the end-of-run leak check.
      */
    private def mkTransport()(using Frame): NioTransport < (Sync & Scope) =
        Sync.defer(NioTransport.init()).map { t =>
            Scope.ensure(Sync.defer(t.pool.next().close())).andThen(t)
        }

    // The connect path carries the same divergence one level up, and it is the half a caller actually sees. `startTlsHandshake` catches
    // `NetTlsException` and reports it as-is, but reports every other Exception as NetTlsHandshakeException. A raw FileNotFoundException from
    // the CA load took that second branch, so a misconfigured path was reported as a HANDSHAKE failure for a handshake that never started,
    // pointing the operator at the network instead of at their own config. This mirrors the invariant PosixTransportTlsConfigTest already pins
    // for the posix backend: a config failure propagates as-is and is never re-wrapped as a handshake failure.
    "a TLS connect with an unreadable configured caCertPath reports a config failure, not a handshake failure" in {
        given Frame = Frame.internal
        mkTransport().map { transport =>
            // A PLAIN listener is enough: the connect fails while building the engine, before any handshake byte is exchanged, so the peer
            // never has to speak TLS. It exists only so the TCP connect completes and the TLS setup is actually reached.
            transport.listen("127.0.0.1", 0, 50)(_ => ()).safe.get.map { listener =>
                val badTls = NetTlsConfig(caCertPath = Present(unreadablePath()))
                Abort.run[NetException](transport.connectTls("127.0.0.1", listener.port, badTls).safe.get).map { result =>
                    listener.close()
                    result match
                        case Result.Failure(_: NetTlsConfigException) => succeed
                        case Result.Success(conn) =>
                            conn.close()
                            fail("a connect with an unreadable configured caCertPath must not succeed")
                        case other =>
                            fail(s"expected NetTlsConfigException, got: $other")
                    end match
                }
            }
        }
    }

end NioTransportTlsConfigTest
