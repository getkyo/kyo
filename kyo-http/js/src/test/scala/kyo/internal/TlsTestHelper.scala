package kyo.internal

import kyo.*
import scala.scalajs.js

/** Generates ephemeral TLS certificates for tests using openssl via child_process.execSync.
  *
  * No external dependencies beyond openssl. Certs are generated once and cached. The Node modules are reached through
  * `process.getBuiltinModule` at first use rather than a static import, so linking the test bundle adds no `node:` import.
  */
object TlsTestHelper:

    private def builtin(id: String): js.Dynamic =
        PlatformJs.nodeBuiltin(id).getOrElse(throw new IllegalStateException(s"TlsTestHelper needs $id, which the host does not provide"))

    lazy val (certPath, keyPath) =
        val path     = builtin("node:path")
        val tmpDir   = builtin("node:os").tmpdir()
        val certFile = path.join(tmpDir, "kyo-tls-cert.pem").asInstanceOf[String]
        val keyFile  = path.join(tmpDir, "kyo-tls-key.pem").asInstanceOf[String]
        val cmd =
            s"""openssl req -x509 -newkey rsa:2048 -keyout "$keyFile" -out "$certFile" -days 365 -nodes -subj "/CN=localhost" 2>&1"""
        discard(builtin("node:child_process").execSync(cmd))
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
