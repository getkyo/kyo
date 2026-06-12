package kyo.net

import kyo.*

/** Cross-backend, cross-TLS-implementation introspection for the public [[Connection.serverCertificateHash]] (RFC 5929 tls-server-end-point
  * channel binding).
  *
  * `serverCertificateHash` is observable through the public `Connection` surface and is wired on every backend (posix via the engine cert hash,
  * NIO via the SSLSession peer cert, JS via Node's `getPeerCertificate(true).raw`), so the contract is asserted over the full backend x TLS-impl
  * matrix: after a TLS handshake the client reports the SHA-256 of the server leaf certificate DER (32 bytes, matching the golden for the shared
  * test cert, which is fixed regardless of the implementation that produced it), the value is idempotent, and it is Absent after close. A
  * plaintext connection (no TLS impl) has none, asserted once per backend.
  */
class TransportTlsIntrospectionTest extends Test:

    import AllowUnsafe.embrace.danger

    "after a TLS handshake the client reports the server leaf-cert SHA-256 (32 bytes, golden), idempotent, Absent after close" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            for
                listener <- transport.listen("127.0.0.1", 0, 16, serverTls)(_ => ()).safe.get
                client   <- transport.connect("127.0.0.1", listener.port, clientTls).safe.get
            yield
                val hash      = client.serverCertificateHash
                val hashAgain = client.serverCertificateHash
                client.close()
                val afterClose = client.serverCertificateHash
                listener.close()
                assert(hash.nonEmpty, "serverCertificateHash must be Present after a TLS handshake")
                assert(
                    hash.exists(sp => sp.toArray.length == 32 && sp.toArray.sameElements(TlsTestCertShared.certGoldenSha256)),
                    s"serverCertificateHash must be the 32-byte golden leaf-cert SHA-256, got ${hash.map(_.toArray.length)} bytes"
                )
                assert(
                    hash.exists(a => hashAgain.exists(b => a.toArray.sameElements(b.toArray))),
                    "serverCertificateHash must be idempotent"
                )
                assert(afterClose.isEmpty, "serverCertificateHash must be Absent after close")
            end for
    }

    "a plaintext connection has no server certificate hash" - eachBackend { transport =>
        for
            listener <- transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get
            client   <- transport.connect("127.0.0.1", listener.port).safe.get
        yield
            val hash = client.serverCertificateHash
            client.close()
            listener.close()
            assert(hash.isEmpty, "a plaintext connection must have no serverCertificateHash")
        end for
    }

end TransportTlsIntrospectionTest
