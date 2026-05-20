package kyo.internal

import kyo.*
import scala.scalajs.js

/** Generates ephemeral TLS certificates for tests using openssl via child_process.execSync.
  *
  * No external dependencies beyond openssl. Certs are generated once and cached.
  */
object TlsTestHelper:

    private val fs           = js.Dynamic.global.require("fs")
    private val os           = js.Dynamic.global.require("os")
    private val childProcess = js.Dynamic.global.require("child_process")
    private val path         = js.Dynamic.global.require("path")

    lazy val (certPath, keyPath): (String, String) =
        val tmpDir   = os.tmpdir().asInstanceOf[String]
        val certFile = path.join(tmpDir, "kyo-tls-cert.pem").asInstanceOf[String]
        val keyFile  = path.join(tmpDir, "kyo-tls-key.pem").asInstanceOf[String]
        val cmd =
            s"""openssl req -x509 -newkey rsa:2048 -keyout "$keyFile" -out "$certFile" -days 365 -nodes -subj "/CN=localhost" 2>&1"""
        childProcess.execSync(cmd)
        (certFile, keyFile)
    end val

    /** Server TLS config with self-signed cert for localhost. */
    lazy val serverTlsConfig: HttpTlsConfig = HttpTlsConfig(
        certChainPath = Present(certPath),
        privateKeyPath = Present(keyPath)
    )

    /** Client TLS config that trusts any certificate (for connecting to self-signed test servers). */
    lazy val clientTlsConfig: HttpTlsConfig = HttpTlsConfig(
        trustAll = true
    )

end TlsTestHelper
