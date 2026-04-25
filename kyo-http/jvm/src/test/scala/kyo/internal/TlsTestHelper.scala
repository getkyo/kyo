package kyo.internal

import java.io.File
import java.security.*
import java.security.cert.X509Certificate
import javax.net.ssl.*
import kyo.HttpTlsConfig

/** Generates ephemeral TLS certificates for tests using keytool (ships with every JDK).
  *
  * No external dependencies, works in CI. Certs are generated once and cached.
  */
object TlsTestHelper:

    private lazy val keystorePath: String =
        val tmpFile = File.createTempFile("kyo-tls-test", ".p12")
        tmpFile.delete() // keytool needs the file to not exist
        tmpFile.deleteOnExit()
        val path = tmpFile.getAbsolutePath
        // keytool ships with every JDK
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
            path,
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
        path
    end keystorePath

    private lazy val password = "changeit".toCharArray

    /** SSLContext with a self-signed cert for localhost. For server-side TLS. */
    lazy val serverSslContext: SSLContext =
        val ks  = KeyStore.getInstance("PKCS12")
        val fis = new java.io.FileInputStream(keystorePath)
        try ks.load(fis, password)
        finally fis.close()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
        kmf.init(ks, password)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.getKeyManagers, null, null)
        ctx
    end serverSslContext

    /** SSLContext that trusts any certificate. For client-side TLS connecting to self-signed servers. */
    lazy val trustAllSslContext: SSLContext =
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, Array(TrustAllManager), null)
        ctx
    end trustAllSslContext

    private object TrustAllManager extends X509TrustManager:
        def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
        def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
        def getAcceptedIssuers: Array[X509Certificate]                                = Array.empty
    end TrustAllManager

    /** Export the self-signed certificate and private key as PEM files from the PKCS12 keystore. */
    lazy val (certPath, keyPath): (String, String) =
        val ks  = KeyStore.getInstance("PKCS12")
        val fis = new java.io.FileInputStream(keystorePath)
        try ks.load(fis, password)
        finally fis.close()

        // Export certificate as PEM
        val cert = ks.getCertificate("server")
        val certPem = "-----BEGIN CERTIFICATE-----\n" + java.util.Base64.getMimeEncoder(
            64,
            "\n".getBytes
        ).encodeToString(cert.getEncoded) + "\n-----END CERTIFICATE-----\n"
        val certFile = java.io.File.createTempFile("kyo-tls-cert", ".pem")
        certFile.deleteOnExit()
        val cw = new java.io.FileWriter(certFile)
        cw.write(certPem)
        cw.close()

        // Export private key as PEM (PKCS#8)
        val key = ks.getKey("server", password).asInstanceOf[java.security.PrivateKey]
        val keyPem = "-----BEGIN PRIVATE KEY-----\n" + java.util.Base64.getMimeEncoder(
            64,
            "\n".getBytes
        ).encodeToString(key.getEncoded) + "\n-----END PRIVATE KEY-----\n"
        val keyFile = java.io.File.createTempFile("kyo-tls-key", ".pem")
        keyFile.deleteOnExit()
        val kw = new java.io.FileWriter(keyFile)
        kw.write(keyPem)
        kw.close()

        (certFile.getAbsolutePath, keyFile.getAbsolutePath)
    end val

    /** Server TLS config with self-signed cert for localhost. */
    lazy val serverTlsConfig: HttpTlsConfig = HttpTlsConfig(
        certChainPath = kyo.Present(certPath),
        privateKeyPath = kyo.Present(keyPath)
    )

    /** Client TLS config that trusts any certificate (for connecting to self-signed test servers). */
    lazy val clientTlsConfig: HttpTlsConfig = HttpTlsConfig(
        trustAll = true
    )

end TlsTestHelper
