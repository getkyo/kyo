package kyo.net.internal

import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import kyo.*
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.internal.transport.*
import kyo.scheduler.IOPromise

/** Close-contract leaves for [[NioIoDriver]] that the public surface cannot reach: each forces one driver-internal window with real
  * sockets, real JDK `SSLEngine`s and the driver's own selector loop.
  */
class NioIoDriverCloseContractTest extends Test:

    import AllowUnsafe.embrace.danger

    /** A client and server `SSLEngine` handshaken in memory over TLSv1.2, so no handshake byte touches the socket under test and no
      * post-handshake record (a TLS 1.3 NewSessionTicket) rides ahead of the first application record.
      */
    private def handshakedEnginePair()(using Frame): (SSLEngine, SSLEngine) =
        val serverCtx = NioTransport.createSslContext(
            NetTlsConfig(certChainPath = Present(TlsTestCert.certPath), privateKeyPath = Present(TlsTestCert.keyPath)),
            isServer = true
        )
        val clientCtx = NioTransport.createSslContext(NetTlsConfig(trustAll = true), isServer = false)
        val client    = clientCtx.createSSLEngine()
        val server    = serverCtx.createSSLEngine()
        client.setUseClientMode(true)
        server.setUseClientMode(false)
        client.setEnabledProtocols(Array("TLSv1.2"))
        server.setEnabledProtocols(Array("TLSv1.2"))
        val cToS  = ByteBuffer.allocate(client.getSession.getPacketBufferSize * 2)
        val sToC  = ByteBuffer.allocate(server.getSession.getPacketBufferSize * 2)
        val cApp  = ByteBuffer.allocate(client.getSession.getApplicationBufferSize)
        val sApp  = ByteBuffer.allocate(server.getSession.getApplicationBufferSize)
        val empty = ByteBuffer.allocate(0)
        client.beginHandshake()
        server.beginHandshake()
        def runTasks(engine: SSLEngine): Unit =
            var task = engine.getDelegatedTask
            while task ne null do
                task.run()
                task = engine.getDelegatedTask
        end runTasks
        // Java enums (SSLEngineResult.HandshakeStatus) have no Scala CanEqual, so compare by reference identity (eq).
        def step(engine: SSLEngine, out: ByteBuffer, in: ByteBuffer, app: ByteBuffer): Unit =
            val hs = engine.getHandshakeStatus
            if hs eq SSLEngineResult.HandshakeStatus.NEED_TASK then runTasks(engine)
            else if hs eq SSLEngineResult.HandshakeStatus.NEED_WRAP then discard(engine.wrap(empty, out))
            else if hs eq SSLEngineResult.HandshakeStatus.NEED_UNWRAP then
                in.flip()
                if in.hasRemaining then discard(engine.unwrap(in, app))
                discard(in.compact())
            end if
        end step
        def handshaken(engine: SSLEngine): Boolean =
            engine.getHandshakeStatus eq SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        var rounds = 0
        while rounds < 200 && !(handshaken(client) && handshaken(server)) do
            rounds += 1
            step(client, cToS, sToC, cApp)
            step(server, sToC, cToS, sApp)
        end while
        if !(handshaken(client) && handshaken(server)) then
            throw new IllegalStateException(s"in-memory TLS handshake did not complete after $rounds rounds")
        (client, server)
    end handshakedEnginePair

    /** `plain` as one TLS record from `engine`. */
    private def wrapRecord(engine: SSLEngine, plain: Array[Byte]): Array[Byte] =
        val out    = ByteBuffer.allocate(engine.getSession.getPacketBufferSize)
        val result = engine.wrap(ByteBuffer.wrap(plain), out)
        if !(result.getStatus eq SSLEngineResult.Status.OK) then throw new IllegalStateException(s"wrap failed: $result")
        out.flip()
        val arr = new Array[Byte](out.remaining())
        out.get(arr)
        arr
    end wrapRecord

    /** At least `n` complete selector cycles of `driver`'s running loop: a listener release completes on the drain of the first cycle that
      * starts after it is queued, so each release in the chain ends one cycle later.
      */
    private def selectorPasses(driver: NioIoDriver, n: Int)(using Frame): Unit < Async =
        Loop.repeat(n) {
            Sync.defer {
                val released = Promise.Unsafe.init[Unit, Any]()
                val unused   = ServerSocketChannel.open()
                unused.close()
                driver.releaseListener(unused, released)
                released.safe.get
            }
        }

    /** `inner`, reporting every unwrap result to a callback right after the call: the point where the driver holds the result and has not
      * acted on it yet.
      */
    final private class HookedEngine(inner: SSLEngine) extends SSLEngine:
        @volatile private var unwrapHook: SSLEngineResult => Unit = _ => ()

        def onUnwrap(f: SSLEngineResult => Unit): Unit = unwrapHook = f

        override def unwrap(src: ByteBuffer, dsts: Array[ByteBuffer], offset: Int, length: Int): SSLEngineResult =
            val result = inner.unwrap(src, dsts, offset, length)
            unwrapHook(result)
            result
        end unwrap

        override def wrap(srcs: Array[ByteBuffer], offset: Int, length: Int, dst: ByteBuffer): SSLEngineResult =
            inner.wrap(srcs, offset, length, dst)
        override def getDelegatedTask(): Runnable                          = inner.getDelegatedTask()
        override def closeInbound(): Unit                                  = inner.closeInbound()
        override def isInboundDone(): Boolean                              = inner.isInboundDone()
        override def closeOutbound(): Unit                                 = inner.closeOutbound()
        override def isOutboundDone(): Boolean                             = inner.isOutboundDone()
        override def getSupportedCipherSuites(): Array[String]             = inner.getSupportedCipherSuites()
        override def getEnabledCipherSuites(): Array[String]               = inner.getEnabledCipherSuites()
        override def setEnabledCipherSuites(suites: Array[String]): Unit   = inner.setEnabledCipherSuites(suites)
        override def getSupportedProtocols(): Array[String]                = inner.getSupportedProtocols()
        override def getEnabledProtocols(): Array[String]                  = inner.getEnabledProtocols()
        override def setEnabledProtocols(protocols: Array[String]): Unit   = inner.setEnabledProtocols(protocols)
        override def getSession(): javax.net.ssl.SSLSession                = inner.getSession()
        override def beginHandshake(): Unit                                = inner.beginHandshake()
        override def getHandshakeStatus(): SSLEngineResult.HandshakeStatus = inner.getHandshakeStatus()
        override def setUseClientMode(mode: Boolean): Unit                 = inner.setUseClientMode(mode)
        override def getUseClientMode(): Boolean                           = inner.getUseClientMode()
        override def setNeedClientAuth(need: Boolean): Unit                = inner.setNeedClientAuth(need)
        override def getNeedClientAuth(): Boolean                          = inner.getNeedClientAuth()
        override def setWantClientAuth(want: Boolean): Unit                = inner.setWantClientAuth(want)
        override def getWantClientAuth(): Boolean                          = inner.getWantClientAuth()
        override def setEnableSessionCreation(flag: Boolean): Unit         = inner.setEnableSessionCreation(flag)
        override def getEnableSessionCreation(): Boolean                   = inner.getEnableSessionCreation()
    end HookedEngine

    "a released handle leaves no pendingReads entry behind" in {
        val (clientEngine, serverEngine) = handshakedEnginePair()
        NioLoopbackPair.open().map { (client, peer) =>
            val driver = NioIoDriver.init()
            val engine = new HookedEngine(clientEngine)
            val handle = NioHandle.initTls(client, 4096, engine, Duration.Infinity, Duration.Infinity, Frame.internal)
            val read   = new IOPromise[Closed, ReadOutcome]
            val hooked = Promise.Unsafe.init[Unit, Any]()
            val closer = Promise.Unsafe.init[Unit, Any]()
            // The release is decided on its own thread while the selector carrier holds the gate inside the dispatch: it takes the release
            // claim and leaves its sweep to the gate's holder. Once the claim is taken the hook lets the dispatch go on to its need-more-data
            // path, which must not re-put the read entry of a released handle.
            engine.onUnwrap { _ =>
                if !hooked.done() then
                    hooked.completeDiscard(Result.succeed(()))
                    val t = new Thread(() =>
                        driver.closeHandle(handle)
                        closer.completeDiscard(Result.succeed(()))
                    )
                    t.setDaemon(true)
                    t.start()
                    while !closer.done() do java.lang.Thread.onSpinWait()
            }
            driver.registerChannel(handle)
            discard(driver.start())
            Sync.ensure(Sync.defer {
                peer.close()
                driver.close()
            }) {
                driver.awaitRead(handle, read.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                // Half a record: the dispatch feeds it to the engine and takes the need-more-data path, which re-puts the read entry.
                discard(peer.write(ByteBuffer.wrap(wrapRecord(serverEngine, Array.fill[Byte](100)(3)).take(10))))
                hooked.safe.get.andThen(closer.safe.get).andThen {
                    selectorPasses(driver, 2).map { _ =>
                        assert(!driver.hasPendingRead(handle), "pendingReads still holds the released handle")
                    }
                }
            }
        }
    }

end NioIoDriverCloseContractTest
