package kyo.net.internal

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Connection
import kyo.net.NetConfig
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.internal.posix.PollerIoDriver
import kyo.net.internal.posix.PosixConstants
import kyo.net.internal.posix.PosixHandle
import kyo.net.internal.posix.PosixTransport
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.IoDriverPool

/** Shared real-engine factories and availability probes for TLS tests on JVM and Native.
  *
  * Provides real BoringSSL and OpenSSL engine pairs over [[TlsTestCert]] so TLS tests can run real crypto without re-inlining the engine
  * setup.
  *
  * Anti-flakiness: engine pairs are allocated and freed in a try/finally block; no sleeping. realTlsLoopback latches on real connect/listen
  * Fiber.Unsafe completions.
  */
object TlsRealEngines:

    private val serverConfig = NetTlsConfig(
        certChainPath = Present(TlsTestCert.certPath),
        privateKeyPath = Present(TlsTestCert.keyPath)
    )
    private val clientConfig = NetTlsConfig(trustAll = true)

    /** True if the BoringSSL bundle is staged for this host and the one-call probe succeeds.
      *
      * Lifted verbatim from TlsEngineTest.scala lines 22-24.
      */
    def boringSslAvailable(): Boolean =
        import AllowUnsafe.embrace.danger
        try Ffi.load[BoringSslBindings].probeAvailable()
        catch case _: Throwable => false
    end boringSslAvailable

    /** True if the system OpenSSL shim is available on this host.
      *
      * Lifted verbatim from TlsEngineTest.scala lines 26-28.
      */
    def openSslAvailable(): Boolean =
        import AllowUnsafe.embrace.danger
        try Ffi.load[OpenSslBindings].probeAvailable()
        catch case _: Throwable => false
    end openSslAvailable

    /** Cancel the test if no TLS provider is available on this host and no poller is present.
      *
      * Pattern from StartTlsUpgradeCloseRaceTest.scala lines 56-58.
      */
    def assumeTlsReady()(using Frame): Unit < Any =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            throw new kyo.test.TestCancelled(
                "TLS real-engine tests need epoll (Linux) or kqueue (macOS/BSD)"
            )
        end if
        if !boringSslAvailable() && !openSslAvailable() then
            throw new kyo.test.TestCancelled("No TLS provider staged for this host")
        end if
    end assumeTlsReady

    /** Allocate a real BoringSSL client+server engine pair over [[TlsTestCert]], run `f`, then free both engines once the body computation
      * completes.
      *
      * The free is sequenced with [[Sync.ensure]], NOT a plain try/finally. A try/finally frees the instant `f(client, server)` returns its
      * value; for an Async body that value is the as-yet-UNRUN computation, so the engines (their native `kyo_bssl_ssl` wrapper structs) would be
      * freed before the body actually drives a write. A later FIFO-worker `writePlain` would then deref `st->ssl` out of a freed-and-reused wrapper
      * struct: `kyo_bssl_write_plain` calls `SSL_write(NULL, ...)` and SIGSEGVs. `Sync.ensure` runs the free after the effect completes, so it works
      * for both a synchronous in-memory body (engine ops run eagerly during construction) and an Async driver body (ops run on the engine FIFO when
      * the computation is executed). This mirrors production, which frees a connection engine through the same FIFO after every engine op.
      *
      * Anti-flakiness: engine allocation is synchronous and the free is ordered after the body via Sync.ensure; no sleeping.
      *
      * Ownership rule: never wire an engine from this method into a `handle.tls` a driver owns unless the body waits for that handle's `tls`
      * to go `Absent` before returning. A driver's own teardown (`dischargeClose` / `shutdownTls` / `freeResources`) is frequently QUEUED, not
      * inline, so it can still be pending when the body's result value completes; this method's `ensure` then frees the engine out-of-band
      * while the driver's queued teardown still owes it an `SSL_shutdown`, a native use-after-free (unmapped reuse crashes immediately,
      * recycled reuse corrupts a live allocation and surfaces later at an unrelated leaf). Prefer [[singleEngine]] instead, which leaves the
      * free entirely to whichever path (the driver's own free sink, or the test's own cleanup) actually needs it; `NativeSslEngine.free()` is
      * CAS-guarded exactly-once so calling it from both is harmless. See `PollerIoDriverTlsHalfCloseEtTest`'s `awaitCondition(...)
      * (!acceptedH.tls.isDefined)` for the guard a caller that still wants this method's convenience must add.
      */
    def withEngines[A, S](f: (TlsEngine, TlsEngine) => A < S)(using Frame, AllowUnsafe): A < (S & Sync) =
        if !boringSslAvailable() then
            throw new kyo.test.TestCancelled("BoringSSL not staged for this host")
        val client = BoringSslProvider.createEngine(clientConfig, "localhost", isServer = false)
        val server = BoringSslProvider.createEngine(serverConfig, "localhost", isServer = true)
        Sync.ensure(Sync.defer {
            client.free()
            server.free()
        })(f(client, server))
    end withEngines

    /** Allocate a single real BoringSSL engine that the CALLER owns and must free exactly once.
      *
      * Unlike [[withEngines]] (which frees both engines after the body computation completes), this hands ownership of the native session to the caller, so a test whose
      * subject is the free path itself (the failed-handshake free in HandshakeEngineFreeTest, the close-during-encrypt/decrypt free in the race
      * tests) can drive a single real native free without a competing finally that would double-free the native session. The server role uses the
      * cert/key config so it can complete a real handshake (via [[TlsEngineLoopback.handshake]]); the client role uses `trustAll`.
      */
    /** Cancel unless BoringSSL is staged, for a leaf that will build its engine with [[singleEngine]].
      *
      * Exists so such a leaf can gate BEFORE it allocates sockets. singleEngine itself cancels when BoringSSL is missing, and a leaf that has
      * already opened a loopback pair by then leaks it: the cancel unwinds past the cleanup. That is only reachable on a host staging exactly
      * one of the two providers, which is why it survived a general sweep. [[assumeTlsReady]] is NOT a substitute: it accepts OpenSSL too.
      */
    def assumeBoringSslReady()(using Frame): Unit =
        if !boringSslAvailable() then
            throw new kyo.test.TestCancelled("BoringSSL not staged for this host")
    end assumeBoringSslReady

    def singleEngine(isServer: Boolean)(using Frame, AllowUnsafe): TlsEngine =
        if !boringSslAvailable() then
            throw new kyo.test.TestCancelled("BoringSSL not staged for this host")
        BoringSslProvider.createEngine(if isServer then serverConfig else clientConfig, "localhost", isServer = isServer)
    end singleEngine

    /** Allocate a real OpenSSL client+server engine pair over [[TlsTestCert]], run `f`, then free both engines once the body computation
      * completes.
      *
      * Frees via [[Sync.ensure]] for the same reason as [[withEngines]]: a plain try/finally would free the native sessions before an Async body
      * runs the engine ops, a use-after-free. Sync.ensure orders the free after the effect completes.
      */
    def withOpenSslEngines[A, S](f: (TlsEngine, TlsEngine) => A < S)(using Frame, AllowUnsafe): A < (S & Sync) =
        if !openSslAvailable() then
            throw new kyo.test.TestCancelled("system OpenSSL not available for this host")
        val client = SystemOpenSslProvider.createEngine(clientConfig, "localhost", isServer = false)
        val server = SystemOpenSslProvider.createEngine(serverConfig, "localhost", isServer = true)
        Sync.ensure(Sync.defer {
            client.free()
            server.free()
        })(f(client, server))
    end withOpenSslEngines

    /** Run `f` with a real TLS client+server Connection pair over a real loopback socket.
      *
      * Creates a fresh PollerIoDriver and
      * PosixTransport, listens on an ephemeral port with the test cert, connects from the client side with trustAll, completes the TLS
      * handshake, then hands both connections to `f`. The driver is closed when `f` returns (this transport is never closed, mirroring
      * production). The listener and both connections are registered with [[Scope.ensure]] as soon as each is acquired, so every one of
      * them is closed even if a later acquisition step or `f` itself aborts (the driver's own teardown only fails pending I/O
      * promises, it never closes an idle handle's fd).
      *
      * Anti-flakiness: connect and listen use real Fiber.Unsafe latches (Fiber.initUnscoped) completing on the real kernel accept; no sleep.
      */
    def realTlsLoopback[A, S](config: NetConfig)(
        f: (Connection, Connection) => A < S
    )(using Frame): A < (S & Async & Abort[NetException | Closed]) =
        import AllowUnsafe.embrace.danger
        val driver    = PollerIoDriver.init()
        val pool      = IoDriverPool.init(Array[IoDriver[PosixHandle]](driver))
        val transport = PosixTransport.init(pool)
        discard(driver.start())
        val serverTls = serverConfig
        val clientTls = clientConfig
        Abort.run[NetException | Closed] {
            Scope.run {
                val acceptedCh = Channel.Unsafe.init[Connection](1)
                val listenerF =
                    transport.listenTls("127.0.0.1", 0, 16, serverTls) { serverConn =>
                        discard(acceptedCh.putFiber(serverConn))
                    }.safe
                for
                    listener   <- listenerF.get
                    _          <- Scope.ensure(Sync.defer(listener.close()))
                    clientConn <- transport.connectTls("127.0.0.1", listener.port, clientTls).safe.get
                    _          <- Scope.ensure(Sync.defer(clientConn.close()))
                    serverConn <- acceptedCh.takeFiber().safe.get
                    _          <- Scope.ensure(Sync.defer(serverConn.close()))
                    result     <- f(clientConn, serverConn)
                yield result
                end for
            }
        }.map { r =>
            Sync.defer(driver.close()).andThen(Abort.get(r))
        }
    end realTlsLoopback

end TlsRealEngines
