package kyo.net.internal.tls

import kyo.*
import kyo.ffi.Buffer
import kyo.net.NetTlsConfig
import kyo.net.NetTlsSetupException
import kyo.net.Test

/** D-006 table (Q-003 resolved): a native TLS setup failure (`SSL_CTX_new` / `SSL_new` allocation, or a configured PEM that could not be
  * read) surfaces as [[NetTlsSetupException]], never a raw `Closed`. Driven over an injected [[SslLibBindings]] stub (no real BoringSSL /
  * system OpenSSL library needed for the allocation-failure half); the PEM half touches the real filesystem via a path that does not exist.
  */
class SslLibProviderSetupTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    /** A stub [[SslLibBindings]] whose `ctxNew` return value the test controls; every other method is an inert stub, invoked only where the
      * scenario reaches it (`ctxFree` on the cleanup-rethrow path).
      */
    final private class StubLib(ctxNewResult: Long) extends SslLibBindings:
        var ctxFreeCalls                                                                         = 0
        def ctxNew(isServer: Int)(using AllowUnsafe): Long                                       = ctxNewResult
        def ctxFree(ctx: Long)(using AllowUnsafe): Unit                                          = ctxFreeCalls += 1
        def ctxSetCert(ctx: Long, certPem: String, keyPem: String)(using AllowUnsafe): Int       = 0
        def ctxSetVerifyMode(ctx: Long, mode: Int)(using AllowUnsafe): Unit                      = ()
        def ctxLoadCa(ctx: Long, caPem: String)(using AllowUnsafe): Int                          = 1
        def ctxLoadSystemCa(ctx: Long)(using AllowUnsafe): Int                                   = 1
        def ctxSetMinMaxVersion(ctx: Long, min: Int, max: Int)(using AllowUnsafe): Int           = 0
        def sslNew(ctx: Long, hostname: String)(using AllowUnsafe): Long                         = 1L
        def sslSetVerifyName(ssl: Long, hostname: String)(using AllowUnsafe): Int                = 1
        def sslRequireUnmatchableIdentity(ssl: Long)(using AllowUnsafe): Int                     = 1
        def sslSetConnectState(ssl: Long)(using AllowUnsafe): Unit                               = ()
        def sslSetAcceptState(ssl: Long)(using AllowUnsafe): Unit                                = ()
        def sslFree(ssl: Long)(using AllowUnsafe): Unit                                          = ()
        def doHandshakeStep(ssl: Long)(using AllowUnsafe): Int                                   = 1
        def feedCiphertext(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int       = 0
        def drainCiphertext(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int      = 0
        def readPlain(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int            = 0
        def writePlain(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int           = 0
        def pending(ssl: Long)(using AllowUnsafe): Int                                           = 0
        def shutdownStep(ssl: Long)(using AllowUnsafe): Int                                      = 1
        def peerCertSha256(ssl: Long, outBuf: Buffer[Byte], outLen: Int)(using AllowUnsafe): Int = -1
        def probeAvailable()(using AllowUnsafe): Boolean                                         = true
    end StubLib

    final private class StubProvider(stub: StubLib) extends SslLibProvider:
        def name                                                = "stub"
        def priority                                            = 0
        private[tls] def lib(using AllowUnsafe): SslLibBindings = stub
    end StubProvider

    "SSL_CTX_new / SSL_new allocation failure surfaces NetTlsSetupException" in {
        val stub     = new StubLib(ctxNewResult = 0L)
        val provider = new StubProvider(stub)
        val ex = intercept[NetTlsSetupException] {
            provider.createEngine(NetTlsConfig.default, hostname = "example.com", isServer = false)
        }
        assert(ex.getMessage.contains("SSL_CTX_new"), s"the leaf must name the failed operation, got ${ex.getMessage}")
    }

    "an unreadable configured PEM surfaces NetTlsSetupException(read PEM ...)" in {
        val stub     = new StubLib(ctxNewResult = 1L)
        val provider = new StubProvider(stub)
        val badPath  = "/nonexistent/path/kyo-net-test-ca.pem"
        val config   = NetTlsConfig(caCertPath = Present(badPath))
        val ex = intercept[NetTlsSetupException] {
            provider.createEngine(config, hostname = "example.com", isServer = false)
        }
        assert(ex.getMessage.contains("read PEM"), s"the leaf must name the read-PEM step, got ${ex.getMessage}")
        assert(ex.getMessage.contains(badPath), s"the leaf must name the unreadable path, got ${ex.getMessage}")
        assert(ex.getCause != null, "the underlying IOException must be threaded as the structural cause, never swallowed")
        assert(stub.ctxFreeCalls == 1, "the allocated context must be freed on the cleanup-rethrow path")
    }

end SslLibProviderSetupTest
