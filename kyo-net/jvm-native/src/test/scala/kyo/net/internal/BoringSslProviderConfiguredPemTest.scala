package kyo.net.internal

import kyo.*
import kyo.ffi.Ffi
import kyo.net.NetTlsConfig
import kyo.net.NetTlsConfigException
import kyo.net.Test

/** Reproduce-first fail-closed tests for an explicitly-configured but unreadable CA / cert / key PEM path, covering both native providers
  * ([[BoringSslProvider]] priority-30 primary on JVM and Native, [[SystemOpenSslProvider]] priority-20 Native fallback). Both providers share
  * the identical `readPem` / `applyConfig` wiring, so one parameterized provider-identity leaf pins the same control on both (the same pattern
  * [[BoringSslProviderHostnameFlagTest]] uses to drive two providers from one `create` function).
  *
  * The control is RFC 9525 / CWE-295 fail-closed: a verifying client that points `caCertPath` at a file it cannot read has had its pinned
  * private CA dropped. Falling back to the system trust store in that case silently weakens validation (the operator's pin is gone without
  * notice), so `createEngine` MUST reject. The JDK floor holds the same posture and reports the same type: `NioTransport` wraps an unreadable
  * configured path as `NetTlsConfigException` rather than swallowing it or letting a raw JDK exception escape.
  *
  * The distinction drawn is between "path never configured" (`Absent`, keep the system-trust default, the non-verifying / no-CA cases)
  * and "path configured but unreadable / unparseable" (fail closed). The `Absent` boundary is pinned by a positive control: a verifying client
  * with NO `caCertPath` still builds an engine (the system-trust default is preserved).
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

    /** A path that EXISTS and is readable but holds no certificate. Distinct from [[unreadablePath]]: that one fails at the read, this one
      * reads fine and fails inside the library, which is the case the loader's return code is the only witness to.
      */
    private def certificateFreePath(): String =
        val f = java.io.File.createTempFile("kyo-net-tls-noncert", ".pem")
        java.nio.file.Files.write(f.toPath, "NOT A CERTIFICATE\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        f.deleteOnExit()
        f.getAbsolutePath
    end certificateFreePath

    /** Drive the readable-but-certificate-free CA against one provider. The PEM read SUCCEEDS here, so the only signal that the trust anchor
      * did not take is the loader's return code. Left unchecked, the engine builds with an EMPTY trust store and every verification decision
      * afterwards is made against trust the caller never configured, which surfaces later as an opaque handshake failure.
      */
    private def assertCertificateFreeCaFailsClosed(create: (NetTlsConfig, String, Boolean) => TlsEngine)(using
        Frame,
        kyo.test.AssertScope
    ): Unit =
        val config = NetTlsConfig(caCertPath = Present(certificateFreePath()))
        val ex = intercept[NetTlsConfigException] {
            val engine = create(config, "localhost", false)
            // Defensive, as above: a regression returns a live engine over an empty store rather than throwing.
            engine.free()
        }
        discard(ex)
    end assertCertificateFreeCaFailsClosed

    /** Drive a server whose certificate chain READS fine but holds no certificate. The unreadable-path leaves stop at `readPem`, so they never
      * reach the cert/key loader at all; only a readable-but-invalid pair gets that far, which makes `ctxSetCert`'s return code the sole
      * witness. Unchecked, the server builds with no identity to present and every handshake fails opaquely rather than naming the config.
      */
    private def assertCertificateFreeServerMaterialFailsClosed(create: (NetTlsConfig, String, Boolean) => TlsEngine)(using
        Frame,
        kyo.test.AssertScope
    ): Unit =
        val config = NetTlsConfig(certChainPath = Present(certificateFreePath()), privateKeyPath = Present(TlsTestCert.keyPath))
        val ex = intercept[NetTlsConfigException] {
            val engine = create(config, "localhost", true)
            // Defensive, as above: a regression returns a live engine carrying no usable identity rather than throwing.
            engine.free()
        }
        discard(ex)
    end assertCertificateFreeServerMaterialFailsClosed

    private def boringSsl(using Frame): (NetTlsConfig, String, Boolean) => TlsEngine = BoringSslProvider.createEngine(_, _, _)
    private def openSsl(using Frame): (NetTlsConfig, String, Boolean) => TlsEngine   = SystemOpenSslProvider.createEngine(_, _, _)

    /** Drive the configured-but-unreadable-CA repro against one provider's `createEngine`. A verifying client (the two defaults,
      * `trustAll = false` and `hostnameVerification = true`) with `caCertPath` set to a nonexistent file must FAIL CLOSED (`NetTlsConfigException`) instead of
      * silently building an engine that falls back to the system trust store.
      */
    private def assertCaFailsClosed(create: (NetTlsConfig, String, Boolean) => TlsEngine)(using Frame, kyo.test.AssertScope): Unit =
        val config = NetTlsConfig(caCertPath = Present(unreadablePath()))
        val ex = intercept[NetTlsConfigException] {
            val engine = create(config, "localhost", false)
            // Defensive: if the bug is present, createEngine returns a live engine instead of throwing; free it so a failing run leaks nothing.
            engine.free()
        }
        assert(
            ex.getMessage.contains("PEM") || ex.getMessage.contains("read"),
            "createEngine threw NetTlsConfigException but not for the configured-but-unreadable-PEM reason: " + ex.getMessage
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
        val certEx = intercept[NetTlsConfigException] {
            val engine = create(badCertConfig, "localhost", true)
            engine.free()
        }
        val keyEx = intercept[NetTlsConfigException] {
            val engine = create(badKeyConfig, "localhost", true)
            engine.free()
        }
        assert(
            (certEx.getMessage.contains("PEM") || certEx.getMessage.contains("read")) &&
                (keyEx.getMessage.contains("PEM") || keyEx.getMessage.contains("read")),
            "server createEngine threw NetTlsConfigException but not for the configured-but-unreadable-PEM reason: cert=" + certEx.getMessage + " key=" + keyEx.getMessage
        )
    end assertServerMaterialFailsClosed

    // The bug (CWE-295 silent weakening) this guards: a verifying client whose configured caCertPath is unreadable must not fall back to the
    // system trust store. If readPem swallowed the read error to Absent and applyConfig treated Absent as "no CA configured, use system trust",
    // createEngine would SUCCEED (the pin silently dropped); the CORRECT behavior is to reject (the same posture the JDK floor holds), so this
    // asserts a NetTlsConfigException is thrown.
    "BoringSSL: a verifying client with an unreadable configured caCertPath fails closed instead of falling back to system trust" in {
        if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
        Sync.defer(assertCaFailsClosed(boringSsl))
    }

    "OpenSSL: a verifying client with an unreadable configured caCertPath fails closed instead of falling back to system trust" in {
        if !TlsRealEngines.openSslAvailable() then cancel("system OpenSSL not available for this host")
        Sync.defer(assertCaFailsClosed(openSsl))
    }

    // The unreadable-path leaves above cover a CA that cannot be READ. A CA that reads fine and contains no certificate is a separate case:
    // the read succeeds, so only the loader's return code says the anchor did not take, and discarding it leaves an empty trust store.
    "BoringSSL: a readable caCertPath holding no certificate fails closed instead of building over an empty trust store" in {
        if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
        Sync.defer(assertCertificateFreeCaFailsClosed(boringSsl))
    }

    "OpenSSL: a readable caCertPath holding no certificate fails closed instead of building over an empty trust store" in {
        if !TlsRealEngines.openSslAvailable() then cancel("system OpenSSL not available for this host")
        Sync.defer(assertCertificateFreeCaFailsClosed(openSsl))
    }

    // The server cert/key loader needs its own readable-but-invalid case. The unreadable-path server leaves above fail at the READ, so they
    // never reach the loader and would pass with its return code discarded; only material that reads successfully exercises it.
    "BoringSSL: a server whose readable certificate chain holds no certificate fails closed instead of starting with no identity" in {
        if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
        Sync.defer(assertCertificateFreeServerMaterialFailsClosed(boringSsl))
    }

    "OpenSSL: a server whose readable certificate chain holds no certificate fails closed instead of starting with no identity" in {
        if !TlsRealEngines.openSslAvailable() then cancel("system OpenSSL not available for this host")
        Sync.defer(assertCertificateFreeServerMaterialFailsClosed(openSsl))
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
    // still builds an engine; "no CA configured" must not become a rejection. This holds regardless, pinning that Absent (keep default) is
    // distinguished from configured-but-unreadable (fail closed).
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

    // A verifying client with no configured caCertPath must actually LOAD the platform trust store, not leave an EMPTY X509 store. If
    // applyConfig's Absent branch loaded no CAs while verify mode stayed 2 (SSL_VERIFY_PEER), every public-internet handshake would fail
    // with EngineError (the "system-trust default" the two tests above assert would be empty in the native
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
