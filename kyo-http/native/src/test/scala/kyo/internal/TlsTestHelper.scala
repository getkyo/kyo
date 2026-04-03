package kyo.internal

import kyo.*

/** Generates ephemeral TLS certificates for tests using openssl (available on macOS and Linux).
  *
  * No external dependencies beyond openssl. Certs are generated once and cached.
  */
object TlsTestHelper:

    lazy val (certPath, keyPath): (String, String) =
        val certFile = java.io.File.createTempFile("kyo-tls-cert", ".pem")
        val keyFile  = java.io.File.createTempFile("kyo-tls-key", ".pem")
        certFile.deleteOnExit()
        keyFile.deleteOnExit()
        val cmd = Array(
            "openssl",
            "req",
            "-x509",
            "-newkey",
            "rsa:2048",
            "-keyout",
            keyFile.getAbsolutePath,
            "-out",
            certFile.getAbsolutePath,
            "-days",
            "365",
            "-nodes",
            "-subj",
            "/CN=localhost"
        )
        val proc   = new ProcessBuilder(cmd*).redirectErrorStream(true).start()
        val output = new String(proc.getInputStream.readAllBytes())
        val exit   = proc.waitFor()
        if exit != 0 then
            throw new RuntimeException(s"openssl failed (exit=$exit): $output")
        (certFile.getAbsolutePath, keyFile.getAbsolutePath)
    end val

    /** Server TLS config with self-signed cert for localhost. */
    lazy val serverTlsConfig: TlsConfig = TlsConfig(
        certChainPath = Present(certPath),
        privateKeyPath = Present(keyPath)
    )

    /** Client TLS config that trusts any certificate (for connecting to self-signed test servers). */
    lazy val clientTlsConfig: TlsConfig = TlsConfig(
        trustAll = true
    )

end TlsTestHelper
