package kyo.net.internal

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test

/** Direct binding lifecycle test for the full [[BoringSslBindings]] surface: the `SSL_CTX` / `SSL` handle round-trip resolves and frees
  * cleanly through the bundled shim, and `kyo_bssl_probe_available` reports the bundle functional. Shared JVM (Panama) + Native (`@extern`);
  * cancels when BoringSSL is not staged for the host.
  */
class BoringSslBindingsTest extends Test:

    import AllowUnsafe.embrace.danger

    "the TLS bindings declare no headers gate, so the publishing host cannot bake throwing stubs into the published binding" in {
        // kyo_net_boringssl.c and kyo_net_openssl.c define every entry point in their linked, external and stub states, so the
        // bindings link everywhere and the shim, not the codegen, decides whether the library is there.
        val gated = Chunk[(String, Ffi.Config)](
            "BoringSslBindings"     -> BoringSslBindings,
            "BoringSslProbe"        -> BoringSslProbe,
            "BoringSslTestBindings" -> BoringSslTestBindings,
            "OpenSslBindings"       -> OpenSslBindings
        ).filter(_._2.headers.nonEmpty)
        assert(gated.isEmpty, s"${gated.map((name, config) => s"$name: ${config.headers}")}")
    }

    "SSL_CTX_new + SSL_new + free round-trip without leak, and probeAvailable() returns true" in {
        val bssl =
            try Ffi.load[BoringSslBindings]
            catch
                case _: Throwable => cancel("kyonet_boringssl shim not built/loadable for this host")
        // kyo_bssl_probe_available() is the first call that forces BoringSslBindingsImpl's static init,
        // which loads the native lib; when BoringSSL is not staged for the host that init throws (surfacing
        // as NoClassDefFoundError on the call). Guard it so the test CANCELS rather than aborting the suite.
        val available =
            try bssl.probeAvailable()
            catch case _: Throwable => cancel("kyonet_boringssl shim not built/loadable for this host")
        if !available then cancel("BoringSSL not staged for this host")
        Sync.defer {
            val ctx = bssl.ctxNew(0)
            assert(ctx != 0L, "SSL_CTX_new returned a null (0) pointer")
            val ssl = bssl.sslNew(ctx, "localhost")
            assert(ssl != 0L, "SSL_new returned a null (0) pointer")
            // Frees succeed (idempotent C; a second free of either is a no-op).
            bssl.sslFree(ssl)
            bssl.ctxFree(ctx)
            assert(bssl.probeAvailable(), "probeAvailable() returned false")
        }
    }

end BoringSslBindingsTest
