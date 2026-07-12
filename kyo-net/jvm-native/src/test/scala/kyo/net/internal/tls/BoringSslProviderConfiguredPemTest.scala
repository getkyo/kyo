package kyo.net.internal.tls

import kyo.*
import kyo.ffi.Ffi
import kyo.net.NetTlsConfig
import kyo.net.Test

/** Reproduce-first fail-closed tests for an explicitly-configured but unreadable CA / cert / key PEM path, covering both native providers
  * ([[BoringSslProvider]] priority-30 primary on JVM and Native, [[SystemOpenSslProvider]] priority-20 Native fallback). Both providers share
  * the identical `readPem` / `applyConfig` wiring, so one parameterized provider-identity leaf pins the same control on both (the same pattern
  * [[BoringSslProviderHostnameFlagTest]] uses to drive two providers from one `create` function).
  *
  * The control is RFC 9525 / CWE-295 fail-closed: a verifying client that points `caCertPath` at a file it cannot read has had its pinned
  * private CA dropped. Falling back to the system trust store in that case silently weakens validation (the operator's pin is gone without
  * notice), so `createEngine` MUST reject. The JDK floor already converges to this posture: `NioTransport.loadCaCertTrustManagers` opens the
  * configured CA with a plain `FileInputStream` and lets the resulting exception propagate out of `createSslContext` rather than swallowing it.
  *
  * The distinction the fix draws is between "path never configured" (`Absent`, keep the system-trust default, the non-verifying / no-CA cases)
  * and "path configured but unreadable / unparseable" (fail closed). The `Absent` boundary is pinned by a positive control: a verifying client
  * with NO `caCertPath` still builds an engine (the system-trust default is preserved, untouched by the fix).
  *
  * Each leaf cancels where the relevant provider is not staged, so a host without the bundle is not a failure; CI validates the real
  * provider. Anti-flakiness: `createEngine` is synchronous and in-memory (no socket, no sleep); the unreadable path is a fresh, never-created
  * temp path so the read deterministically fails.
  */
class BoringSslProviderConfiguredPemTest extends Test:

    import AllowUnsafe.embrace.danger

    /** An absolute path that does not exist (a fresh temp name that is never created), so any read of it fails deterministically. */
    private def unreadablePath(): String =
        val f = java.io.File.createTempFile("kyo-net-tls-missing", ".pem")
        discard(f.delete())
        f.getAbsolutePath + "-does-not-exist"
    end unreadablePath

    private def boringSsl(using Frame): (NetTlsConfig, String, Boolean) => TlsEngine = BoringSslProvider.createEngine(_, _, _)
    private def openSsl(using Frame): (NetTlsConfig, String, Boolean) => TlsEngine   = SystemOpenSslProvider.createEngine(_, _, _)

    /** Drive the configured-but-unreadable-CA repro against one provider's `createEngine`. A verifying client (the two defaults,
      * `trustAll = false` and `hostnameVerification = true`) with `caCertPath` set to a nonexistent file must FAIL CLOSED (`Closed`) instead of
      * silently building an engine that falls back to the system trust store.
      */
    private def assertCaFailsClosed(create: (NetTlsConfig, String, Boolean) => TlsEngine)(using Frame, kyo.test.AssertScope): Unit =
        val config = NetTlsConfig(caCertPath = Present(unreadablePath()))
        val ex = intercept[Closed] {
            val engine = create(config, "localhost", false)
            // Defensive: if the bug is present, createEngine returns a live engine instead of throwing; free it so a failing run leaks nothing.
            engine.free()
        }
        assert(
            ex.getMessage.contains("PEM") || ex.getMessage.contains("read"),
            "createEngine threw Closed but not for the configured-but-unreadable-PEM reason: " + ex.getMessage
        )
    end assertCaFailsClosed

    /** Drive the configured-but-unreadable server-material repro against one provider's `createEngine`. A server with one of `certChainPath` /
      * `privateKeyPath` set to a nonexistent file must FAIL CLOSED rather than silently dropping the (mis)configured material.
      */
    private def assertServerMaterialFailsClosed(create: (NetTlsConfig, String, Boolean) => TlsEngine)(using
        Frame,
        kyo.test.AssertScope
    ): Unit =
        val badCertConfig = NetTlsConfig(certChainPath = Present(unreadablePath()), privateKeyPath = Present(TlsTestCert.keyPath))
        val badKeyConfig  = NetTlsConfig(certChainPath = Present(TlsTestCert.certPath), privateKeyPath = Present(unreadablePath()))
        val certEx = intercept[Closed] {
            val engine = create(badCertConfig, "localhost", true)
            engine.free()
        }
        val keyEx = intercept[Closed] {
            val engine = create(badKeyConfig, "localhost", true)
            engine.free()
        }
        assert(
            (certEx.getMessage.contains("PEM") || certEx.getMessage.contains("read")) &&
                (keyEx.getMessage.contains("PEM") || keyEx.getMessage.contains("read")),
            "server createEngine threw Closed but not for the configured-but-unreadable-PEM reason: cert=" + certEx.getMessage + " key=" + keyEx.getMessage
        )
    end assertServerMaterialFailsClosed

    // The bug (CWE-295 silent weakening): a verifying client whose configured caCertPath is unreadable falls back to the system trust store
    // because readPem swallows the read error to Absent and applyConfig treats Absent as "no CA configured, use system trust". Before the fix
    // createEngine SUCCEEDS (the pin is silently dropped); the CORRECT behavior is to reject (the JDK loadCaCertTrustManagers posture). This
    // assertion FAILS today: no Closed is thrown.
    "BoringSSL: a verifying client with an unreadable configured caCertPath fails closed instead of falling back to system trust" in {
        if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
        Sync.defer(assertCaFailsClosed(boringSsl))
    }

    "OpenSSL: a verifying client with an unreadable configured caCertPath fails closed instead of falling back to system trust" in {
        if !TlsRealEngines.openSslAvailable() then cancel("system OpenSSL not available for this host")
        Sync.defer(assertCaFailsClosed(openSsl))
    }

    // The same swallow path backs the server cert/key material: an unreadable certChainPath or privateKeyPath is dropped to Absent and the
    // server silently starts with no certificate configured. Fail closed instead.
    "BoringSSL: a server with an unreadable configured cert or key path fails closed instead of silently dropping the material" in {
        if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
        Sync.defer(assertServerMaterialFailsClosed(boringSsl))
    }

    "OpenSSL: a server with an unreadable configured cert or key path fails closed instead of silently dropping the material" in {
        if !TlsRealEngines.openSslAvailable() then cancel("system OpenSSL not available for this host")
        Sync.defer(assertServerMaterialFailsClosed(openSsl))
    }

    // Boundary control: a NOT-configured path (caCertPath Absent) must keep the system-trust default. A verifying client with no caCertPath
    // still builds an engine; the fix must not turn "no CA configured" into a rejection. This passes before and after the fix, pinning that the
    // fix distinguishes Absent (keep default) from configured-but-unreadable (fail closed).
    "BoringSSL: a verifying client with no configured caCertPath still builds an engine (system-trust default preserved)" in {
        if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
        Sync.defer {
            val engine = BoringSslProvider.createEngine(NetTlsConfig(), "localhost", isServer = false)
            try assert(engine != null, "verifying client with no caCertPath should still build an engine on the system-trust default")
            finally engine.free()
            end try
        }
    }

    "OpenSSL: a verifying client with no configured caCertPath still builds an engine (system-trust default preserved)" in {
        if !TlsRealEngines.openSslAvailable() then cancel("system OpenSSL not available for this host")
        Sync.defer {
            val engine = SystemOpenSslProvider.createEngine(NetTlsConfig(), "localhost", isServer = false)
            try assert(engine != null, "verifying client with no caCertPath should still build an engine on the system-trust default")
            finally engine.free()
            end try
        }
    }

    // The fix: a verifying client with no configured caCertPath must actually LOAD the platform trust store, not leave an EMPTY X509 store. Before
    // the fix, applyConfig's Absent branch loaded no CAs while verify mode stayed 2 (SSL_VERIFY_PEER), so every public-internet handshake failed
    // with EngineError (the kyo-browser Chrome-download wipeout: the "system-trust default" the two tests above assert was empty in the native
    // path). This exercises the shim's ctxLoadSystemCa directly: on a host with a real platform CA bundle it must load at least one trust source
    // (SSL_CTX_load_verify_locations parses the bundle only when it exists), so the client validates public chains. Gated on a bundle file being
    // present (Linux CI has /etc/ssl/certs/ca-certificates.crt); a host with none cannot exercise system trust and cancels.
    private val systemCaBundlePaths = Seq(
        "/etc/ssl/certs/ca-certificates.crt", // Debian, Ubuntu, Alpine, Arch
        "/etc/pki/tls/certs/ca-bundle.crt",   // Fedora, RHEL, CentOS
        "/etc/ssl/ca-bundle.pem",             // openSUSE
        "/etc/ssl/cert.pem"                   // macOS, some BSD
    )
    private def presentCaBundle: Maybe[String] =
        Maybe.fromOption(systemCaBundlePaths.find(p => java.nio.file.Files.exists(java.nio.file.Paths.get(p))))

    "BoringSSL: a verifying client with no caCertPath loads the platform trust store, not an empty store" in {
        if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
        presentCaBundle match
            case Absent => cancel("no platform CA bundle on this host to exercise system trust")
            case Present(bundle) =>
                Sync.defer {
                    val lib = Ffi.load[BoringSslBindings]
                    val ctx = lib.ctxNew(0)
                    assert(ctx != 0L, "ctxNew")
                    try
                        assert(
                            lib.ctxLoadSystemCa(ctx) > 0,
                            s"ctxLoadSystemCa must load at least one trust source from the platform default store (bundle present: $bundle)"
                        )
                    finally lib.ctxFree(ctx)
                    end try
                }
        end match
    }

end BoringSslProviderConfiguredPemTest
