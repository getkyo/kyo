package kyo.net.internal

import kyo.*
import kyo.net.NetTlsConfig
import kyo.net.Test

/** Reproduce-first cross-provider divergence test for the `hostnameVerification` flag of [[kyo.net.NetTlsConfig]].
  *
  * `NetTlsConfig.hostnameVerification = false` is documented to disable the server host-name check (the `sslmode=verify-ca` / chain-only
  * case): with it off, a verifying client whose connect host does not match the cert's CN/SAN should still complete the handshake because the
  * chain validates and the host is intentionally not checked. The JDK provider ([[SslEngineProvider]] line 37) honors the flag: it only sets
  * the `HTTPS` endpoint-identification algorithm when `hostnameVerification && !trustAll && hostname.nonEmpty`. The BoringSSL provider
  * ([[BoringSslProvider.applyConfig]]) NEVER reads `hostnameVerification`; the shim always calls `SSL_set1_host` for a non-empty hostname, so
  * the same `NetTlsConfig` verifies the host on BoringSSL but not on the JDK floor.
  *
  * This leaf builds both engines from the IDENTICAL config (CA-pinned to the wrong-name cert, `hostnameVerification = false`, wrong host) and
  * asserts the JDK floor completes the handshake (the documented contract) while pinning the BoringSSL outcome. The divergence is the bug: the
  * two providers implement different verification from one config. BoringSSL is the STRICTER side (it checks the host the config asked it not
  * to), so this is a correctness / config-contract divergence rather than a direct vulnerability, but it shows the native verify wiring does
  * not implement the documented `NetTlsConfig` contract.
  *
  * JVM-only: [[SslEngineProvider]] imports `javax.net.ssl`. Gate: cancels where BoringSSL is not staged.
  */
class BoringSslProviderHostnameFlagTest extends Test:

    import AllowUnsafe.embrace.danger

    // Same config for both providers: verify the chain against the pinned wrong-name cert, but disable the host-name check, and ask for a host
    // ("localhost") the cert (evil.example.com) does not cover.
    private val clientConfig = NetTlsConfig(
        caCertPath = Present(TlsWrongNameCert.certPath),
        hostnameVerification = false
    )

    private def wrongNameServer(create: (NetTlsConfig, String, Boolean) => TlsEngine)(using Frame): TlsEngine =
        create(
            NetTlsConfig(
                certChainPath = Present(TlsWrongNameCert.certPath),
                privateKeyPath = Present(TlsWrongNameCert.keyPath)
            ),
            "evil.example.com",
            true
        )

    // The documented contract: hostnameVerification = false disables the host check, so a chain-valid cert for the wrong name is accepted. The
    // JDK floor honors this. Anti-flakiness: in-memory loopback, no socket, no sleep.
    "the JDK provider honors hostnameVerification = false (chain-only): handshake completes against a wrong-name cert" in {
        Sync.defer {
            val client = SslEngineProvider.createEngine(clientConfig, "localhost", isServer = false)
            val server = wrongNameServer(SslEngineProvider.createEngine)
            try
                val completed = TlsEngineLoopback.handshake(client, server)
                assert(
                    completed,
                    "JDK provider rejected a chain-valid wrong-name cert even with hostnameVerification = false: the flag was not honored"
                )
            finally
                client.free()
                server.free()
            end try
        }
    }

    // The bug: with the SAME config (hostnameVerification = false), the BoringSSL provider IGNORES the flag and still checks the host, so the
    // wrong-name cert is rejected. The CORRECT cross-platform behavior is that the same config verifies the same way (completes here, matching
    // the JDK floor). This assertion FAILS today: BoringSSL rejects the handshake the JDK floor accepts.
    "the BoringSSL provider ignores hostnameVerification = false and still rejects a wrong-name cert (DIVERGES from the JDK floor)" in {
        if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
        Sync.defer {
            val client = BoringSslProvider.createEngine(clientConfig, "localhost", isServer = false)
            val server = wrongNameServer(BoringSslProvider.createEngine)
            try
                val completed = TlsEngineLoopback.handshake(client, server)
                assert(
                    completed,
                    "DIVERGENCE: BoringSSL rejected a wrong-name cert with hostnameVerification = false, while the JDK floor accepts it; " +
                        "BoringSslProvider.applyConfig never reads config.hostnameVerification so the same NetTlsConfig verifies differently per platform"
                )
            finally
                client.free()
                server.free()
            end try
        }
    }

end BoringSslProviderHostnameFlagTest
