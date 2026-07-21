package kyo.net

import java.io.File
import java.security.KeyStore
import kyo.*

/** Tests for Connection.upgradeToTls — STARTTLS-style post-connect TLS upgrade (JVM only).
  *
  * These tests simulate a full STARTTLS handshake using two sides of a loopback TCP connection:
  *   - The server side accepts a plaintext connection, waits for a 1-byte upgrade signal ('U'), then upgrades to TLS.
  *   - The client side connects plaintext, sends the upgrade signal, then calls upgradeToTls.
  *   - After upgrade, both sides communicate over encrypted TLS.
  *
  * Certificates are generated via keytool (ships with every JDK) — no external tools or BouncyCastle required.
  */
class UpgradeTlsTest extends Test:

    /** Generate a self-signed cert via keytool and export PEM cert + key. Cached across tests in this suite. */
    private lazy val (serverTls, clientTls): (NetTlsConfig, NetTlsConfig) =
        val tmpFile = File.createTempFile("kyo-upgrade-tls-test", ".p12")
        tmpFile.delete()
        tmpFile.deleteOnExit()
        val ksPath = tmpFile.getAbsolutePath

        val cmd = Array(
            "keytool",
            "-genkeypair",
            "-alias",
            "server",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "36500",
            "-dname",
            "CN=localhost",
            "-ext",
            "SAN=dns:localhost,ip:127.0.0.1",
            "-storetype",
            "PKCS12",
            "-keystore",
            ksPath,
            "-storepass",
            "changeit",
            "-keypass",
            "changeit"
        )
        val pb = new ProcessBuilder(cmd*)
        pb.redirectErrorStream(true)
        val proc   = pb.start()
        val output = new String(proc.getInputStream.readAllBytes())
        val exit   = proc.waitFor()
        if exit != 0 then
            throw new RuntimeException(s"keytool failed (exit=$exit): $output")

        val ks  = KeyStore.getInstance("PKCS12")
        val fis = new java.io.FileInputStream(ksPath)
        try ks.load(fis, "changeit".toCharArray)
        finally fis.close()

        val enc = java.util.Base64.getMimeEncoder(64, "\n".getBytes)

        val cert     = ks.getCertificate("server")
        val certPem  = s"-----BEGIN CERTIFICATE-----\n${enc.encodeToString(cert.getEncoded)}\n-----END CERTIFICATE-----\n"
        val certFile = File.createTempFile("kyo-upgrade-tls-cert", ".pem")
        certFile.deleteOnExit()
        java.nio.file.Files.writeString(certFile.toPath, certPem)

        val key     = ks.getKey("server", "changeit".toCharArray).asInstanceOf[java.security.PrivateKey]
        val keyPem  = s"-----BEGIN PRIVATE KEY-----\n${enc.encodeToString(key.getEncoded)}\n-----END PRIVATE KEY-----\n"
        val keyFile = File.createTempFile("kyo-upgrade-tls-key", ".pem")
        keyFile.deleteOnExit()
        java.nio.file.Files.writeString(keyFile.toPath, keyPem)

        val server = NetTlsConfig(certChainPath = Present(certFile.getAbsolutePath), privateKeyPath = Present(keyFile.getAbsolutePath))
        val client = NetTlsConfig(trustAll = true)
        (server, client)
    end val

    "upgradeToTls - round-trip encrypted message after STARTTLS upgrade" in run {
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
                        plainConn.upgradeToTls(clientTls).flatMap { tlsConn =>
                            val msg = Span.from("hello-tls".getBytes)
                            tlsConn.write(msg).andThen {
                                tlsConn.inbound.take.map { received =>
                                    val decoded = new String(received.toArray)
                                    assert(decoded == "hello-tls", s"Expected 'hello-tls', got '$decoded'")
                                }
                            }
                        }
                    }
                }
            yield result
        }
    }

    "upgradeToTls - original plaintext Connection becomes closed after upgrade" in run {
        val transport = NetPlatform.transport

        Scope.run {
            for
                listener <- transport.listen("127.0.0.1", 0) { serverConn =>
                    Abort.run[Closed] {
                        serverConn.inbound.take.flatMap { _ =>
                            serverConn.upgradeToTls(serverTls).map { _ => () }
                        }
                    }.unit
                }
                port = listener.port
                result <- transport.connect("127.0.0.1", port).map { plainConn =>
                    plainConn.write(Span.from(Array[Byte]('U'))).andThen {
                        plainConn.upgradeToTls(clientTls).flatMap { tlsConn =>
                            for
                                tlsOpen   <- tlsConn.isOpen
                                plainOpen <- plainConn.isOpen
                            yield
                                assert(tlsOpen, "TLS connection must be open after upgrade")
                                assert(!plainOpen, "Original plaintext connection must be closed after upgrade")
                        }
                    }
                }
            yield result
        }
    }

    "upgradeToTls - large payload round-trip across TLS records" in run {
        val transport   = NetPlatform.transport
        val payloadSize = 32768 // 32KB — spans at least two TLS records (max record size is ~16KB)

        // Collect exactly `target` bytes by repeatedly taking from a TLS connection's inbound channel.
        def collectBytes(conn: Connection, target: Int): Span[Byte] < (Async & Abort[Closed]) =
            def loop(acc: Array[Byte], received: Int): Span[Byte] < (Async & Abort[Closed]) =
                if received >= target then Span.from(acc)
                else
                    conn.inbound.take.flatMap { chunk =>
                        val arr = chunk.toArray
                        java.lang.System.arraycopy(arr, 0, acc, received, arr.length)
                        loop(acc, received + arr.length)
                    }
            loop(new Array[Byte](target), 0)
        end collectBytes

        Scope.run {
            for
                echoResult <- Promise.init[Int, Nothing]
                listener <- transport.listen("127.0.0.1", 0) { serverConn =>
                    Abort.run[Closed] {
                        serverConn.inbound.take.flatMap { _ =>
                            serverConn.upgradeToTls(serverTls).flatMap { tlsConn =>
                                // Collect all bytes and echo them back in one write
                                collectBytes(tlsConn, payloadSize).flatMap { allData =>
                                    tlsConn.write(allData).andThen {
                                        echoResult.complete(Result.succeed(allData.size)).unit
                                    }
                                }
                            }
                        }
                    }.unit
                }
                port = listener.port
                result <- transport.connect("127.0.0.1", port).map { plainConn =>
                    plainConn.write(Span.from(Array[Byte]('U'))).andThen {
                        plainConn.upgradeToTls(clientTls).flatMap { tlsConn =>
                            val payload = Array.fill[Byte](payloadSize)(42)
                            tlsConn.write(Span.from(payload)).andThen {
                                // Collect all echoed bytes
                                collectBytes(tlsConn, payloadSize).map { received =>
                                    assert(
                                        received.size == payloadSize,
                                        s"Expected $payloadSize bytes echoed, got ${received.size}"
                                    )
                                }
                            }
                        }
                    }
                }
            yield result
        }
    }

end UpgradeTlsTest
