package kyo.net.internal

import kyo.*
import kyo.net.NetTlsConfig
import kyo.net.Test

/** Regression test for finding #14 (reframed): a verifying client whose configured CA did NOT sign the server certificate MUST reject the
  * handshake. This pins the EXISTING correct behavior (verify mode 2 / `FAIL_IF_NO_PEER_CERT` causes the handshake to abort on a chain
  * validation error) so a future change that relaxes verify mode or wires a different trust store would fail this test loudly.
  *
  * Distinct from [[BoringSslProviderHostnameVerificationTest]], which tests wrong-NAME-but-trusted-chain rejection (the client pins the
  * wrong-name cert as its own CA so the chain validates, then isolates the host-name check as the only failure point). Here the chain itself
  * fails: the client pins [[TlsTestCert]] as its CA (CN=localhost, self-signed), but the server presents [[TlsWrongNameCert]] (CN=evil.example.com,
  * a DIFFERENT self-signed cert signed by a DIFFERENT key). `TlsTestCert` did NOT issue `TlsWrongNameCert`, so the chain is untrusted and
  * the handshake must be aborted by the engine before any record-layer data is exchanged.
  *
  * Two leaves cover both native providers: BoringSSL (priority-30 primary, [[BoringSslProvider]]) and system OpenSSL (priority-20 Native
  * fallback, [[SystemOpenSslProvider]]). Each leaf cancels where the relevant provider is not staged, the same pattern
  * [[BoringSslProviderConfiguredPemTest]] uses. A positive control is included for each provider: the same verifying client connecting to a
  * server presenting the cert actually signed by its configured CA ([[TlsTestCert]] server, [[TlsTestCert]] CA) must COMPLETE. This pins both
  * the failure and the boundary of the failure so a fix that rejects every handshake does not pass.
  *
  * Anti-flakiness: all leaves use [[TlsEngineLoopback]] (ciphertext shuttled through feed/drain in memory, no socket, no sleep). Engine
  * allocation and free are synchronous and deterministic.
  */
class BoringSslProviderUntrustedChainTest extends Test:

    import AllowUnsafe.embrace.danger

    /** A server engine presenting [[TlsWrongNameCert]] (CN=evil.example.com, self-signed, issued by its own key). The client's configured CA
      * ([[TlsTestCert]]) did NOT sign this certificate, so a verifying client that pins TlsTestCert as its CA must reject the chain.
      */
    private def untrustedServer(create: (NetTlsConfig, String, Boolean) => TlsEngine)(using Frame): TlsEngine =
        create(
            NetTlsConfig(
                certChainPath = Present(TlsWrongNameCert.certPath),
                privateKeyPath = Present(TlsWrongNameCert.keyPath)
            ),
            "evil.example.com",
            true
        )

    /** A verifying client that pins [[TlsTestCert]] as its CA (`hostnameVerification = true`, `trustAll = false`, both defaults). When the
      * server presents [[TlsWrongNameCert]], which was NOT signed by TlsTestCert, the chain is untrusted and the handshake must be rejected.
      */
    private def verifyingClient(create: (NetTlsConfig, String, Boolean) => TlsEngine)(using Frame): TlsEngine =
        create(
            NetTlsConfig(caCertPath = Present(TlsTestCert.certPath)),
            "localhost",
            false
        )

    /** A server engine presenting [[TlsTestCert]] (CN=localhost, self-signed). This IS the cert the client pins as its CA, so the chain
      * validates. Used by the positive controls to verify only the bad-chain scenario rejects, not all verifying handshakes.
      */
    private def trustedServer(create: (NetTlsConfig, String, Boolean) => TlsEngine)(using Frame): TlsEngine =
        create(
            NetTlsConfig(
                certChainPath = Present(TlsTestCert.certPath),
                privateKeyPath = Present(TlsTestCert.keyPath)
            ),
            "localhost",
            true
        )

    // Positive control (BoringSSL): the verifying client against a server that presents the cert its CA DID sign must COMPLETE. Pinning the
    // expected-good outcome so the test set is not trivially satisfied by a fix that rejects every handshake.
    // Anti-flakiness: in-memory loopback, no socket, no sleep.
    "BoringSSL: verifying client with a matching CA completes the handshake (positive control)" in {
        if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
        Sync.defer {
            val client = verifyingClient(BoringSslProvider.createEngine)
            val server = trustedServer(BoringSslProvider.createEngine)
            try
                val completed = TlsEngineLoopback.handshake(client, server)
                assert(
                    completed,
                    "verifying client rejected a handshake against a cert signed by its own configured CA: " +
                        "chain validation is too strict or the positive control is misconfigured"
                )
            finally
                client.free()
                server.free()
            end try
        }
    }

    // Regression guard (BoringSSL): a verifying client that pins TlsTestCert as its CA, but the server presents TlsWrongNameCert (signed by
    // a DIFFERENT key, not by TlsTestCert), must REJECT the handshake on a chain validation failure. The verify mode is 2
    // (SSL_VERIFY_PEER | FAIL_IF_NO_PEER_CERT), which causes the handshake to abort on any certificate verification error. This assertion
    // PASSES today and is a regression guard: it FAILS if a future change relaxes verify mode, wires the wrong trust store, or otherwise
    // causes an untrusted chain to be accepted.
    // Anti-flakiness: in-memory loopback, no socket, no sleep.
    "BoringSSL: verifying client rejects a server cert signed by a different CA (untrusted chain)" in {
        if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
        Sync.defer {
            val client = verifyingClient(BoringSslProvider.createEngine)
            val server = untrustedServer(BoringSslProvider.createEngine)
            try
                val completed = TlsEngineLoopback.handshake(client, server)
                assert(
                    !completed,
                    "SECURITY REGRESSION: verifying client accepted a cert signed by a CA it does not trust; " +
                        "verify mode 2 should have aborted the handshake on the chain validation error"
                )
            finally
                client.free()
                server.free()
            end try
        }
    }

    // Positive control (OpenSSL): mirrors the BoringSSL positive control for the system OpenSSL provider.
    // Anti-flakiness: in-memory loopback, no socket, no sleep.
    "OpenSSL: verifying client with a matching CA completes the handshake (positive control)" in {
        if !TlsRealEngines.openSslAvailable() then cancel("system OpenSSL not available for this host")
        Sync.defer {
            val client = verifyingClient(SystemOpenSslProvider.createEngine)
            val server = trustedServer(SystemOpenSslProvider.createEngine)
            try
                val completed = TlsEngineLoopback.handshake(client, server)
                assert(
                    completed,
                    "verifying client rejected a handshake against a cert signed by its own configured CA: " +
                        "chain validation is too strict or the positive control is misconfigured"
                )
            finally
                client.free()
                server.free()
            end try
        }
    }

    // Regression guard (OpenSSL): mirrors the BoringSSL regression guard for the system OpenSSL provider.
    // Anti-flakiness: in-memory loopback, no socket, no sleep.
    "OpenSSL: verifying client rejects a server cert signed by a different CA (untrusted chain)" in {
        if !TlsRealEngines.openSslAvailable() then cancel("system OpenSSL not available for this host")
        Sync.defer {
            val client = verifyingClient(SystemOpenSslProvider.createEngine)
            val server = untrustedServer(SystemOpenSslProvider.createEngine)
            try
                val completed = TlsEngineLoopback.handshake(client, server)
                assert(
                    !completed,
                    "SECURITY REGRESSION: verifying client accepted a cert signed by a CA it does not trust; " +
                        "verify mode 2 should have aborted the handshake on the chain validation error"
                )
            finally
                client.free()
                server.free()
            end try
        }
    }

end BoringSslProviderUntrustedChainTest
