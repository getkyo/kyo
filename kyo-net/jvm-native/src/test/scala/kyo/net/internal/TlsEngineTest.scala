package kyo.net.internal

import kyo.*
import kyo.ffi.Ffi
import kyo.net.NetTlsConfig
import kyo.net.Test

/** BoringSSL + OpenSSL [[TlsEngine]] handshake + RFC 5929 cert-binding tests, shared across JVM (Panama) and Native (`@extern`).
  *
  * Each BoringSSL leaf builds a client and a server [[NativeSslEngine]] over the bundled-BoringSSL backing and the fixed [[TlsTestCert]],
  * drives the handshake through the in-memory [[TlsEngineLoopback]] (ciphertext shuttled via feed/drain, no socket), then asserts the
  * negotiated session: a plaintext round-trip is byte-equal and `certSha256()` equals the precomputed golden 32 bytes on both platforms. The
  * OpenSSL leaves mirror that with a [[NativeSslEngine]] over the system-OpenSSL `kyonet_openssl` shim, gated on `probeAvailable()`.
  * A final interop leaf crosses the two engines (BoringSSL client against OpenSSL server and the reverse) to prove the prefixed shims share
  * one TLS implementation on the Native binary with no symbol clash. Each leaf cancels when its engine is unavailable for the host
  * (BoringSSL not staged / OpenSSL absent), so a host without an engine is not a failure.
  */
class TlsEngineTest extends Test:

    import AllowUnsafe.embrace.danger

    private def boringSslAvailable: Boolean =
        try Ffi.load[BoringSslBindings].probeAvailable()
        catch case _: Throwable => false

    private def openSslAvailable: Boolean =
        try Ffi.load[OpenSslBindings].probeAvailable()
        catch case _: Throwable => false

    private val serverConfig = NetTlsConfig(
        certChainPath = Present(TlsTestCert.certPath),
        privateKeyPath = Present(TlsTestCert.keyPath)
    )
    private val clientConfig = NetTlsConfig(trustAll = true)

    private def withEngines[A](body: (TlsEngine, TlsEngine) => A): A =
        val client = BoringSslProvider.createEngine(clientConfig, "localhost", isServer = false)
        val server = BoringSslProvider.createEngine(serverConfig, "localhost", isServer = true)
        try body(client, server)
        finally
            client.free()
            server.free()
        end try
    end withEngines

    private def withOpenSslEngines[A](body: (TlsEngine, TlsEngine) => A): A =
        val client = SystemOpenSslProvider.createEngine(clientConfig, "localhost", isServer = false)
        val server = SystemOpenSslProvider.createEngine(serverConfig, "localhost", isServer = true)
        try body(client, server)
        finally
            client.free()
            server.free()
        end try
    end withOpenSslEngines

    "BoringSslEngine handshake completes over an in-memory BIO pair and round-trips plaintext byte-equal" in {
        if !boringSslAvailable then cancel("BoringSSL not staged for this host")
        Sync.defer {
            withEngines { (client, server) =>
                val done = TlsEngineLoopback.handshake(client, server)
                assert(done, "handshake did not complete on both sides")
                val plaintext = "hello-tls-boringssl".getBytes("UTF-8")
                val echoed    = TlsEngineLoopback.roundTrip(client, server, plaintext)
                assert(echoed.sameElements(plaintext), s"plaintext round-trip mismatch: got ${new String(echoed, "UTF-8")}")
            }
        }
    }

    "certSha256 returns the precomputed 32-byte golden value (RFC 5929) and is byte-identical JVM+Native" in {
        if !boringSslAvailable then cancel("BoringSSL not staged for this host")
        Sync.defer {
            withEngines { (client, server) =>
                assert(TlsEngineLoopback.handshake(client, server), "handshake did not complete")
                client.certSha256() match
                    case Present(hash) =>
                        val bytes = hash.toArrayUnsafe
                        assert(bytes.length == 32, s"expected 32 bytes, got ${bytes.length}")
                        assert(bytes.sameElements(TlsTestCert.certGoldenSha256), "certSha256 did not match the golden value")
                    case Absent =>
                        fail("certSha256 was Absent after a completed handshake with a peer cert")
                end match
            }
        }
    }

    "certSha256 is Absent when there is no peer certificate" in {
        if !boringSslAvailable then cancel("BoringSSL not staged for this host")
        Sync.defer {
            // A fresh client engine with no completed handshake has no peer cert.
            val client = BoringSslProvider.createEngine(clientConfig, "localhost", isServer = false)
            try assert(client.certSha256() == Absent, "expected Absent with no peer cert")
            finally client.free()
        }
    }

    "OpenSslEngine handshake completes over an in-memory BIO pair and round-trips plaintext byte-equal" in {
        if !openSslAvailable then cancel("system OpenSSL not available for this host")
        Sync.defer {
            withOpenSslEngines { (client, server) =>
                val done = TlsEngineLoopback.handshake(client, server)
                assert(done, "handshake did not complete on both sides")
                val plaintext = "hello-tls-openssl".getBytes("UTF-8")
                val echoed    = TlsEngineLoopback.roundTrip(client, server, plaintext)
                assert(echoed.sameElements(plaintext), s"plaintext round-trip mismatch: got ${new String(echoed, "UTF-8")}")
            }
        }
    }

    "OpenSslEngine certSha256 returns the precomputed 32-byte golden value (RFC 5929), byte-identical to BoringSSL" in {
        if !openSslAvailable then cancel("system OpenSSL not available for this host")
        Sync.defer {
            withOpenSslEngines { (client, server) =>
                assert(TlsEngineLoopback.handshake(client, server), "handshake did not complete")
                client.certSha256() match
                    case Present(hash) =>
                        val bytes = hash.toArrayUnsafe
                        assert(bytes.length == 32, s"expected 32 bytes, got ${bytes.length}")
                        assert(bytes.sameElements(TlsTestCert.certGoldenSha256), "certSha256 did not match the golden value")
                    case Absent =>
                        fail("certSha256 was Absent after a completed handshake with a peer cert")
                end match
            }
        }
    }

    "OpenSslEngine certSha256 is Absent when there is no peer certificate" in {
        if !openSslAvailable then cancel("system OpenSSL not available for this host")
        Sync.defer {
            val client = SystemOpenSslProvider.createEngine(clientConfig, "localhost", isServer = false)
            try assert(client.certSha256() == Absent, "expected Absent with no peer cert")
            finally client.free()
        }
    }

    "BoringSSL and OpenSSL engines interoperate (no symbol clash): each as client against the other as server" in {
        if !boringSslAvailable then cancel("BoringSSL not staged for this host")
        if !openSslAvailable then cancel("system OpenSSL not available for this host")
        Sync.defer {
            // BoringSSL client <-> OpenSSL server.
            val bClient = BoringSslProvider.createEngine(clientConfig, "localhost", isServer = false)
            val oServer = SystemOpenSslProvider.createEngine(serverConfig, "localhost", isServer = true)
            try
                assert(TlsEngineLoopback.handshake(bClient, oServer), "BoringSSL-client/OpenSSL-server handshake did not complete")
                val msg1    = "boring-client-openssl-server".getBytes("UTF-8")
                val echoed1 = TlsEngineLoopback.roundTrip(bClient, oServer, msg1)
                assert(echoed1.sameElements(msg1), s"round-trip mismatch (B->O): got ${new String(echoed1, "UTF-8")}")
                // The cert binding the client computes over the OpenSSL server's leaf equals the same golden.
                bClient.certSha256() match
                    case Present(hash) => assert(hash.toArrayUnsafe.sameElements(TlsTestCert.certGoldenSha256), "B->O certSha256 mismatch")
                    case Absent        => fail("B->O certSha256 was Absent after a completed handshake")
                end match
            finally
                bClient.free()
                oServer.free()
            end try

            // OpenSSL client <-> BoringSSL server.
            val oClient = SystemOpenSslProvider.createEngine(clientConfig, "localhost", isServer = false)
            val bServer = BoringSslProvider.createEngine(serverConfig, "localhost", isServer = true)
            try
                assert(TlsEngineLoopback.handshake(oClient, bServer), "OpenSSL-client/BoringSSL-server handshake did not complete")
                val msg2    = "openssl-client-boring-server".getBytes("UTF-8")
                val echoed2 = TlsEngineLoopback.roundTrip(oClient, bServer, msg2)
                assert(echoed2.sameElements(msg2), s"round-trip mismatch (O->B): got ${new String(echoed2, "UTF-8")}")
                oClient.certSha256() match
                    case Present(hash) => assert(hash.toArrayUnsafe.sameElements(TlsTestCert.certGoldenSha256), "O->B certSha256 mismatch")
                    case Absent        => fail("O->B certSha256 was Absent after a completed handshake")
                end match
            finally
                oClient.free()
                bServer.free()
            end try
        }
    }

end TlsEngineTest
