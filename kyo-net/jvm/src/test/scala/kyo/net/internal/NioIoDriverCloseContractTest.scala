package kyo.net.internal

import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import kyo.*
import kyo.net.Connection as NetConnection
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.internal.transport.*
import kyo.scheduler.IOPromise

/** Close-contract leaves for [[NioIoDriver]] that the public surface cannot reach: each forces one driver-internal window with real
  * sockets, real JDK `SSLEngine`s and the driver's own selector loop.
  */
class NioIoDriverCloseContractTest extends Test with NioCloseContractFixtures:

    import AllowUnsafe.embrace.danger

    "a TLS write after the engine closed its outbound side returns instead of spinning".pendingUntilFixed(
        "J3: wrapLoop treats wrap's CLOSED with nothing consumed or produced as progress and loops forever holding engineGate"
    ) in {
        given Frame                      = Frame.internal
        val (clientEngine, serverEngine) = handshakedEnginePair("TLSv1.2")
        NioLoopbackPair.open().map { (client, peer) =>
            val driver  = NioIoDriver.init()
            val engine  = new HookedEngine(clientEngine)
            val handle  = NioHandle.initTls(client, 4096, engine, Duration.Infinity, Duration.Infinity, Frame.internal)
            var stalled = 0
            var spun    = false
            engine.onWrap { result =>
                if (result.getStatus eq SSLEngineResult.Status.CLOSED) && result.bytesConsumed() == 0 && result.bytesProduced() == 0 then
                    stalled += 1
                    // Closing the channel is the only way out of the loop once it is proven: the next write throws.
                    if stalled == SpinProof then
                        spun = true
                        client.close()
                    end if
                else stalled = 0
            }
            driver.registerChannel(handle)
            discard(driver.start())
            Sync.ensure(Sync.defer(closeAll(driver, handle, peer))) {
                val read = new IOPromise[Closed, ReadOutcome]
                driver.awaitRead(handle, read.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                // TLS 1.2: the JDK engine answers a peer close_notify by closing its own outbound side.
                discard(peer.write(ByteBuffer.wrap(closeNotify(serverEngine))))
                awaitOutcome(read, HangBound).map { outcome =>
                    outcome match
                        case Present(Result.Success(ReadOutcome.CleanClose)) => ()
                        case other => fail(s"setup: the peer's close_notify must read as CleanClose; got $other")
                    val result = driver.write(handle, Span.fromUnsafe(Array.fill[Byte](100)(1)), 0)
                    assert(!spun, s"write spun: $SpinProof consecutive wraps returned CLOSED with nothing consumed or produced")
                    assert(result == WriteResult.Error, s"a write the engine can no longer encrypt fails; got $result")
                }
            }
        }
    }

    "plaintext decrypted ahead of a bad record reaches the reader, and the read then ends with an error".pendingUntilFixed(
        "J4: an SSLException in dispatchReadTls discards plaintext already decrypted in the same read and ends the stream as PeerFin"
    ) in {
        given Frame                      = Frame.internal
        val (clientEngine, serverEngine) = handshakedEnginePair("TLSv1.2")
        NioLoopbackPair.open().map { (client, peer) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.initTls(client, 4096, clientEngine, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)
            discard(driver.start())
            Sync.ensure(Sync.defer(closeAll(driver, handle, peer))) {
                val first = new IOPromise[Closed, ReadOutcome]
                driver.awaitRead(handle, first.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                val good = Array.tabulate[Byte](100)(i => i.toByte)
                // One write, so one socket read takes the good record and the corrupted one together.
                discard(peer.write(ByteBuffer.wrap(wrapRecord(serverEngine, good) ++
                    corrupt(wrapRecord(serverEngine, Array.fill[Byte](100)(2))))))
                awaitOutcome(first, HangBound).map { outcome =>
                    outcome match
                        case Present(Result.Success(ReadOutcome.Bytes(span))) =>
                            assert(span.toArray.toList == good.toList, "the good record's plaintext arrives intact")
                        case other => fail(s"the plaintext decrypted before the bad record must reach the reader; got $other")
                    end match
                    val second = new IOPromise[Closed, ReadOutcome]
                    driver.awaitRead(handle, second.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                    awaitOutcome(second, HangBound).map { end =>
                        assert(endsWithError(end), s"a bad record ends the stream with an error, not a clean end; got $end")
                    }
                }
            }
        }
    }

    "a read whose staged ciphertext holds a bad record is failed, never thrown out of awaitRead".pendingUntilFixed(
        "J4: the caller-carrier awaitRead pre-check lets the SSLException escape, leaving no read armed and no teardown"
    ) in {
        given Frame                      = Frame.internal
        val (clientEngine, serverEngine) = handshakedEnginePair("TLSv1.2")
        NioLoopbackPair.open().map { (client, peer) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.initTls(client, 4096, clientEngine, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)
            Sync.ensure(Sync.defer(closeAll(driver, handle, peer))) {
                // A grace probe staged the peer's bad record while the pump was parked; the pump's re-arm runs on the consumer's carrier.
                handle.graceStaging.set(Chunk(corrupt(wrapRecord(serverEngine, Array.fill[Byte](100)(2)))))
                val read   = new IOPromise[Closed, ReadOutcome]
                val thrown =
                    try
                        driver.awaitRead(handle, read.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                        Absent
                    catch case e: Throwable => Present(e)
                assert(thrown.isEmpty, s"awaitRead threw ${thrown.map(_.toString).getOrElse("")} instead of completing the read")
                assert(endsWithError(read.poll()), s"the read is failed by the bad record; got ${read.poll()}")
            }
        }
    }

    "a bad record in staged ciphertext fails only its own connection, never the driver".pendingUntilFixed(
        "J4: the SSLException escapes deliverStaged on the selector carrier, and the crashed cycle closes every connection on the driver"
    ) in {
        given Frame                      = Frame.internal
        val (clientEngine, serverEngine) = handshakedEnginePair("TLSv1.2")
        NioLoopbackPair.open().map { (clientA, peerA) =>
            NioLoopbackPair.open().map { (clientB, peerB) =>
                val driver  = NioIoDriver.init()
                val handleA = NioHandle.initTls(clientA, 4096, clientEngine, Duration.Infinity, Duration.Infinity, Frame.internal)
                val handleB = NioHandle.init(clientB, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
                driver.registerChannel(handleA)
                driver.registerChannel(handleB)
                discard(driver.start())
                Sync.ensure(Sync.defer {
                    closeAll(driver, handleA, peerA)
                    driver.closeHandle(handleB)
                    peerB.close()
                }) {
                    val readB = new IOPromise[Closed, ReadOutcome]
                    driver.awaitRead(handleB, readB.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                    // A grace probe staged A's bad record after A's pump read passed its staging pre-check, so the arm's re-check hands the
                    // staged ciphertext to the selector carrier.
                    handleA.graceStaging.set(Chunk(corrupt(wrapRecord(serverEngine, Array.fill[Byte](100)(2)))))
                    val readA = new IOPromise[Closed, ReadOutcome]
                    driver.armRead(handleA, readA.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                    awaitOutcome(readA, HangBound).map { outcomeA =>
                        assert(endsWithError(outcomeA), s"A's read is failed by its bad record; got $outcomeA")
                        discard(peerB.write(ByteBuffer.wrap(Array[Byte](7))))
                        awaitOutcome(readB, HangBound).map { outcomeB =>
                            outcomeB match
                                case Present(Result.Success(ReadOutcome.Bytes(span))) => assert(span.toArray.toList == List[Byte](7))
                                case other => fail(s"connection B, which saw no bad record, must keep reading; got $other")
                        }
                    }
                }
            }
        }
    }

    "status after the peer's close_notify then FIN is never Truncated, even when a grace probe read them".pendingUntilFixed(
        "J5: the grace probe reads the FIN and sets peerEof while the close_notify sits undecrypted in its staging"
    ) in {
        given Frame                      = Frame.internal
        val (clientEngine, serverEngine) = handshakedEnginePair("TLSv1.2")
        NioLoopbackPair.open().map { (client, peer) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.initTls(client, 4096, clientEngine, Duration.Infinity, Duration.Infinity, Frame.internal)
            val tls    = handle.tls.getOrElse(throw new IllegalStateException("TLS handle without TLS state"))
            val conn   = Connection.init(handle, driver, 4)
            driver.registerChannel(handle)
            discard(driver.start())
            Sync.ensure(Sync.defer(closeAll(driver, handle, peer))) {
                // The ReadPump is parked on a full inbound channel, so no read is armed and only the grace probe reads the socket.
                val data = Array.tabulate[Byte](100)(i => i.toByte)
                discard(peer.write(ByteBuffer.wrap(wrapRecord(serverEngine, data) ++ closeNotify(serverEngine))))
                peer.shutdownOutput()
                awaitPeerClosed(driver, handle).map { latched =>
                    assert(latched, "setup: the grace probe must observe the peer's FIN")
                    val status = NioTransport.statusFor(conn, tls)
                    assert(status != NetConnection.Status.Truncated, s"the peer sent close_notify before its FIN, yet status reads $status")
                    val read = new IOPromise[Closed, ReadOutcome]
                    driver.awaitRead(handle, read.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                    awaitOutcome(read, HangBound).map { outcome =>
                        outcome match
                            case Present(Result.Success(ReadOutcome.Bytes(span))) => assert(span.toArray.toList == data.toList)
                            case other => fail(s"the staged record must be delivered; got $other")
                        assert(NioTransport.statusFor(conn, tls) == NetConnection.Status.CleanClose)
                    }
                }
            }
        }
    }

    "a local close landing while the grace probe reads reports LocalClose, never Truncated".pendingUntilFixed(
        "J6: the probe's read fails with an IOException on the locally closed channel and sets peerEof"
    ) in {
        given Frame = Frame.internal
        // The close must land inside the probe's read loop, a window no hook reaches (the probe touches no engine), so the leaf
        // repeats the race and passes only if no round reports Truncated.
        Loop.indexed { round =>
            if round >= ProbeCloseRounds then Loop.done(Absent)
            else
                probeCloseRound(round % 4).map {
                    case Present(status) => Loop.done(Present((round, status)))
                    case Absent          => Loop.continue
                }
        }.map { truncated =>
            assert(truncated.isEmpty, s"round ${truncated.map(_._1).getOrElse(-1)}: a local close read as ${truncated.map(_._2)}")
        }
    }

    "a released handle leaves no pendingReads entry behind".pendingUntilFixed(
        "J7: a TLS read dispatch re-puts the pendingReads entry after the closer's cleanupPending, holding the closed handle"
    ) in {
        given Frame                      = Frame.internal
        val (clientEngine, serverEngine) = handshakedEnginePair("TLSv1.2")
        NioLoopbackPair.open().map { (client, peer) =>
            val driver = NioIoDriver.init()
            val engine = new HookedEngine(clientEngine)
            val handle = NioHandle.initTls(client, 4096, engine, Duration.Infinity, Duration.Infinity, Frame.internal)
            val hooked = Promise.Unsafe.init[Unit, Any]()
            // The closer's cancel and cleanupPending land while the selector carrier holds the engine gate inside the dispatch; its
            // NioHandle.close then spins on the gate until the dispatch ends, which is where the test resumes it below.
            engine.onUnwrap { _ =>
                if !hooked.done() then
                    driver.cancel(handle)
                    hooked.completeUnitDiscard()
            }
            driver.registerChannel(handle)
            discard(driver.start())
            Sync.ensure(Sync.defer(closeAll(driver, handle, peer))) {
                val read = new IOPromise[Closed, ReadOutcome]
                driver.awaitRead(handle, read.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                // Half a record: the dispatch feeds it to the engine and takes the need-more-data path.
                discard(peer.write(ByteBuffer.wrap(wrapRecord(serverEngine, Array.fill[Byte](100)(3)).take(10))))
                hooked.safe.get.andThen {
                    NioHandle.close(handle)
                    driver.wakeup()
                    selectorPasses(driver, 2).map { _ =>
                        assert(!driver.hasPendingRead(handle), "pendingReads still holds the released handle")
                    }
                }
            }
        }
    }

    "an unwrap that needs a delegated task returns control instead of spinning the selector carrier".pendingUntilFixed(
        "J12: tryUnwrapBuffered repeats on every OK, and a TLS 1.2 renegotiation ClientHello leaves unwrap at OK 0/0 NEED_TASK"
    ) in {
        given Frame                      = Frame.internal
        val (clientEngine, serverEngine) = handshakedEnginePair("TLSv1.2")
        NioLoopbackPair.open().map { (client, peer) =>
            val driver  = NioIoDriver.init()
            val engine  = new HookedEngine(serverEngine)
            val handle  = NioHandle.initTls(client, 4096, engine, Duration.Infinity, Duration.Infinity, Frame.internal)
            var stalled = 0
            var spun    = false
            engine.onUnwrap { result =>
                if (result.getStatus eq SSLEngineResult.Status.OK) && result.bytesConsumed() == 0 && result.bytesProduced() == 0 then
                    stalled += 1
                    // Throwing is the only way out of the loop once it is proven, and it must keep throwing: the staged-delivery path re-enters
                    // the same loop on later selector passes, and a single throw leaves the carrier spinning past the leaf's end.
                    if stalled >= SpinProof then
                        spun = true
                        throw new SSLException("spin proven")
                    end if
                else stalled = 0
            }
            driver.registerChannel(handle)
            discard(driver.start())
            Sync.ensure(Sync.defer(closeAll(driver, handle, peer))) {
                val read = new IOPromise[Closed, ReadOutcome]
                driver.awaitRead(handle, read.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                clientEngine.beginHandshake()
                val hello = ByteBuffer.allocate(clientEngine.getSession.getPacketBufferSize)
                discard(clientEngine.wrap(ByteBuffer.allocate(0), hello))
                hello.flip()
                discard(peer.write(hello))
                awaitOutcome(read, HangBound).map { outcome =>
                    assert(outcome.nonEmpty, "setup: the dispatch must complete the read")
                    assert(!spun, s"unwrap spun: $SpinProof consecutive calls returned OK with nothing consumed or produced")
                }
            }
        }
    }

    /** One J6 round: a TLS connection whose pump is parked, a grace probe reading 1 MiB of peer records, and a local close issued after
      * `passes` selector passes. Returns the status when the local close read as anything but LocalClose.
      */
    private def probeCloseRound(passes: Int)(using Frame): Maybe[NetConnection.Status] < (Async & Abort[java.io.IOException]) =
        val (clientEngine, serverEngine) = handshakedEnginePair("TLSv1.2")
        NioLoopbackPair.open(
            configureClient = _.setOption(java.net.StandardSocketOptions.SO_RCVBUF, Integer.valueOf(4 << 20)): Unit
        ).map { (client, peer) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.initTls(client, 64 << 10, clientEngine, Duration.Infinity, Duration.Infinity, Frame.internal)
            val tls    = handle.tls.getOrElse(throw new IllegalStateException("TLS handle without TLS state"))
            val conn   = Connection.init(handle, driver, 4)
            driver.registerChannel(handle)
            discard(driver.start())
            Sync.ensure(Sync.defer(closeAll(driver, handle, peer))) {
                val record = wrapRecord(serverEngine, Array.fill[Byte](16384)(5))
                val burst  = Array.concat(Seq.fill(64)(record)*)
                discard(peer.write(ByteBuffer.wrap(burst)))
                discard(driver.isPeerClosed(handle))
                selectorPasses(driver, passes).map { _ =>
                    conn.close()
                    // A probe read the close interrupted ends within the cycle that ran it.
                    selectorPasses(driver, 2).map { _ =>
                        val status = NioTransport.statusFor(conn, tls)
                        if status == NetConnection.Status.LocalClose then Absent else Present(status)
                    }
                }
            }
        }
    end probeCloseRound

end NioIoDriverCloseContractTest

/** Real JDK TLS peers, record helpers and selector-loop barriers shared by the NIO close-contract leaves. */
private[internal] trait NioCloseContractFixtures:

    import AllowUnsafe.embrace.danger

    /** Bound on a latch that only a hang would exhaust. */
    val HangBound: Duration = 10.seconds

    /** Consecutive no-progress engine results that prove a loop cannot end on its own. */
    val SpinProof: Int = 10000

    /** J6 race rounds; the measured red rate is recorded with the leaf's commit. */
    val ProbeCloseRounds: Int = 200

    /** A client and server `SSLEngine` handshaken in memory over `protocol`, so no handshake byte touches the socket under test.
      * TLSv1.2 keeps the exchange free of post-handshake records (a TLS 1.3 NewSessionTicket would ride ahead of the first application
      * record).
      */
    def handshakedEnginePair(protocol: String)(using Frame): (SSLEngine, SSLEngine) =
        val serverCtx = NioTransport.createSslContext(
            NetTlsConfig(certChainPath = Present(TlsTestCert.certPath), privateKeyPath = Present(TlsTestCert.keyPath)),
            isServer = true
        )
        val clientCtx = NioTransport.createSslContext(NetTlsConfig(trustAll = true), isServer = false)
        val client    = clientCtx.createSSLEngine()
        val server    = serverCtx.createSSLEngine()
        client.setUseClientMode(true)
        server.setUseClientMode(false)
        client.setEnabledProtocols(Array(protocol))
        server.setEnabledProtocols(Array(protocol))
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
    def wrapRecord(engine: SSLEngine, plain: Array[Byte]): Array[Byte] =
        val out    = ByteBuffer.allocate(engine.getSession.getPacketBufferSize)
        val result = engine.wrap(ByteBuffer.wrap(plain), out)
        if !(result.getStatus eq SSLEngineResult.Status.OK) then throw new IllegalStateException(s"wrap failed: $result")
        out.flip()
        remaining(out)
    end wrapRecord

    /** `engine`'s close_notify record. */
    def closeNotify(engine: SSLEngine): Array[Byte] =
        engine.closeOutbound()
        val out = ByteBuffer.allocate(engine.getSession.getPacketBufferSize)
        discard(engine.wrap(ByteBuffer.allocate(0), out))
        out.flip()
        remaining(out)
    end closeNotify

    /** `record` with its last byte (inside the AEAD tag) flipped: a record the receiver cannot authenticate. */
    def corrupt(record: Array[Byte]): Array[Byte] =
        val bad = record.clone()
        bad(bad.length - 1) = (bad(bad.length - 1) ^ 0x5a).toByte
        bad
    end corrupt

    /** Decrypts `bytes` with `engine` record by record until they run out or end mid-record; returns the plaintext and whether a
      * close_notify was among the records.
      */
    def unwrapAll(engine: SSLEngine, bytes: Array[Byte]): (Array[Byte], Boolean) =
        val in    = ByteBuffer.wrap(bytes)
        val app   = ByteBuffer.allocate(engine.getSession.getApplicationBufferSize)
        val plain = new java.io.ByteArrayOutputStream
        @scala.annotation.tailrec
        def loop(): Boolean =
            app.clear()
            val result = engine.unwrap(in, app)
            app.flip()
            plain.write(app.array(), 0, app.limit())
            if result.getStatus eq SSLEngineResult.Status.CLOSED then true
            else if (result.getStatus eq SSLEngineResult.Status.OK) && result.bytesConsumed() > 0 && in.hasRemaining then loop()
            else false
        end loop
        val closed = in.hasRemaining && loop()
        (plain.toByteArray, closed)
    end unwrapAll

    def remaining(buf: ByteBuffer): Array[Byte] =
        val arr = new Array[Byte](buf.remaining())
        buf.get(arr)
        arr
    end remaining

    /** Whether a read ended with an error rather than data or a clean end. */
    def endsWithError(outcome: Maybe[Result[Closed, ReadOutcome]]): Boolean =
        outcome match
            case Present(Result.Success(ReadOutcome.Failed(_))) => true
            case Present(Result.Failure(_))                     => true
            case Present(Result.Panic(_))                       => true
            case _                                              => false

    /** Bounded await on a read promise: Present(outcome) when it completes, Absent when nothing completes it within `bound`. */
    def awaitOutcome(p: IOPromise[Closed, ReadOutcome], bound: Duration)(using Frame): Maybe[Result[Closed, ReadOutcome]] < Async =
        Abort.run[Closed | Timeout](Async.timeout(bound)(p.asInstanceOf[Fiber.Unsafe[ReadOutcome, Abort[Closed]]].safe.get)).map {
            case Result.Success(outcome)        => Present(Result.succeed(outcome))
            case Result.Failure(_: Timeout)     => Absent
            case Result.Failure(closed: Closed) => Present(Result.fail(closed))
            case Result.Panic(e)                => Present(Result.panic(e))
        }

    /** At least `n` complete selector cycles of `driver`'s running loop. A listener release is completed by the drain of the first cycle
      * that starts after it is queued, so each release in the chain, queued after the previous one completed, ends one cycle later.
      */
    def selectorPasses(driver: NioIoDriver, n: Int)(using Frame): Unit < Async =
        Loop.repeat(n) {
            Sync.defer {
                val released = Promise.Unsafe.init[Unit, Any]()
                val unused   = ServerSocketChannel.open()
                unused.close()
                driver.releaseListener(unused, released)
                released.safe.get
            }
        }

    /** Kick the grace probe the way the ReadPump's grace expiry does, three selector cycles apart, until it latches the peer's close. A probe
      * armed by a kick is applied by the next cycle and dispatched by the one after, so each round gives it room to run.
      */
    def awaitPeerClosed(driver: NioIoDriver, handle: NioHandle)(using Frame): Boolean < Async =
        Loop.indexed { round =>
            if driver.isPeerClosed(handle) then Loop.done(true)
            else if round >= 50 then Loop.done(false)
            else selectorPasses(driver, 3).andThen(Loop.continue)
        }

    def closeAll(driver: NioIoDriver, handle: NioHandle, peer: SocketChannel)(using Frame): Unit =
        driver.closeHandle(handle)
        peer.close()
        driver.close()
    end closeAll

    /** `inner`, reporting every wrap and unwrap result to a callback right after the call: the point where the driver holds the result and
      * has not acted on it yet.
      */
    final class HookedEngine(inner: SSLEngine) extends SSLEngine:
        @volatile private var wrapHook: SSLEngineResult => Unit   = _ => ()
        @volatile private var unwrapHook: SSLEngineResult => Unit = _ => ()

        def onWrap(f: SSLEngineResult => Unit): Unit   = wrapHook = f
        def onUnwrap(f: SSLEngineResult => Unit): Unit = unwrapHook = f

        override def unwrap(src: ByteBuffer, dsts: Array[ByteBuffer], offset: Int, length: Int): SSLEngineResult =
            val result = inner.unwrap(src, dsts, offset, length)
            unwrapHook(result)
            result
        end unwrap

        override def wrap(srcs: Array[ByteBuffer], offset: Int, length: Int, dst: ByteBuffer): SSLEngineResult =
            val result = inner.wrap(srcs, offset, length, dst)
            wrapHook(result)
            result
        end wrap

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

end NioCloseContractFixtures
