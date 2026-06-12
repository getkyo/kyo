package kyo.net.internal.tls

import kyo.net.TlsTestCertShared

/** JVM/Native view of the shared self-signed test certificate (CN=localhost, SAN dns:localhost / ip:127.0.0.1, valid 100 years). The PEM
  * literals and the channel-binding golden are the canonical copy in [[kyo.net.TlsTestCertShared]]; this object re-exports them and adds the
  * synchronous temp-file write the JVM/Native posix suites use (they consume `certPath`/`keyPath` outside an effect context).
  *
  * `certGoldenSha256` is the precomputed SHA-256 of this certificate's leaf DER (`openssl x509 -outform DER | sha256`), the RFC 5929
  * tls-server-end-point golden the cert-binding tests assert against on both JVM and Native.
  */
object TlsTestCert:

    val certPem: String = TlsTestCertShared.certPem

    val keyPem: String = TlsTestCertShared.keyPem

    /** SHA-256 of the leaf certificate DER bytes (RFC 5929 tls-server-end-point), as 32 bytes. */
    val certGoldenSha256: Array[Byte] = TlsTestCertShared.certGoldenSha256

    /** Write the embedded cert and key to fresh temp files and return their absolute paths (used by `NetTlsConfig.certChainPath` /
      * `privateKeyPath`). The files are marked delete-on-exit.
      */
    lazy val (certPath, keyPath): (String, String) =
        val certFile = java.io.File.createTempFile("kyo-net-tls-cert", ".pem")
        val keyFile  = java.io.File.createTempFile("kyo-net-tls-key", ".pem")
        certFile.deleteOnExit()
        keyFile.deleteOnExit()
        val cw = new java.io.FileWriter(certFile)
        try cw.write(certPem)
        finally cw.close()
        val kw = new java.io.FileWriter(keyFile)
        try kw.write(keyPem)
        finally kw.close()
        (certFile.getAbsolutePath, keyFile.getAbsolutePath)
    end val

end TlsTestCert
