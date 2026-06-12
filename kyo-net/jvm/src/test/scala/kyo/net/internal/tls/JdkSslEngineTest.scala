package kyo.net.internal.tls

import kyo.*
import kyo.ffi.Buffer
import kyo.net.NetTlsConfig
import kyo.net.Test

/** The JVM SSLEngine fallback arm of the TLS engine surface: `-Dkyo.net.tls=jdk` selects [[JdkSslEngine]], which must handshake to
  * completion against the same fixed [[TlsTestCert]] and produce the SAME RFC 5929 cert-binding bytes as BoringSSL (provider-agnostic
  * channel-binding parity). JVM-only because `JdkSslEngine` wraps `javax.net.ssl.SSLEngine`.
  */
class JdkSslEngineTest extends Test:

    import AllowUnsafe.embrace.danger

    private val serverConfig = NetTlsConfig(
        certChainPath = Present(TlsTestCert.certPath),
        privateKeyPath = Present(TlsTestCert.keyPath)
    )
    private val clientConfig = NetTlsConfig(trustAll = true, hostnameVerification = false)

    /** Drain every plaintext byte the engine can produce, exactly as the driver's `decryptAll` loop does: loop `readPlain` until it returns
      * `<= 0`. This is the surface that strands application data when a post-handshake record (NewSessionTicket) arrives coalesced ahead of
      * an application record in one `feedCiphertext`.
      */
    private def decryptAll(engine: TlsEngine, chunkSize: Int)(using AllowUnsafe): Array[Byte] =
        val acc  = new java.io.ByteArrayOutputStream
        var more = true
        while more do
            val out = Buffer.alloc[Byte](chunkSize)
            try
                val n = engine.readPlain(out, chunkSize)
                if n > 0 then acc.write(Buffer.copyToArray[Byte](out, 0, n))
                else more = false
            finally out.close()
            end try
        end while
        acc.toByteArray
    end decryptAll

    "JdkSslEngine recovers an application record that arrives coalesced behind a post-handshake record" in {
        Sync.defer {
            val client = SslEngineProvider.createEngine(clientConfig, "localhost", isServer = false)
            val server = SslEngineProvider.createEngine(serverConfig, "localhost", isServer = true)
            try
                val done = TlsEngineLoopback.handshake(client, server)
                assert(done, "JdkSslEngine handshake did not complete on both sides")

                // The JDK server emits NewSessionTicket post-handshake records on its next wrap. Encrypting the application response on the
                // server therefore drains a single ciphertext blob holding the session-ticket record(s) FOLLOWED BY the response record. On a
                // loopback (or epoll under load) that whole blob lands in the client in ONE feedCiphertext, exactly the coalescing the real
                // freeze trace shows (cipherIn=1251 plainOut=0). The client must still recover the full response: a single unwrap consumes
                // only the leading ticket record and yields zero application bytes, so a readPlain/decryptAll loop that stops at the first
                // zero would strand the response and deadlock the connection.
                val response = "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\npong".getBytes("UTF-8")
                val src      = Buffer.fromArray[Byte](response)
                try discard(server.writePlain(src, response.length))
                finally src.close()
                val coalesced = TlsEngineLoopback.drainAll(server)
                assert(
                    coalesced.length > response.length,
                    s"expected the server to coalesce post-handshake records ahead of the response, got ${coalesced.length} bytes"
                )

                val cipher = Buffer.fromArray[Byte](coalesced)
                try discard(client.feedCiphertext(cipher, coalesced.length))
                finally cipher.close()

                val decrypted = decryptAll(client, 16 * 1024)
                assert(
                    decrypted.sameElements(response),
                    s"response stranded behind the post-handshake record: got ${decrypted.length} bytes '${new String(decrypted, "UTF-8")}'"
                )
            finally
                client.free()
                server.free()
            end try
        }
    }

    "JdkSslEngine handshakes and produces the golden certSha256 bytes" in {
        Sync.defer {
            val client = SslEngineProvider.createEngine(clientConfig, "localhost", isServer = false)
            val server = SslEngineProvider.createEngine(serverConfig, "localhost", isServer = true)
            try
                val done = TlsEngineLoopback.handshake(client, server)
                assert(done, "JdkSslEngine handshake did not complete on both sides")
                val plaintext = "hello-tls-jdk".getBytes("UTF-8")
                val echoed    = TlsEngineLoopback.roundTrip(client, server, plaintext)
                assert(echoed.sameElements(plaintext), s"plaintext round-trip mismatch: got ${new String(echoed, "UTF-8")}")
                client.certSha256() match
                    case Present(hash) =>
                        val bytes = hash.toArrayUnsafe
                        assert(bytes.length == 32, s"expected 32 bytes, got ${bytes.length}")
                        assert(bytes.sameElements(TlsTestCert.certGoldenSha256), "JdkSslEngine certSha256 did not match the golden value")
                    case Absent =>
                        fail("certSha256 was Absent after a completed JdkSslEngine handshake")
                end match
            finally
                client.free()
                server.free()
            end try
        }
    }

    "JdkSslEngine enforces the version range and reports a no-common-version handshake as -2 (not a thrown SSLException)" in {
        Sync.defer {
            // Client pinned to TLS1.2, server to TLS1.3: no common version. SslEngineProvider must APPLY the pins (without enforcement the engines
            // negotiate a shared version and the handshake completes), and JdkSslEngine.handshakeStep must report the resulting SSLHandshakeException
            // as the -2 failure code so the driver aborts Closed, rather than letting it escape as a thrown Panic (which TlsEngineLoopback would
            // propagate instead of returning a clean false). Locks the two JDK-floor fixes the sbt-linux run surfaced (version pins + Closed wrap).
            val clientTls12 = NetTlsConfig(
                trustAll = true,
                hostnameVerification = false,
                minVersion = NetTlsConfig.Version.TLS12,
                maxVersion = NetTlsConfig.Version.TLS12
            )
            val serverTls13 = NetTlsConfig(
                certChainPath = Present(TlsTestCert.certPath),
                privateKeyPath = Present(TlsTestCert.keyPath),
                minVersion = NetTlsConfig.Version.TLS13,
                maxVersion = NetTlsConfig.Version.TLS13
            )
            val client = SslEngineProvider.createEngine(clientTls12, "localhost", isServer = false)
            val server = SslEngineProvider.createEngine(serverTls13, "localhost", isServer = true)
            try
                val done = TlsEngineLoopback.handshake(client, server)
                assert(
                    !done,
                    "a TLS1.2-only client and TLS1.3-only server must not complete a handshake: the version pins must be enforced and the " +
                        "no-common-version failure reported as -2"
                )
            finally
                client.free()
                server.free()
            end try
        }
    }

end JdkSslEngineTest
