package kyo.net

import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import kyo.*

/** JVM-provider-specific Connection.serverCertificateHash introspection (RFC 5929 tls-server-end-point). The cross-backend contract (Present
  * with the 32-byte golden leaf-cert SHA-256, idempotent, Absent after close, none for a plaintext connection) lives in the shared
  * TransportTlsIntrospectionTest; this suite keeps the JVM-specific coverage the shared self-signed fixture cannot give: the live SHA-256 of a
  * keytool-generated cert's DER, and a multi-cert chain proving the leaf (not the CA) is hashed.
  *
  * Each test spins up a TLS echo server using Transport.listen-with-TLS and connects via Transport.connect-with-TLS, then
  * inspects the hash returned by serverCertificateHash on the client side.
  *
  * Certificate material is generated once per fixture via keytool (ships with every JDK). No external tools or BouncyCastle required.
  *
  * Test naming: ConnectionTlsIntrospection satisfies naming rule 1 because Connection is a prefix of ConnectionTlsIntrospection.
  */
class ConnectionTlsIntrospectionTest extends Test:

    import AllowUnsafe.embrace.danger

    // Self-signed cert fixture (used by leaves 1, 2, 3, 4, 6, 7)

    /** Generate a self-signed cert via keytool. Returns (serverTls, clientTls, leafDerBytes). */
    private lazy val selfSignedFixture: (NetTlsConfig, NetTlsConfig, Array[Byte]) =
        val ksFile = File.createTempFile("kyo-tls-introspection", ".p12")
        ksFile.delete()
        ksFile.deleteOnExit()
        val ksPath = ksFile.getAbsolutePath

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
        runCmd(cmd)

        val ks = loadP12(ksPath, "changeit")

        val cert     = ks.getCertificate("server")
        val derBytes = cert.getEncoded

        val enc = java.util.Base64.getMimeEncoder(64, "\n".getBytes)

        val certFile = writePem("-----BEGIN CERTIFICATE-----", enc.encodeToString(derBytes), "-----END CERTIFICATE-----")
        val keyFile = writePem(
            "-----BEGIN PRIVATE KEY-----",
            enc.encodeToString(ks.getKey("server", "changeit".toCharArray).getEncoded),
            "-----END PRIVATE KEY-----"
        )

        val serverTls = NetTlsConfig(certChainPath = Present(certFile.getAbsolutePath), privateKeyPath = Present(keyFile.getAbsolutePath))
        val clientTls = NetTlsConfig(trustAll = true)
        (serverTls, clientTls, derBytes)
    end selfSignedFixture

    // Chain cert fixture (used by the certificate-chain test)

    /** Generate a 2-level chain (CA + leaf server cert) via keytool. Returns (serverTls, clientTls, leafDerBytes, caDerBytes). */
    private lazy val chainFixture: (NetTlsConfig, NetTlsConfig, Array[Byte], Array[Byte]) =
        val caKsFile = File.createTempFile("kyo-tls-ca", ".p12")
        caKsFile.delete()
        caKsFile.deleteOnExit()
        val caKsPath = caKsFile.getAbsolutePath

        runCmd(Array(
            "keytool",
            "-genkeypair",
            "-alias",
            "ca",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "36500",
            "-dname",
            "CN=TestCA",
            "-ext",
            "bc:critical=ca:true",
            "-storetype",
            "PKCS12",
            "-keystore",
            caKsPath,
            "-storepass",
            "changeit",
            "-keypass",
            "changeit"
        ))

        val leafKsFile = File.createTempFile("kyo-tls-leaf", ".p12")
        leafKsFile.delete()
        leafKsFile.deleteOnExit()
        val leafKsPath = leafKsFile.getAbsolutePath

        runCmd(Array(
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
            leafKsPath,
            "-storepass",
            "changeit",
            "-keypass",
            "changeit"
        ))

        val csrFile = File.createTempFile("kyo-tls-leaf", ".csr")
        csrFile.deleteOnExit()
        runCmd(Array(
            "keytool",
            "-certreq",
            "-alias",
            "server",
            "-storetype",
            "PKCS12",
            "-keystore",
            leafKsPath,
            "-storepass",
            "changeit",
            "-keypass",
            "changeit",
            "-file",
            csrFile.getAbsolutePath
        ))

        val leafSignedFile = File.createTempFile("kyo-tls-leaf-signed", ".pem")
        leafSignedFile.deleteOnExit()
        runCmd(Array(
            "keytool",
            "-gencert",
            "-alias",
            "ca",
            "-storetype",
            "PKCS12",
            "-keystore",
            caKsPath,
            "-storepass",
            "changeit",
            "-keypass",
            "changeit",
            "-validity",
            "36500",
            "-ext",
            "SAN=dns:localhost,ip:127.0.0.1",
            "-infile",
            csrFile.getAbsolutePath,
            "-outfile",
            leafSignedFile.getAbsolutePath,
            "-rfc"
        ))

        val caCertExport = File.createTempFile("kyo-tls-ca-cert", ".pem")
        caCertExport.deleteOnExit()
        runCmd(Array(
            "keytool",
            "-exportcert",
            "-alias",
            "ca",
            "-storetype",
            "PKCS12",
            "-keystore",
            caKsPath,
            "-storepass",
            "changeit",
            "-file",
            caCertExport.getAbsolutePath,
            "-rfc"
        ))

        runCmd(Array(
            "keytool",
            "-importcert",
            "-noprompt",
            "-alias",
            "ca",
            "-storetype",
            "PKCS12",
            "-keystore",
            leafKsPath,
            "-storepass",
            "changeit",
            "-file",
            caCertExport.getAbsolutePath
        ))

        runCmd(Array(
            "keytool",
            "-importcert",
            "-noprompt",
            "-alias",
            "server",
            "-storetype",
            "PKCS12",
            "-keystore",
            leafKsPath,
            "-storepass",
            "changeit",
            "-keypass",
            "changeit",
            "-file",
            leafSignedFile.getAbsolutePath
        ))

        val caKs   = loadP12(caKsPath, "changeit")
        val leafKs = loadP12(leafKsPath, "changeit")

        val caCert   = caKs.getCertificate("ca")
        val leafCert = leafKs.getCertificate("server")

        val caDer   = caCert.getEncoded
        val leafDer = leafCert.getEncoded

        val enc = java.util.Base64.getMimeEncoder(64, "\n".getBytes)

        val chainPem = s"""-----BEGIN CERTIFICATE-----
${enc.encodeToString(leafDer)}
-----END CERTIFICATE-----
-----BEGIN CERTIFICATE-----
${enc.encodeToString(caDer)}
-----END CERTIFICATE-----
"""
        val chainFile = File.createTempFile("kyo-tls-chain", ".pem")
        chainFile.deleteOnExit()
        java.nio.file.Files.writeString(chainFile.toPath, chainPem)

        val keyFile = writePem(
            "-----BEGIN PRIVATE KEY-----",
            enc.encodeToString(leafKs.getKey("server", "changeit".toCharArray).getEncoded),
            "-----END PRIVATE KEY-----"
        )

        val serverTls = NetTlsConfig(certChainPath = Present(chainFile.getAbsolutePath), privateKeyPath = Present(keyFile.getAbsolutePath))
        val clientTls = NetTlsConfig(trustAll = true)
        (serverTls, clientTls, leafDer, caDer)
    end chainFixture

    // Helper utilities

    private def runCmd(cmd: Array[String]): Unit =
        val pb = new ProcessBuilder(cmd*)
        pb.redirectErrorStream(true)
        val proc   = pb.start()
        val output = new String(proc.getInputStream.readAllBytes())
        val exit   = proc.waitFor()
        if exit != 0 then
            throw new RuntimeException(s"Command failed (exit=$exit): ${cmd.take(2).mkString(" ")}\n$output")
    end runCmd

    private def loadP12(path: String, pass: String): KeyStore =
        val ks  = KeyStore.getInstance("PKCS12")
        val fis = new java.io.FileInputStream(path)
        try ks.load(fis, pass.toCharArray)
        finally fis.close()
        ks
    end loadP12

    private def writePem(header: String, body: String, footer: String): File =
        val f = File.createTempFile("kyo-tls-pem", ".pem")
        f.deleteOnExit()
        java.nio.file.Files.writeString(f.toPath, s"$header\n$body\n$footer\n")
        f
    end writePem

    private def sha256(bytes: Array[Byte]): Array[Byte] =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Spin up a TLS echo server, connect with TLS, run body with the client connection. */
    private def withTlsConnection[A](serverTls: NetTlsConfig, clientTls: NetTlsConfig)(
        body: Connection => A < (Async & Abort[Closed])
    ): A < (Async & Abort[Closed]) =
        val transport = NetPlatform.transport
        for
            listener <- transport.listen("127.0.0.1", 0, 128, serverTls) { serverConn =>
                discard(Sync.Unsafe.evalOrThrow {
                    Fiber.initUnscoped {
                        Abort.run[Closed] {
                            serverConn.inbound.safe.take.flatMap { bytes =>
                                serverConn.outbound.safe.put(bytes)
                            }
                        }.unit
                    }
                })
            }.safe.get
            port = listener.port
            conn   <- transport.connect("127.0.0.1", port, clientTls).safe.get
            result <- body(conn)
        yield
            conn.close()
            listener.close()
            result
        end for
    end withTlsConnection

    // hash equals SHA-256 of the test cert DER bytes

    "hash equals SHA-256 of the test cert DER bytes" in {
        val (serverTls, clientTls, leafDer) = selfSignedFixture
        val expectedHash                    = sha256(leafDer)
        withTlsConnection(serverTls, clientTls) { conn =>
            conn.serverCertificateHash match
                case Absent =>
                    fail("serverCertificateHash must return Present for a TLS connection, got Absent")
                case Present(hash) =>
                    val actualBytes = hash.toArray
                    assert(
                        java.util.Arrays.equals(actualBytes, expectedHash),
                        s"Hash mismatch: expected ${expectedHash.map("%02x".format(_)).mkString} but got ${actualBytes.map("%02x".format(_)).mkString}"
                    )
        }
    }

    // client cert not relevant, only server's cert is hashed

    "client cert not relevant, only server's cert is hashed" in {
        val (serverTls, _, leafDer) = selfSignedFixture
        val clientTlsNoAuth         = NetTlsConfig(trustAll = true)
        val expectedHash            = sha256(leafDer)
        withTlsConnection(serverTls, clientTlsNoAuth) { conn =>
            conn.serverCertificateHash match
                case Absent =>
                    fail("serverCertificateHash must return Present even without client auth, got Absent")
                case Present(hash) =>
                    val actualBytes = hash.toArray
                    assert(
                        java.util.Arrays.equals(actualBytes, expectedHash),
                        s"Hash must match server cert DER regardless of client auth mode"
                    )
        }
    }

    // hash returns leaf cert (not intermediate or root) when server presents a chain

    "hash returns leaf cert (not intermediate or root) when server presents a chain" in {
        val (serverTls, clientTls, leafDer, caDer) = chainFixture
        val expectedLeafHash                       = sha256(leafDer)
        val caHash                                 = sha256(caDer)
        withTlsConnection(serverTls, clientTls) { conn =>
            conn.serverCertificateHash match
                case Absent =>
                    fail("serverCertificateHash must return Present for a chain-cert TLS connection, got Absent")
                case Present(hash) =>
                    val actualBytes = hash.toArray
                    assert(
                        java.util.Arrays.equals(actualBytes, expectedLeafHash),
                        s"serverCertificateHash must hash the leaf cert (index 0), not the CA cert"
                    )
                    assert(
                        !java.util.Arrays.equals(actualBytes, caHash),
                        "serverCertificateHash must NOT return the CA cert hash"
                    )
        }
    }

end ConnectionTlsIntrospectionTest
