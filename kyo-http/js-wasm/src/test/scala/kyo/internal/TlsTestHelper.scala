package kyo.internal

import kyo.*
import kyo.net.NetTlsConfig
import scala.scalajs.js

/** Generates ephemeral TLS certificates for tests using openssl via child_process.execSync.
  *
  * No external dependencies beyond openssl. Certs are generated once and cached.
  */
object TlsTestHelper:

    private val childProcess = HttpChildProcess.asInstanceOf[js.Dynamic]

    lazy val (certPath, keyPath): (String, String) =
        val tmpDir   = HttpOs.tmpdir()
        val certFile = HttpNodePath.join(tmpDir, "kyo-tls-cert.pem")
        val keyFile  = HttpNodePath.join(tmpDir, "kyo-tls-key.pem")
        val cmd =
            s"""openssl req -x509 -newkey rsa:2048 -keyout "$keyFile" -out "$certFile" -days 365 -nodes -subj "/CN=localhost" 2>&1"""
        childProcess.execSync(cmd)
        (certFile, keyFile)
    end val

    /** Server TLS config with self-signed cert for localhost. */
    lazy val serverTlsConfig: NetTlsConfig = NetTlsConfig(
        certChainPath = Present(certPath),
        privateKeyPath = Present(keyPath)
    )

    /** Client TLS config that trusts any certificate (for connecting to self-signed test servers). */
    lazy val clientTlsConfig: NetTlsConfig = NetTlsConfig(
        trustAll = true
    )

end TlsTestHelper
