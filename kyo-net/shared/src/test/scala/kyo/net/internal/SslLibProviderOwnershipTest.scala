package kyo.net.internal

import kyo.*
import kyo.ffi.Buffer
import kyo.net.NetTlsConfig
import kyo.net.NetTlsConfigException
import kyo.net.Test

/** Engine construction over recording bindings instead of a real TLS library.
  *
  * What this covers is native OWNERSHIP, which no end-to-end TLS suite can see: an `SSL_CTX` is refcounted, `SSL_new` takes its own reference,
  * and only `SSL_free` releases that one, so a context whose creation reference is never dropped leaks silently along with the chains and keys
  * loaded into it. A leak has no observable behaviour, so counting the calls is the only way to assert it, and driving the provider over stub
  * bindings does that without crypto, without staged libraries, and on every platform.
  *
  * It also reaches the fail-closed identity path, which real material cannot: `SSL_set1_host` on a fresh SSL essentially cannot fail, so the
  * second-level failure (the unmatchable identity itself failing to bind) is unreachable with a real library and reachable here.
  */
class SslLibProviderOwnershipTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    /** Records the allocation and release calls, and lets a leaf choose which of them reports failure. Handles are plain integers: nothing
      * dereferences them, so the provider cannot tell them from real pointers.
      */
    private class StubBindings(
        ctxNewResult: Long = 10L,
        sslNewResult: Long = 100L,
        minMaxVersionResult: Int = 0,
        systemCaResult: Int = 1,
        verifyNameResult: Int = 1,
        unmatchableResult: Int = 1
    ) extends SslLibBindings:
        var ctxNewCalls       = 0
        var ctxFreeCalls      = 0
        var sslNewCalls       = 0
        var sslFreeCalls      = 0
        var unmatchableCalls  = 0
        var connectStateCalls = 0

        def ctxNew(isServer: Int)(using AllowUnsafe): Long =
            ctxNewCalls += 1; ctxNewResult
        def ctxFree(ctx: Long)(using AllowUnsafe): Unit = ctxFreeCalls += 1
        def sslNew(ctx: Long, hostname: String)(using AllowUnsafe): Long =
            sslNewCalls += 1; sslNewResult
        def sslFree(ssl: Long)(using AllowUnsafe): Unit = sslFreeCalls += 1

        def sslSetVerifyName(ssl: Long, hostname: String)(using AllowUnsafe): Int = verifyNameResult
        def sslRequireUnmatchableIdentity(ssl: Long)(using AllowUnsafe): Int =
            unmatchableCalls += 1; unmatchableResult
        def sslSetConnectState(ssl: Long)(using AllowUnsafe): Unit = connectStateCalls += 1
        def sslSetAcceptState(ssl: Long)(using AllowUnsafe): Unit  = ()

        def ctxSetCert(ctx: Long, certPem: String, keyPem: String)(using AllowUnsafe): Int = 0
        def ctxSetVerifyMode(ctx: Long, mode: Int)(using AllowUnsafe): Unit                = ()
        def ctxLoadCa(ctx: Long, caPem: String)(using AllowUnsafe): Int                    = 1
        def ctxLoadSystemCa(ctx: Long)(using AllowUnsafe): Int                             = systemCaResult
        def ctxSetMinMaxVersion(ctx: Long, min: Int, max: Int)(using AllowUnsafe): Int     = minMaxVersionResult

        def doHandshakeStep(ssl: Long)(using AllowUnsafe): Int                                   = 1
        def feedCiphertext(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int       = len
        def drainCiphertext(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int      = 0
        def readPlain(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int            = 0
        def writePlain(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int           = len
        def pending(ssl: Long)(using AllowUnsafe): Int                                           = 0
        def shutdownStep(ssl: Long)(using AllowUnsafe): Int                                      = 1
        def peerCertSha256(ssl: Long, outBuf: Buffer[Byte], outLen: Int)(using AllowUnsafe): Int = -1
        def probeAvailable()(using AllowUnsafe): Boolean                                         = true
    end StubBindings

    private class StubProvider(bindings: StubBindings) extends SslLibProvider:
        def name                                                     = "stub"
        def priority                                                 = 0
        def libraryIds: Chunk[String]                                = Chunk("stub")
        private[internal] def lib(using AllowUnsafe): SslLibBindings = bindings
    end StubProvider

    private def build(bindings: StubBindings, config: NetTlsConfig = NetTlsConfig(), hostname: String = "example.com") =
        StubProvider(bindings).createEngine(config, hostname, isServer = false)

    "a built engine leaves the context's creation reference released" in {
        // The engine's SSL holds its own reference, so releasing the creation reference here is what lets the context go when the engine is
        // freed. Skipping it leaks a context per engine, which is invisible at every layer above this one.
        val bindings = StubBindings()
        val engine   = build(bindings)
        assert(
            bindings.ctxNewCalls == 1 && bindings.ctxFreeCalls == 1,
            s"context: new=${bindings.ctxNewCalls} free=${bindings.ctxFreeCalls}"
        )
        assert(bindings.sslFreeCalls == 0, "the SSL belongs to the engine and must outlive construction")
        engine.free()
        assert(bindings.sslFreeCalls == 1, s"the engine must release its SSL exactly once; got ${bindings.sslFreeCalls}")
    }

    "a context whose SSL could not be created is released" in {
        val bindings = StubBindings(sslNewResult = 0L)
        val ex       = intercept[NetTlsConfigException](build(bindings))
        discard(ex)
        assert(bindings.ctxFreeCalls == 1, s"expected the context released; got ${bindings.ctxFreeCalls}")
        assert(bindings.sslFreeCalls == 0, "no SSL was created, so none may be freed")
    }

    "a context rejected during configuration is released before any SSL exists" in {
        val bindings = StubBindings(minMaxVersionResult = -1)
        val ex       = intercept[NetTlsConfigException](build(bindings))
        discard(ex)
        assert(bindings.sslNewCalls == 0, "configuration failed, so no SSL should have been created")
        assert(bindings.ctxFreeCalls == 1, s"expected the context released; got ${bindings.ctxFreeCalls}")
    }

    "an identity that cannot be bound fails closed and releases both the SSL and the context" in {
        // A verifying client with no reference identity binds an unmatchable one so the handshake rejects every peer. When THAT fails there is
        // no name bound at all and the handshake would take any chain-valid certificate, so the engine build must fail rather than proceed.
        // It fails after the SSL exists, which is the path that would otherwise strand the SSL and its two memory BIOs.
        val bindings = StubBindings(unmatchableResult = 0)
        val ex       = intercept[NetTlsConfigException](build(bindings, hostname = ""))
        discard(ex)
        assert(bindings.unmatchableCalls == 1, "the unmatchable identity should have been attempted")
        assert(bindings.sslFreeCalls == 1, s"expected the SSL released on the failure path; got ${bindings.sslFreeCalls}")
        assert(bindings.ctxFreeCalls == 1, s"expected the context released; got ${bindings.ctxFreeCalls}")
        assert(bindings.connectStateCalls == 0, "the engine must not reach role selection after a failed identity bind")
    }

    "a verifying client whose reference identity binds keeps the unmatchable fallback unused" in {
        val bindings = StubBindings()
        val engine   = build(bindings)
        assert(bindings.unmatchableCalls == 0, "a host that bound needs no unmatchable fallback")
        assert(bindings.connectStateCalls == 1, "the client role should have been selected")
        engine.free()
    }

end SslLibProviderOwnershipTest
