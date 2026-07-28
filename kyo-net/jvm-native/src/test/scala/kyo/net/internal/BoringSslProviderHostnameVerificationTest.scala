package kyo.net.internal

import kyo.*
import kyo.net.NetTlsConfig
import kyo.net.Test

/** Reproduce-first hostname-verification tests for the BoringSSL provider ([[BoringSslProvider]], priority-30 primary on JVM and Native).
  *
  * Every other TLS test in the suite sets `trustAll = true` on the client, so the certificate- and hostname-verification code paths ship with
  * zero coverage. These leaves drive a VERIFYING client (non-`trustAll`, with the peer cert pinned via `caCertPath` so the chain validates)
  * against a server whose certificate identity does NOT match the host the client asked for, isolating the host-name check (RFC 6125 §6) as the
  * only thing that can reject the handshake.
  *
  * Two engines are built per leaf via [[BoringSslProvider.createEngine]] and driven to completion (or rejection) by the in-memory
  * [[TlsEngineLoopback]] (ciphertext shuttled through feed/drain, no socket, no timing). The negative control pins the wrong-name cert through
  * its own CA and asks for `localhost`: the chain validates but the name mismatches, so the handshake MUST fail. The empty-host leaf is the
  * bug: a client that passes hostname `""` (the STARTTLS-without-SNI case, [[kyo.net.internal.posix.PosixTransport]] `upgradeHost`) gets no
  * `SSL_set1_host` call in the shim (`kyo_net_boringssl.c` lines 180-184), so NO name check runs and a cert minted for a different identity is
  * accepted even though the client never named that identity.
  *
  * Gate: each leaf cancels where no BoringSSL provider is staged, so a host without the bundle is not a failure.
  */
class BoringSslProviderHostnameVerificationTest extends Test:

    import AllowUnsafe.embrace.danger

    /** A server engine over [[TlsWrongNameCert]] (identity `evil.example.com`). */
    private def wrongNameServer()(using Frame): TlsEngine =
        BoringSslProvider.createEngine(
            NetTlsConfig(
                certChainPath = Present(TlsWrongNameCert.certPath),
                privateKeyPath = Present(TlsWrongNameCert.keyPath)
            ),
            "evil.example.com",
            isServer = true
        )

    /** A verifying client that pins the wrong-name cert as its own CA (so the chain validates) and intends to reach `hostname`. */
    private def verifyingClient(hostname: String)(using Frame): TlsEngine =
        BoringSslProvider.createEngine(
            NetTlsConfig(caCertPath = Present(TlsWrongNameCert.certPath)),
            hostname,
            isServer = false
        )

    // Positive control: a verifying client that asks for "localhost" against a cert valid only for "evil.example.com" MUST reject the
    // handshake on the name mismatch, even though the cert chain validates (the cert is pinned as the CA). This pins the expected RFC 6125
    // behavior; if it fails, hostname verification is broken outright.
    // Anti-flakiness: the loopback completes or rejects synchronously in memory; no socket, no sleep.
    "a verifying client rejects a chain-valid cert whose identity does not match the requested host" in {
        if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
        Sync.defer {
            val client = verifyingClient("localhost")
            val server = wrongNameServer()
            try
                val completed = TlsEngineLoopback.handshake(client, server)
                assert(
                    !completed,
                    "handshake completed against a cert valid for evil.example.com while the client asked for localhost: hostname verification did not run"
                )
            finally
                client.free()
                server.free()
            end try
        }
    }

    // The bug (RFC 6125 §6 violation): a verifying client that passes the EMPTY hostname (the STARTTLS-without-SNI / connect-by-no-host case)
    // gets NO SSL_set1_host call in the shim, so the name check never runs. The chain still validates (cert pinned as CA), so a certificate
    // minted for a DIFFERENT identity (evil.example.com) is accepted. The CORRECT behavior is to reject any peer when no reference identity was
    // bound (fail closed); the OBSERVED behavior is acceptance. This assertion FAILS today, documenting the insecure default.
    // Anti-flakiness: synchronous in-memory loopback; no socket, no sleep.
    "a verifying client with an empty host accepts a chain-valid cert for an unrequested identity (CURRENTLY ACCEPTED, should be rejected)" in {
        if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
        Sync.defer {
            val client = verifyingClient("")
            val server = wrongNameServer()
            try
                val completed = TlsEngineLoopback.handshake(client, server)
                assert(
                    !completed,
                    "SECURITY: empty-host verifying client accepted a cert for evil.example.com with no host binding; " +
                        "the empty hostname skipped SSL_set1_host so no RFC 6125 name check ran"
                )
            finally
                client.free()
                server.free()
            end try
        }
    }

end BoringSslProviderHostnameVerificationTest
