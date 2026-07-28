package kyo.net.internal

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import kyo.*
import kyo.net.Connection as NetConnection
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.internal.TlsTestCert
import kyo.net.internal.transport.*

/** Reproduce-first close-reason tests for the inline JVM NIO TLS path ([[NioTransport]] + [[NioTlsState]]).
  *
  * Per-leaf rationale: tests the JVM NioTransport TLS close-reason signal, JVM-specific. The transport is built directly via
  * [[NioTransport.init]] (not through `NetPlatform.transport`), because on a posix host the platform registry selects the unified
  * `PosixTransport` over the Nio floor, so `NetPlatform.transport` would never exercise the inline `NioTransport` / `NioTlsState` read+close
  * path that carries this finding. Constructing `NioTransport` directly forces the inline NIO path under test.
  *
  * RFC 8446 6.1 truncation distinction: the engine-driver path (`PosixHandle.peerCleanClose` / `peerEof`, surfaced by
  * `PosixTransport.installStatus`) reports `Status.CleanClose` when the peer sent an authenticated close_notify before the FIN and
  * `Status.Truncated` when the TCP connection ended with a bare FIN and no close_notify. The inline NIO transport drives a JDK
  * `SSLEngine` through its own `NioTlsState` (not `TlsEngineIo`), so setting those signals is its own responsibility: without them a NIO TLS
  * connection would always report `Status.Active` even after a bare-FIN truncation, and a length-aware caller could not detect a truncation attack. These leaves
  * assert the inline NIO connection converges on the same close-reason semantics as the engine path.
  *
  * Determinism: the peer is a real loopback NIO TLS server driven by its own `Selector` event loop (the same non-blocking primitive the
  * production `NioIoDriver` uses, never a blocked thread), so the close sequence is fully controlled: for CleanClose it wraps and writes a real
  * close_notify record before closing the socket (FIN); for Truncated it closes the socket abruptly (bare FIN, no close_notify). The client is
  * the kyo-net NIO TLS connection under test. Synchronization latches on the client's inbound-stream end (the `inbound` channel completes once
  * the ReadPump observes EOF) and a `Channel` the server offers the bound port onto once it is listening, never on a sleep or a timeout.
  */
class NioTransportTlsCloseReasonTest extends Test:

    import AllowUnsafe.embrace.danger

    /** A `trustAll` client config: the focus is the close-reason signal, not chain/identity verification, so trust is disabled to keep the
      * handshake against the embedded self-signed test cert unconditional. (The identity/fail-closed path is covered by
      * `NioTransportTlsIdentityTest`.)
      */
    private lazy val clientTls: NetTlsConfig = NetTlsConfig(trustAll = true)

    /** Build an inline NIO transport directly (forces the NioTransport path; see suite scaladoc). Process-lifetime: never closed. */
    private def mkTransport()(using Frame): NioTransport =
        NioTransport.init()

    /** How the controlled raw-JSSE server peer ends the connection after the handshake. */
    private enum ServerClose derives CanEqual:
        case CleanCloseNotify // wrap+write a real TLS close_notify, then FIN
        case BareFin          // close the socket abruptly: bare FIN, no close_notify

    /** A server-mode JDK `SSLContext` backed by the embedded test cert+key (the same material the kyo-net NIO server uses), built once. */
    private lazy val serverSslContext: SSLContext =
        NioTransport.createSslContext(
            NetTlsConfig(
                certChainPath = Present(TlsTestCert.certPath),
                privateKeyPath = Present(TlsTestCert.keyPath)
            ),
            isServer = true
        )

    // CleanClose: the peer sends an authenticated TLS close_notify before the FIN. The inline NIO client must report CleanClose. Without the wiring
    // it would report Active (the close_notify-vs-FIN signal unwired on the NIO path), which this leaf catches.
    "inline NIO TLS client reports CleanClose when the peer sends close_notify before the FIN" in {
        given Frame   = Frame.internal
        val transport = mkTransport()
        runStatusScenario(transport, ServerClose.CleanCloseNotify).map { reason =>
            assert(
                reason == NetConnection.Status.CleanClose,
                "SECURITY: inline NIO TLS connection did not report an orderly close after the peer sent a close_notify before the FIN " +
                    s"(RFC 8446 6.1); expected Status.CleanClose, got $reason. An unwired close_notify signal makes the NIO path report " +
                    "Active."
            )
            succeed
        }
    }

    // Truncated: the peer ends the TCP connection with a bare FIN and NO close_notify. The inline NIO client must report Truncated (the
    // truncation-attack condition, made observable). Without the wiring it would report Active, so a length-aware caller could not detect the truncation.
    "inline NIO TLS client reports Truncated when the peer ends with a bare FIN and no close_notify" in {
        given Frame   = Frame.internal
        val transport = mkTransport()
        runStatusScenario(transport, ServerClose.BareFin).map { reason =>
            assert(
                reason == NetConnection.Status.Truncated,
                "SECURITY: inline NIO TLS connection did not report a truncation after the peer's bare FIN with no close_notify " +
                    s"(RFC 8446 6.1); expected Status.Truncated, got $reason. An unwired NIO path reports Active, so a " +
                    "length-aware caller cannot detect a truncation attack."
            )
            succeed
        }
    }

    /** Run one end-to-end scenario: start a controlled raw-JSSE NIO TLS server peer, connect the kyo-net NIO TLS client to it, drain the
      * client's inbound stream until it ends, then read and return the client's `status`.
      *
      * The server's `serverReady` channel carries the bound port from its own Selector carrier to the test carrier; the client connects only
      * after that. Draining `client.inbound` to completion is the latch for "the inbound stream ended" (the ReadPump completes the channel on
      * EOF), so the `status` read happens strictly after the close was observed, with no sleep.
      */
    private def runStatusScenario(transport: NioTransport, closeMode: ServerClose)(using
        Frame
    ): NetConnection.Status < (Scope & Async & Abort[kyo.net.NetException | Closed]) =
        for
            serverReady <- Channel.init[Int](1)
            _ = startRawJsseServer(serverReady, closeMode)
            port   <- serverReady.take
            client <- transport.connectTls("127.0.0.1", port, clientTls).safe.get
            _      <- drainUntilEnd(client)
        yield
            val reason = client.status
            client.close()
            reason
        end for
    end runStatusScenario

    /** Take from `conn.inbound` until the channel completes (the ReadPump's EOF teardown closes it). The `Closed` that the final `take` raises
      * is the end-of-stream signal, caught here so the scenario continues to read `status`. No data is expected on this stream (the server
      * sends no application bytes, only a close), so the first take normally already ends it.
      */
    private def drainUntilEnd(conn: NetConnection)(using Frame): Unit < (Async & Abort[Closed]) =
        Abort.run[Closed] {
            Loop.foreach {
                conn.inbound.safe.take.map(_ => Loop.continue)
            }
        }.map(_ => ())

    /** Start a raw-JSSE NIO TLS server on an ephemeral loopback port and drive exactly one accepted connection's server handshake + controlled
      * close on a dedicated `Selector` event loop, mirroring how the production `NioIoDriver` runs its loop (a non-blocking `select()` carrier,
      * never a blocked thread). Offers the bound port onto `serverReady` once the listening socket is up.
      */
    private def startRawJsseServer(serverReady: Channel[Int], closeMode: ServerClose)(using Frame): Unit =
        discard(Fiber.Unsafe.init {
            val serverChannel = ServerSocketChannel.open()
            serverChannel.configureBlocking(false)
            serverChannel.bind(new InetSocketAddress("127.0.0.1", 0))
            val port     = serverChannel.socket().getLocalPort
            val selector = Selector.open()
            discard(serverChannel.register(selector, SelectionKey.OP_ACCEPT))
            discard(serverReady.unsafe.offer(port))
            try driveServer(selector, serverChannel, closeMode)
            finally
                try selector.close()
                catch case _: Throwable => ()
                try serverChannel.close()
                catch case _: Throwable => ()
            end try
        })

    /** One-connection non-blocking server event loop: accept the client, drive the JSSE server handshake to completion, then perform the
      * controlled close. The loop ends after the single connection's close is driven (the test creates exactly one client per scenario).
      */
    private def driveServer(selector: Selector, serverChannel: ServerSocketChannel, closeMode: ServerClose): Unit =
        var clientChannel: SocketChannel = null
        var engine: SSLEngine            = null
        var netIn: ByteBuffer            = null
        var netOut: ByteBuffer           = null
        var appBuf: ByteBuffer           = null
        var handshakeDone                = false
        var done                         = false

        while !done do
            discard(selector.select())
            val it = selector.selectedKeys().iterator()
            while it.hasNext do
                val key = it.next()
                it.remove()
                if key.isValid && key.isAcceptable && (clientChannel eq null) then
                    val ch = serverChannel.accept()
                    if ch ne null then
                        ch.configureBlocking(false)
                        clientChannel = ch
                        engine = serverSslContext.createSSLEngine()
                        engine.setUseClientMode(false)
                        val session = engine.getSession
                        netIn = ByteBuffer.allocate(session.getPacketBufferSize)
                        netOut = ByteBuffer.allocate(session.getPacketBufferSize)
                        appBuf = ByteBuffer.allocate(session.getApplicationBufferSize)
                        engine.beginHandshake()
                        discard(ch.register(selector, SelectionKey.OP_READ))
                    end if
                else if key.isValid && key.isReadable && !handshakeDone then
                    handshakeDone = stepHandshake(clientChannel, engine, netIn, netOut, appBuf)
                    if handshakeDone then
                        performControlledClose(clientChannel, engine, netOut, closeMode)
                        done = true
                    end if
                end if
            end while
        end while
    end driveServer

    /** Advance the server-side JSSE handshake using the bytes currently readable on the channel. Returns true once the handshake reaches
      * FINISHED / NOT_HANDSHAKING (no application I/O is needed for this test, only the handshake then the close). Returns false to wait for
      * more network bytes. A read EOF before the handshake completes throws, failing the scenario loudly rather than hanging.
      */
    private def stepHandshake(
        channel: SocketChannel,
        engine: SSLEngine,
        netIn: ByteBuffer,
        netOut: ByteBuffer,
        appBuf: ByteBuffer
    ): Boolean =
        val n = channel.read(netIn)
        if n < 0 then throw new IllegalStateException("raw JSSE server: client closed during handshake")
        netIn.flip()
        // The JDK SSLEngineResult enums (HandshakeStatus, Status) carry no Scala CanEqual, so they are compared by reference identity (eq),
        // matching the production NioTransport handshake loop.
        var status = engine.getHandshakeStatus
        var loop   = true
        while loop do
            if status eq SSLEngineResult.HandshakeStatus.NEED_UNWRAP then
                appBuf.clear()
                val res = engine.unwrap(netIn, appBuf)
                status = engine.getHandshakeStatus
                val s = res.getStatus
                if s eq SSLEngineResult.Status.OK then ()
                else if s eq SSLEngineResult.Status.BUFFER_UNDERFLOW then loop = false // need more network bytes
                else throw new IllegalStateException(s"unexpected unwrap status $s")
                end if
            else if status eq SSLEngineResult.HandshakeStatus.NEED_WRAP then
                netOut.clear()
                val res = engine.wrap(ByteBuffer.allocate(0), netOut)
                status = engine.getHandshakeStatus
                netOut.flip()
                while netOut.hasRemaining do discard(channel.write(netOut))
                if res.getStatus ne SSLEngineResult.Status.OK then
                    throw new IllegalStateException(s"unexpected wrap status ${res.getStatus}")
            else if status eq SSLEngineResult.HandshakeStatus.NEED_TASK then
                var task = engine.getDelegatedTask
                while task != null do
                    task.run()
                    task = engine.getDelegatedTask
                status = engine.getHandshakeStatus
            else
                // FINISHED or NOT_HANDSHAKING: the handshake reached a stable state, stop driving it.
                loop = false
            end if
        end while
        netIn.compact()
        val hs = engine.getHandshakeStatus
        (hs eq SSLEngineResult.HandshakeStatus.FINISHED) || (hs eq SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)
    end stepHandshake

    /** Perform the controlled close on the server side after a completed handshake.
      *   - CleanCloseNotify: `closeOutbound()` then `wrap` to produce this side's close_notify record, write the whole record, then close the
      *     socket (FIN follows the close_notify on the wire). The client's engine unwraps the close_notify and reports an orderly close.
      *   - BareFin: close the socket immediately. The peer sees a bare TCP FIN with no close_notify (the truncation condition).
      */
    private def performControlledClose(channel: SocketChannel, engine: SSLEngine, netOut: ByteBuffer, closeMode: ServerClose): Unit =
        closeMode match
            case ServerClose.CleanCloseNotify =>
                engine.closeOutbound()
                // wrap on a closeOutbound'd engine produces the close_notify alert record; loop until the engine has emitted it fully.
                var emitted = false
                while !emitted do
                    netOut.clear()
                    val res = engine.wrap(ByteBuffer.allocate(0), netOut)
                    netOut.flip()
                    while netOut.hasRemaining do discard(channel.write(netOut))
                    if engine.isOutboundDone || (res.getStatus eq SSLEngineResult.Status.CLOSED) then emitted = true
                end while
                try channel.close()
                catch case _: Throwable => ()
            case ServerClose.BareFin =>
                try channel.close()
                catch case _: Throwable => ()
        end match
    end performControlledClose

end NioTransportTlsCloseReasonTest
