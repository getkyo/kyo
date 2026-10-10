package kyo.net.internal

import kyo.*
import kyo.ffi.Buffer
import kyo.net.Test
import kyo.net.internal.backend.CapabilityOutcome

/** What [[SslLibProvider]]'s capability probe reports for each body a TLS shim can compile to.
  *
  * Which body a shim compiles to is decided by the build that links the binary, so one test binary carries one body per shim. The two
  * answers the shim gives (which body was compiled, and whether `SSL_CTX_new` succeeded) are therefore driven through bindings whose engine
  * surface throws: the probe must decide from those two answers alone.
  */
class SslLibProviderProbeTest extends Test:

    import AllowUnsafe.embrace.danger

    final private class ProbeOnlyBindings(stub: Boolean, ctxProbe: Boolean) extends SslLibBindings:
        var probeCalls = 0

        def compiledStub()(using AllowUnsafe): Boolean   = stub
        def probeAvailable()(using AllowUnsafe): Boolean =
            probeCalls += 1
            ctxProbe

        private def engine: Nothing = throw new IllegalStateException("a capability probe must not touch the engine surface")
        def ctxNew(isServer: Int)(using AllowUnsafe): Long                                             = engine
        def ctxFree(ctx: Long)(using AllowUnsafe): Unit                                                = engine
        def ctxSetCert(ctx: Long, certPem: String, keyPem: String)(using AllowUnsafe): Int             = engine
        def ctxSetVerifyMode(ctx: Long, mode: Int)(using AllowUnsafe): Unit                            = engine
        def ctxLoadCa(ctx: Long, caPem: String)(using AllowUnsafe): Int                                = engine
        def ctxLoadSystemCa(ctx: Long)(using AllowUnsafe): Int                                         = engine
        def ctxSetMinMaxVersion(ctx: Long, min: Int, max: Int)(using AllowUnsafe): Int                 = engine
        def sslNew(ctx: Long, hostname: String)(using AllowUnsafe): Long                               = engine
        def sslSetVerifyName(ssl: Long, hostname: String)(using AllowUnsafe): Int                      = engine
        def sslRequireUnmatchableIdentity(ssl: Long)(using AllowUnsafe): Int                           = engine
        def sslSetConnectState(ssl: Long)(using AllowUnsafe): Unit                                     = engine
        def sslSetAcceptState(ssl: Long)(using AllowUnsafe): Unit                                      = engine
        def sslFree(ssl: Long)(using AllowUnsafe): Unit                                                = engine
        def doHandshakeStep(ssl: Long)(using AllowUnsafe): Int                                         = engine
        def feedCiphertext(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int             = engine
        def drainCiphertext(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int            = engine
        def readPlain(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int                  = engine
        def writePlain(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int                 = engine
        def pending(ssl: Long)(using AllowUnsafe): Int                                                 = engine
        def shutdownStep(ssl: Long)(using AllowUnsafe): Int                                            = engine
        def peerCertEndPointHash(ssl: Long, outBuf: Buffer[Byte], outLen: Int)(using AllowUnsafe): Int = engine
    end ProbeOnlyBindings

    final private class ProbeProvider(bindings: ProbeOnlyBindings) extends SslLibProvider:
        def name                                                             = "openssl"
        def priority                                                         = 20
        def libraryIds: Chunk[String]                                        = Chunk.empty
        private[internal] val lib: SslLibBindings                            = bindings
        private[net] val compiledStubOutcome: CapabilityOutcome.CompiledStub =
            CapabilityOutcome.CompiledStub("kyo_net_openssl.c", "OpenSSL", "install libssl-dev and relink")
    end ProbeProvider

    "a shim compiled to its stub body reports the missing library, not a failed SSL_CTX probe" in {
        val bindings = ProbeOnlyBindings(stub = true, ctxProbe = false)
        val provider = ProbeProvider(bindings)
        assert(provider.doProbe == provider.compiledStubOutcome, s"got ${provider.doProbe}")
        assert(bindings.probeCalls == 0, "a stub has no SSL_CTX to probe, so its probe answer carries no information")
    }

    "a compiled-in library whose SSL_CTX probe fails is reported as compiled in, with the probe as the cause" in {
        ProbeProvider(ProbeOnlyBindings(stub = false, ctxProbe = false)).doProbe match
            case CapabilityOutcome.Unavailable(reason) =>
                assert(reason.contains("'openssl'"), reason)
                assert(reason.contains("compiled into this binary"), reason)
                assert(reason.contains("SSL_CTX"), reason)
            case other => fail(s"expected Unavailable, got $other")
        end match
    }

    "a compiled-in library whose SSL_CTX probe succeeds is available" in {
        assert(ProbeProvider(ProbeOnlyBindings(stub = false, ctxProbe = true)).doProbe == CapabilityOutcome.Available)
    }

    "each native provider's stub outcome names its own shim, library and remedy" in {
        val openssl = SystemOpenSslProvider.compiledStubOutcome
        assert(openssl.source == "kyo_net_openssl.c")
        assert(openssl.library == "OpenSSL")
        assert(openssl.remedy.contains("libssl-dev"), openssl.remedy)
        assert(openssl.remedy.contains("kyo-natives-plugin"), openssl.remedy)
        assert(openssl.remedy.contains("relink"), openssl.remedy)
        val boringssl = BoringSslProvider.compiledStubOutcome
        assert(boringssl.source == "kyo_net_boringssl.c")
        assert(boringssl.library == "BoringSSL")
        assert(boringssl.remedy.contains("kyo-natives-plugin"), boringssl.remedy)
        assert(boringssl.remedy.contains("build-boringssl.sh"), boringssl.remedy)
    }

end SslLibProviderProbeTest
