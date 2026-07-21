package kyo.net

import kyo.*

/** Tests for Connection.upgradeToTls on the Scala Native transport.
  *
  * Uses a loopback TCP pair where both client and server run in-process. Certificates are generated via the `openssl` CLI (available on
  * Linux/macOS CI runners and developer machines) — no containers or external infrastructure required.
  *
  * The TLS upgrade flow mirrors Postgres SSLRequest: client sends a 1-byte upgrade signal ('U'), the server reads it and upgrades to TLS,
  * then the client upgrades too. After that, both sides communicate over encrypted TLS on the original socket.
  */
class ConnectionUpgradeTlsTest extends Test:

    /** Generate a self-signed certificate + key in a temp directory. Lazy so it runs once on first use. */
    private lazy val tmpDir: String =
        java.nio.file.Files.createTempDirectory("kyo-native-tls-test").toAbsolutePath.toString

    private lazy val (certPath, keyPath): (String, String) =
        val cert = s"$tmpDir/cert.pem"
        val key  = s"$tmpDir/key.pem"
        val proc = new ProcessBuilder(
            "openssl",
            "req",
            "-x509",
            "-newkey",
            "rsa:2048",
            "-keyout",
            key,
            "-out",
            cert,
            "-days",
            "1",
            "-nodes",
            "-subj",
            "/CN=localhost",
            "-addext",
            "subjectAltName=DNS:localhost,IP:127.0.0.1"
        ).inheritIO().start()
        val exit = proc.waitFor()
        if exit != 0 then throw new RuntimeException(s"openssl req failed (exit=$exit)")
        (cert, key)
    end val

    /** Generate a second self-signed cert with a wrong hostname to test hostname rejection. Lazy, created on demand. */
    private lazy val (wrongCertPath, wrongKeyPath): (String, String) =
        val cert = s"$tmpDir/wrong-cert.pem"
        val key  = s"$tmpDir/wrong-key.pem"
        val proc = new ProcessBuilder(
            "openssl",
            "req",
            "-x509",
            "-newkey",
            "rsa:2048",
            "-keyout",
            key,
            "-out",
            cert,
            "-days",
            "1",
            "-nodes",
            "-subj",
            "/CN=wrong-host.example.com",
            "-addext",
            "subjectAltName=DNS:wrong-host.example.com"
        ).inheritIO().start()
        val exit = proc.waitFor()
        if exit != 0 then throw new RuntimeException(s"openssl req (wrong cert) failed (exit=$exit)")
        (cert, key)
    end val

    private def serverTls: NetTlsConfig =
        val (cert, key) = (certPath, keyPath)
        NetTlsConfig(certChainPath = Present(cert), privateKeyPath = Present(key))
    end serverTls

    private def clientTrustAll: NetTlsConfig = NetTlsConfig(trustAll = true)

    private def clientVerifyHostname: NetTlsConfig =
        val (cert, _) = (certPath, keyPath)
        NetTlsConfig(
            caCertPath = Present(cert),
            hostnameVerification = true,
            sniHostname = Present("localhost")
        )
    end clientVerifyHostname

    "Plaintext connect + upgrade succeeds against TLS-enabled server" in run {
        val transport = NetPlatform.transport
        Scope.run {
            for
                echoResult <- Promise.init[String, Nothing]
                listener <- transport.listen("127.0.0.1", 0) { serverConn =>
                    Abort.run[Closed] {
                        serverConn.inbound.take.flatMap { _ =>
                            serverConn.upgradeToTls(serverTls).flatMap { tlsConn =>
                                tlsConn.inbound.take.flatMap { data =>
                                    tlsConn.write(data).andThen {
                                        echoResult.complete(Result.succeed(new String(data.toArray))).unit
                                    }
                                }
                            }
                        }
                    }.unit
                }
                port = listener.port
                result <- transport.connect("127.0.0.1", port).map { plainConn =>
                    plainConn.write(Span.from(Array[Byte]('U'))).andThen {
                        plainConn.upgradeToTls(clientTrustAll).flatMap { tlsConn =>
                            tlsConn.write(Span.from("hello".getBytes)).andThen {
                                tlsConn.inbound.take.map { received =>
                                    assert(new String(received.toArray) == "hello")
                                }
                            }
                        }
                    }
                }
            yield result
        }
    }

    "Read/write after upgrade is encrypted (peer cert hash present)" in run {
        val transport = NetPlatform.transport
        Scope.run {
            for
                echoResult <- Promise.init[String, Nothing]
                listener <- transport.listen("127.0.0.1", 0) { serverConn =>
                    Abort.run[Closed] {
                        serverConn.inbound.take.flatMap { _ =>
                            serverConn.upgradeToTls(serverTls).flatMap { tlsConn =>
                                tlsConn.inbound.take.flatMap { data =>
                                    tlsConn.write(data).andThen {
                                        echoResult.complete(Result.succeed(new String(data.toArray))).unit
                                    }
                                }
                            }
                        }
                    }.unit
                }
                port = listener.port
                result <- transport.connect("127.0.0.1", port).map { plainConn =>
                    plainConn.write(Span.from(Array[Byte]('U'))).andThen {
                        plainConn.upgradeToTls(clientTrustAll).flatMap { tlsConn =>
                            val msg = Span.from("hello-native-tls".getBytes)
                            tlsConn.write(msg).andThen {
                                tlsConn.serverCertificateHash.flatMap { hashMaybe =>
                                    tlsConn.inbound.take.map { received =>
                                        val decoded = new String(received.toArray)
                                        assert(decoded == "hello-native-tls", s"expected 'hello-native-tls', got '$decoded'")
                                        assert(hashMaybe.isDefined, "peer certificate hash must be present after TLS upgrade")
                                        assert(hashMaybe.get.size == 32, "SHA-256 cert hash must be 32 bytes")
                                    }
                                }
                            }
                        }
                    }
                }
            yield result
        }
    }

    "Upgrade against non-TLS server fails with Closed error" in run {
        val transport = NetPlatform.transport
        Scope.run {
            for
                listener <- transport.listen("127.0.0.1", 0) { serverConn =>
                    // Server does NOT upgrade — reads the signal byte, closes the connection,
                    // so the client's TLS handshake sees EOF and fails.
                    Abort.run[Closed] {
                        serverConn.inbound.take.andThen {
                            serverConn.close
                        }
                    }.unit
                }
                port = listener.port
                result <- transport.connect("127.0.0.1", port).map { plainConn =>
                    plainConn.write(Span.from(Array[Byte]('U'))).andThen {
                        // Client tries to upgrade against a non-TLS server — the TLS handshake must fail
                        Abort.run[Closed] {
                            plainConn.upgradeToTls(clientTrustAll)
                        }.map { outcome =>
                            assert(outcome.isFailure, s"upgrade against a plain server must fail, got: $outcome")
                        }
                    }
                }
            yield result
        }
    }

    "Upgrade with hostname verification accepts matching cert" in run {
        val transport = NetPlatform.transport
        Scope.run {
            for
                listener <- transport.listen("127.0.0.1", 0) { serverConn =>
                    Abort.run[Closed] {
                        serverConn.inbound.take.flatMap { _ =>
                            serverConn.upgradeToTls(serverTls).flatMap { tlsConn =>
                                tlsConn.inbound.take.flatMap { data =>
                                    tlsConn.write(data)
                                }
                            }
                        }
                    }.unit
                }
                port = listener.port
                result <- transport.connect("127.0.0.1", port).map { plainConn =>
                    plainConn.write(Span.from(Array[Byte]('U'))).andThen {
                        // Cert has SAN=DNS:localhost; client checks hostname "localhost" — must succeed
                        Abort.run[Closed] {
                            plainConn.upgradeToTls(clientVerifyHostname).flatMap { tlsConn =>
                                tlsConn.write(Span.from("verify-ok".getBytes)).andThen {
                                    tlsConn.inbound.take.map { received =>
                                        assert(new String(received.toArray) == "verify-ok")
                                    }
                                }
                            }
                        }.map { outcome =>
                            assert(outcome.isSuccess, s"hostname-verified upgrade must succeed for matching cert, got: $outcome")
                        }
                    }
                }
            yield result
        }
    }

    "Upgrade with hostname verification rejects non-matching cert" in run {
        val transport = NetPlatform.transport
        // Server uses a cert issued for wrong-host.example.com
        val wrongServerTls = NetTlsConfig(
            certChainPath = Present(wrongCertPath),
            privateKeyPath = Present(wrongKeyPath)
        )
        // Client trusts the wrong CA (self-signed, so the cert itself is the CA) but enforces hostname
        val clientCheckingHostname = NetTlsConfig(
            caCertPath = Present(wrongCertPath),
            hostnameVerification = true,
            sniHostname = Present("localhost")
        )
        Scope.run {
            for
                listener <- transport.listen("127.0.0.1", 0) { serverConn =>
                    Abort.run[Closed] {
                        serverConn.inbound.take.flatMap { _ =>
                            // Server upgrade may fail or succeed depending on timing; ignore result
                            Abort.run[Closed](serverConn.upgradeToTls(wrongServerTls)).unit
                        }
                    }.unit
                }
                port = listener.port
                result <- transport.connect("127.0.0.1", port).map { plainConn =>
                    plainConn.write(Span.from(Array[Byte]('U'))).andThen {
                        // OpenSSL verifies hostname "localhost" against SAN=DNS:wrong-host.example.com — must fail
                        Abort.run[Closed] {
                            plainConn.upgradeToTls(clientCheckingHostname)
                        }.map { outcome =>
                            assert(outcome.isFailure, s"hostname mismatch must cause handshake failure, got: $outcome")
                        }
                    }
                }
            yield result
        }
    }

end ConnectionUpgradeTlsTest
