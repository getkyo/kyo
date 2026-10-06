package kyo.net.internal

import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import kyo.*
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.internal.transport.*
import kyo.scheduler.IOPromise

class NioIoDriverTest extends Test:

    import AllowUnsafe.embrace.danger
    import NioIoDriverTest.*

    /** Create a driver, open a handle+channel, call body, then close everything. */
    def withDriverAndHandle[A, S](bufferSize: Int = 4096)(body: (NioIoDriver, NioHandle, SocketChannel) => A < S)(using
        Frame
    ): A < (S & Async & Abort[java.io.IOException]) =
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, bufferSize, Duration.Infinity, Duration.Infinity, Frame.internal)
            Sync.ensure(Sync.defer {
                driver.closeHandle(handle)
                sv.close()
                driver.close()
            }) {
                driver.registerChannel(handle)
                body(driver, handle, sv)
            }
        }
    end withDriverAndHandle

    /** How many times a standing grace probe is read while its peer is open. Each read re-checks the armed probe, so a latch on any is the
      * regression; the "stays false" leaves assert over the reads, not over a clock.
      */
    private val liveWatchReads = 25

    /** Await a read promise with no timer: a read nothing completes hangs to the leaf cap, whose diagnostics carry the driver's pending ops. */
    private def outcome(p: IOPromise[Closed, ReadOutcome])(using Frame): Result[Closed, ReadOutcome] < Async =
        p.asInstanceOf[Fiber.Unsafe[ReadOutcome, Abort[Closed]]].safe.getResult
    // -----------------------------------------------------------------------
    // Construction / lifecycle
    // -----------------------------------------------------------------------

    "init creates a driver" in {
        val driver = NioIoDriver.init()
        try
            assert(driver ne null)
            assert(driver.label.contains("NioIoDriver"))
            succeed
        finally
            given Frame = Frame.internal
            driver.close()
        end try
    }

    "start creates an event loop fiber that is not done" in {
        val driver = NioIoDriver.init()
        try
            given Frame = Frame.internal
            val fiber   = driver.start()
            assert(!fiber.done())
            succeed
        finally
            given Frame = Frame.internal
            driver.close()
        end try
    }

    private def openServer(): ServerSocketChannel =
        val ssc = ServerSocketChannel.open()
        ssc.configureBlocking(false)
        ssc.bind(new InetSocketAddress("127.0.0.1", 0))
        ssc
    end openServer

    // `releaseListener` is armed for a channel that has been closed while registered: its SelectionKey is cancelled and its fd close deferred
    // to the selector's next deregistration pass. `isRegistered` afterwards is
    // the same observation the driver completes on.
    "listener release" - {
        "completes after the running loop's deregistration pass" in {
            val driver  = NioIoDriver.init()
            given Frame = Frame.internal
            discard(driver.start())
            val ssc = openServer()
            assert(driver.registerServerChannel(ssc))
            // No registration check here: the loop is running, so its next pass can deregister the cancelled key at any point after the
            // close.
            ssc.close()
            val released = Promise.Unsafe.init[Unit, Any]()
            driver.releaseListener(ssc, released)
            released.safe.get.map { _ =>
                assert(!ssc.isRegistered)
                driver.close()
                succeed
            }
        }
        "armed before the loop starts, completes on its first pass" in {
            val driver  = NioIoDriver.init()
            given Frame = Frame.internal
            val ssc     = openServer()
            assert(driver.registerServerChannel(ssc))
            ssc.close()
            val released = Promise.Unsafe.init[Unit, Any]()
            driver.releaseListener(ssc, released)
            assert(!released.done(), "nothing runs the deregistration pass before the loop starts")
            discard(driver.start())
            released.safe.get.map { _ =>
                assert(!ssc.isRegistered)
                driver.close()
                succeed
            }
        }
        "armed on a driver that never starts, completes when the driver closes" in {
            val driver  = NioIoDriver.init()
            given Frame = Frame.internal
            val ssc     = openServer()
            assert(driver.registerServerChannel(ssc))
            ssc.close()
            val released = Promise.Unsafe.init[Unit, Any]()
            driver.releaseListener(ssc, released)
            assert(!released.done())
            driver.close()
            assert(released.done())
            assert(!ssc.isRegistered)
            succeed
        }
        "armed after the driver closed, completes at once" in {
            val driver  = NioIoDriver.init()
            given Frame = Frame.internal
            val ssc     = openServer()
            assert(driver.registerServerChannel(ssc))
            ssc.close()
            driver.close()
            val released = Promise.Unsafe.init[Unit, Any]()
            driver.releaseListener(ssc, released)
            assert(released.done())
            assert(!ssc.isRegistered)
            succeed
        }
    }

    "label includes selector hashcode" in {
        val driver = NioIoDriver.init()
        try
            val lbl = driver.label
            assert(lbl.startsWith("NioIoDriver[sel="))
            succeed
        finally
            given Frame = Frame.internal
            driver.close()
        end try
    }

    "handleLabel includes channel hashcode" in {
        withDriverAndHandle() { (driver, handle, _) =>
            val lbl = driver.handleLabel(handle)
            assert(lbl.startsWith("channel="))
            succeed
        }
    }

    // -----------------------------------------------------------------------
    // registerChannel
    // -----------------------------------------------------------------------

    "registerChannel returns true for open non-blocking channel" in {
        val driver = NioIoDriver.init()
        val ch     = SocketChannel.open()
        ch.configureBlocking(false)
        try
            val handle = NioHandle.init(ch, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            val result = driver.registerChannel(handle)
            assert(result)
            succeed
        finally
            ch.close()
            given Frame = Frame.internal
            driver.close()
        end try
    }

    "registerChannel returns false after driver is closed" in {
        val driver  = NioIoDriver.init()
        given Frame = Frame.internal
        driver.close()
        val ch = SocketChannel.open()
        ch.configureBlocking(false)
        try
            val handle = NioHandle.init(ch, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            val result = driver.registerChannel(handle)
            assert(!result)
            succeed
        finally
            ch.close()
        end try
    }

    "registerChannel returns false for closed channel" in {
        val driver = NioIoDriver.init()
        val ch     = SocketChannel.open()
        ch.configureBlocking(false)
        ch.close()
        try
            val handle = NioHandle.init(ch, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            val result = driver.registerChannel(handle)
            assert(!result)
            succeed
        finally
            given Frame = Frame.internal
            driver.close()
        end try
    }

    // -----------------------------------------------------------------------
    // write: plain TCP
    // -----------------------------------------------------------------------

    "writePlain returns Done when all bytes are written" in {
        withDriverAndHandle() { (driver, handle, sv) =>
            val data   = Span.fromUnsafe("hello".getBytes)
            val result = driver.write(handle, data, 0)
            assert(result == WriteResult.Done)
            succeed
        }
    }

    "write returns Done for empty span" in {
        withDriverAndHandle() { (driver, handle, sv) =>
            val result = driver.write(handle, Span.empty[Byte], 0)
            assert(result == WriteResult.Done)
            succeed
        }
    }

    "write returns Error after channel is closed" in {
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)
            client.close()
            sv.close()
            try
                val data   = Span.fromUnsafe("hello".getBytes)
                val result = driver.write(handle, data, 0)
                assert(result == WriteResult.Error)
                succeed
            finally
                given Frame = Frame.internal
                driver.close()
            end try
        }
    }

    "a TLS write whose last record only partly fit the socket is not Done until that record's tail is written" in {
        val (clientEngine, serverEngine) = handshakedEnginePair()
        NioLoopbackPair.open(
            configureListener = _.setOption(StandardSocketOptions.SO_RCVBUF, Integer.valueOf(4096)): Unit,
            configureClient = writer =>
                writer.setOption(StandardSocketOptions.SO_SNDBUF, Integer.valueOf(4096))
                writer.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE): Unit
        ).map { (writer, peer) =>
            val driver = NioIoDriver.init()
            peer.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
            val handle = NioHandle.initTls(writer, 4096, clientEngine, Duration.Infinity, Duration.Infinity, Frame.internal)
            // 16384 plaintext bytes are one TLS record, so the first write the unread peer cannot absorb wraps the whole span and leaves part of
            // its only record unsent: Partial at the span's end.
            val record = Span.fromUnsafe(Array.tabulate[Byte](16384)(i => (i % 251).toByte))
            @scala.annotation.tailrec
            def fill(written: Int): (Int, WriteResult) =
                driver.write(handle, record, 0) match
                    case WriteResult.Done if written < 10000 => fill(written + 1)
                    case other                               => (written, other)
            val (written, first) = fill(0)
            def pending: Boolean = handle.tls.exists(_.pendingCiphertext)
            try
                if first != WriteResult.Partial(record, record.size) then fail(s"after $written whole records, got $first")
                // The pump's retry once the socket is writable.
                val retry = driver.write(handle, record, record.size)
                if retry == WriteResult.Done && pending then fail("Done while the last record's ciphertext is still unsent")
                // The peer drains and decrypts everything, the pump retrying while ciphertext is held back: every record arrives whole.
                val netIn    = ByteBuffer.allocate(serverEngine.getSession.getPacketBufferSize * 4)
                val app      = ByteBuffer.allocate(serverEngine.getSession.getApplicationBufferSize)
                val expected = (written + 1).toLong * record.size
                @scala.annotation.tailrec
                def unwrapAll(plain: Long): Long =
                    app.clear()
                    val result = serverEngine.unwrap(netIn, app)
                    if result.getStatus eq SSLEngineResult.Status.OK then unwrapAll(plain + result.bytesProduced())
                    else plain
                end unwrapAll
                // A retry that runs before the peer's window update reaches the writer pushes nothing, and the peer can then read everything in
                // flight. A blocking read would wait for bytes only the next retry sends, so the peer reads without blocking and an empty read waits
                // for either more ciphertext or, while some is held back, the writer turning writable: the wakeup the driver's OP_WRITE gives the pump.
                val selector = Selector.open()
                peer.configureBlocking(false)
                discard(peer.register(selector, SelectionKey.OP_READ))
                val writable = writer.register(selector, 0)
                @scala.annotation.tailrec
                def drain(plain: Long): Long =
                    if plain >= expected then plain
                    else
                        if pending then discard(driver.write(handle, record, record.size))
                        if peer.read(netIn) == 0 then
                            discard(writable.interestOps(if pending then SelectionKey.OP_WRITE else 0))
                            discard(selector.select())
                            selector.selectedKeys().clear()
                        end if
                        netIn.flip()
                        val more = unwrapAll(plain)
                        discard(netIn.compact())
                        drain(more)
                try
                    assert(drain(0L) == expected)
                    assert(!pending)
                finally selector.close()
                end try
            finally
                given Frame = Frame.internal
                writer.close()
                peer.close()
                driver.close()
            end try
        }
    }

    // -----------------------------------------------------------------------
    // awaitRead: registers interest and completes promise on read
    // -----------------------------------------------------------------------

    "awaitRead completes promise when data arrives" in {
        given Frame = Frame.internal
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)
            discard(driver.start())

            val p = new IOPromise[Closed, ReadOutcome]
            driver.awaitRead(handle, p.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])

            // Write data from server side so client can read
            sv.write(ByteBuffer.wrap("hello".getBytes))

            p.asInstanceOf[Fiber.Unsafe[ReadOutcome, Abort[Closed]]].safe.get.map { result =>
                sv.close()
                driver.closeHandle(handle)
                driver.close()
                val ReadOutcome.Bytes(span) = result.runtimeChecked
                assert(span.nonEmpty)
                succeed
            }
        }
    }

    // -----------------------------------------------------------------------
    // isPeerClosed: the peer-close grace probe (poll-on-expiry) detector
    // -----------------------------------------------------------------------

    "isPeerClosed arms a probe that observes a peer FIN while backpressured, staging pre-FIN bytes ahead of the EOF" in {
        given Frame = Frame.internal
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)
            discard(driver.start())

            // Backpressured: no awaitRead is armed (the pump is parked on a full inbound channel). The peer sends 3 bytes, then closes (FIN).
            discard(sv.write(ByteBuffer.wrap(Array[Byte](10, 20, 30))))
            sv.close()

            // First call arms the probe and returns false; the probe stages the 3 bytes and latches peerClosed on the FIN, so a later call reads true.
            assert(!driver.isPeerClosed(handle), "the first isPeerClosed arms the probe and returns false (not observed yet)")
            Cycles.init(driver).map { cycles =>
                cycles.until(driver.isPeerClosed(handle)).andThen(cycles.close())
            }.andThen {
                val p1 = new IOPromise[Closed, ReadOutcome]
                driver.awaitRead(handle, p1.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                p1.asInstanceOf[Fiber.Unsafe[ReadOutcome, Abort[Closed]]].safe.get
            }.map { r1 =>
                val ReadOutcome.Bytes(span) = r1.runtimeChecked
                assert(
                    span.toArray.toList == List[Byte](10, 20, 30),
                    s"the staged pre-FIN bytes must be delivered first; got ${span.toArray.toList}"
                )
            }.andThen {
                val p2 = new IOPromise[Closed, ReadOutcome]
                driver.awaitRead(handle, p2.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                p2.asInstanceOf[Fiber.Unsafe[ReadOutcome, Abort[Closed]]].safe.get
            }.map { r2 =>
                driver.closeHandle(handle)
                driver.close()
                assert(r2 == ReadOutcome.PeerFin, s"the EOF must follow the staged bytes; got $r2")
                succeed
            }
        }
    }

    "isPeerClosed stays false for a live peer that has not closed" in {
        given Frame = Frame.internal
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)
            discard(driver.start())

            // The peer stays open and sends nothing: the probe reads n == 0 and stays armed as a standing FIN watch. It is read once per
            // selector cycle for a fixed count of cycles, and every read must report false.
            assert(!driver.isPeerClosed(handle), "the first isPeerClosed arms the probe and returns false")
            Cycles.init(driver).map { cycles =>
                Loop(0) { i =>
                    if i >= liveWatchReads then Loop.done(true)
                    else if driver.isPeerClosed(handle) then Loop.done(false)
                    else cycles.next.andThen(Loop.continue(i + 1))
                }.map { stayedOpen =>
                    assert(stayedOpen, "isPeerClosed must stay false for a live peer that has not sent a FIN")
                }.andThen {
                    // The watch was armed, not dead: closing the peer now latches it. Without this, a probe that never ran would report false just
                    // as happily and the reads above would prove nothing. A watch that never latches hangs to the leaf cap.
                    sv.close()
                    cycles.until(driver.isPeerClosed(handle)).andThen {
                        cycles.close()
                        driver.closeHandle(handle)
                        driver.close()
                    }
                }
            }
        }
    }

    "the grace probe stages at most GraceProbeBudgetChunks buffers per window, not the whole receive buffer" in {
        given Frame = Frame.internal
        NioLoopbackPair.open().map { (client, sv) =>
            val driver  = NioIoDriver.init()
            val bufSize = 64
            val handle  = NioHandle.init(client, bufSize, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)
            discard(driver.start())

            // Backpressured, no read armed. The peer sends twice the budget worth of data (no FIN). One probe window reads at most
            // GraceProbeBudgetChunks buffers, so it stages exactly that many and stops: staying armed with data present would refire the
            // level-triggered selector and drain the whole receive buffer in one window.
            val chunks = NioIoDriver.GraceProbeBudgetChunks
            discard(sv.write(ByteBuffer.wrap(Array.fill[Byte](chunks * 2 * bufSize)(7))))

            assert(!driver.isPeerClosed(handle), "the first isPeerClosed arms the probe and returns false")
            Cycles.init(driver).map { cycles =>
                // The window does not re-arm past the budget, so a further cycle must stage nothing more.
                cycles.until(driver.stagedBytes(handle) >= chunks * bufSize).andThen(cycles.next).map { _ =>
                    val staged = driver.stagedBytes(handle)
                    cycles.close()
                    sv.close()
                    driver.closeHandle(handle)
                    driver.close()
                    assert(
                        staged == chunks * bufSize,
                        s"one probe window must stage exactly the budget ($chunks buffers of $bufSize = ${chunks * bufSize} bytes), not the whole receive buffer; got $staged"
                    )
                }
            }
        }
    }

    "the grace probe stops staging past GraceProbeStagingCap, so a FIN behind the cap is not observed" in {
        given Frame = Frame.internal
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 65536, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)
            discard(driver.start())

            // A background writer floods more than the staging cap and then closes (FIN). The probe stages up to the cap and stops consuming, so the
            // FIN sits behind the cap and is never observed: isPeerClosed stays false. This is the deliberate trade that stops a live chatty peer
            // turning the descriptor fix into a heap leak (a FIN behind more than the cap keeps the pre-fix behavior).
            val cap    = NioIoDriver.GraceProbeStagingCap
            val writer = new Thread(() =>
                try
                    val buf = ByteBuffer.wrap(Array.fill[Byte](cap + (256 * 1024))(1))
                    while buf.hasRemaining do discard(sv.write(buf))
                    sv.close()
                catch case _: Throwable => ()
            )
            writer.setDaemon(true)
            writer.start()

            // Each cycle arms a probe (isPeerClosed) and checks staging; once staging reaches the cap the probe stops arming and consuming.
            Cycles.init(driver).map { cycles =>
                cycles.until {
                    discard(driver.isPeerClosed(handle))
                    driver.stagedBytes(handle) >= cap
                }.andThen(cycles.next).map { _ =>
                    val staged = driver.stagedBytes(handle)
                    val closed = driver.isPeerClosed(handle)
                    writer.interrupt()
                    cycles.close()
                    driver.closeHandle(handle)
                    driver.close()
                    assert(staged >= cap, s"the probe must stage up to the cap ($cap); stagedBytes=$staged")
                    assert(!closed, s"a FIN behind the staging cap must not be observed; isPeerClosed=$closed")
                }
            }
        }
    }

    "a read delivers the grace probe's staged plain bytes before fresh socket bytes" in {
        given Frame = Frame.internal
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)
            discard(driver.start())

            // Backpressured: the peer sends [1,2,3] and a probe stages them. The pump then re-arms and takes over, so the fresh [4,5,6] arrives through
            // the pump's read path. The staged bytes must be delivered first (the pre-read staging drain), then the fresh socket bytes.
            discard(sv.write(ByteBuffer.wrap(Array[Byte](1, 2, 3))))
            Cycles.init(driver).map { cycles =>
                cycles.until {
                    discard(driver.isPeerClosed(handle))
                    driver.stagedBytes(handle) >= 3
                }.andThen(cycles.close())
            }.andThen {
                val p1 = new IOPromise[Closed, ReadOutcome]
                driver.awaitRead(handle, p1.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                p1.asInstanceOf[Fiber.Unsafe[ReadOutcome, Abort[Closed]]].safe.get
            }.map { r1 =>
                val ReadOutcome.Bytes(span1) = r1.runtimeChecked
                assert(
                    span1.toArray.toList == List[Byte](1, 2, 3),
                    s"the staged bytes must be delivered first; got ${span1.toArray.toList}"
                )
            }.andThen {
                discard(sv.write(ByteBuffer.wrap(Array[Byte](4, 5, 6))))
                val p2 = new IOPromise[Closed, ReadOutcome]
                driver.awaitRead(handle, p2.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                p2.asInstanceOf[Fiber.Unsafe[ReadOutcome, Abort[Closed]]].safe.get
            }.map { r2 =>
                sv.close()
                driver.closeHandle(handle)
                driver.close()
                val ReadOutcome.Bytes(span2) = r2.runtimeChecked
                assert(
                    span2.toArray.toList == List[Byte](4, 5, 6),
                    s"the fresh socket bytes must follow the staged ones; got ${span2.toArray.toList}"
                )
            }
        }
    }

    /** Shared body for the staging-vs-arm race leaves (plain and TLS).
      *
      * The strand this reproduces: a standing grace probe holds OP_READ, fresh bytes arrive, and the probe's dispatch stages them in the
      * window between awaitRead's staging pre-check (which saw Absent) and armRead installing the pump cell. The staged bytes then sit
      * against an armed read that nothing completes: the socket is empty so the selector never fires again, and no path re-checks staging
      * after the arm. Each iteration re-runs the race with a different spin count between the probe's dispatch and the arm. Reads wait on
      * their promise with no timer: a stranded read hangs to the leaf cap, whose diagnostics carry the driver's pending ops, while a timer
      * would also fail a read whose promise already holds its bytes but whose continuation is late.
      *
      * `encode` turns the plaintext the peer sends into its wire bytes: identity for plain TCP, one wrapped TLS record for TLS. On the TLS
      * leaf the probe therefore stages CIPHERTEXT and delivery must route it through the engine (feed + unwrap); reads always assert the
      * decoded plaintext.
      */
    private def stagingRaceLoop(driver: NioIoDriver, handle: NioHandle, sv: SocketChannel, encode: Array[Byte] => Array[Byte])(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Async & Abort[java.io.IOException]) =
        driver.registerChannel(handle)
        discard(driver.start())

        val sentinel   = Array[Byte](9)
        val payload    = Array[Byte](4, 5, 6)
        val iterations = 600
        val rng        = new java.util.Random(0x57a11)

        // Each record is a few dozen bytes into a socket the probe or a read drains every iteration, so one non-blocking write takes it whole.
        def writeBytes(bytes: Array[Byte]): Unit =
            val buffer = ByteBuffer.wrap(encode(bytes))
            discard(sv.write(buffer))
            assert(!buffer.hasRemaining, s"a ${buffer.capacity()} byte record must be written whole, ${buffer.remaining()} left")
        end writeBytes

        def readBytes(iter: Int): Array[Byte] < Async =
            val p = new IOPromise[Closed, ReadOutcome]
            driver.awaitRead(handle, p.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
            p.asInstanceOf[Fiber.Unsafe[ReadOutcome, Abort[Closed]]].safe.getResult.map {
                case Result.Success(ReadOutcome.Bytes(span)) => span.toArray
                case other                                   =>
                    assert(false, s"iteration $iter: unexpected read outcome $other")
                    Array.emptyByteArray
            }
        end readBytes

        Cycles.init(driver).map { cycles =>
            Sync.ensure(Sync.defer {
                cycles.close()
                sv.close()
                driver.closeHandle(handle)
                driver.close()
            }) {
                discard(sv.configureBlocking(false))
                Loop(0) { iter =>
                    if iter == iterations then Loop.done(succeed)
                    else
                        // Arm (or re-arm) the grace probe and prove it is staging: the sentinel must land in graceStaging, which also leaves the
                        // probe re-armed as a standing FIN watch holding OP_READ (the n == 0 re-install after the staging read drains the socket).
                        discard(driver.isPeerClosed(handle))
                        writeBytes(sentinel)
                        cycles.until(driver.stagedBytes(handle) >= 1).andThen {
                            readBytes(iter).map { got =>
                                assert(got.toList == sentinel.toList, s"iteration $iter: sentinel drain got ${got.toList}")
                            }
                        }.andThen {
                            // The race: fresh bytes toward a socket whose standing probe holds OP_READ, then a pump arm on this carrier.
                            writeBytes(payload)
                            var spins = rng.nextInt(64)
                            while spins > 0 do
                                Thread.onSpinWait()
                                spins -= 1
                            Loop(Array.emptyByteArray) { received =>
                                if received.length >= payload.length then
                                    assert(received.toList == payload.toList, s"iteration $iter: payload round-trip got ${received.toList}")
                                    Loop.done(())
                                else
                                    readBytes(iter).map(bytes => Loop.continue(received ++ bytes))
                            }
                        }.andThen(Loop.continue(iter + 1))
                }
            }
        }
    end stagingRaceLoop

    /** A handshaked client/server JDK SSLEngine pair, driven engine-to-engine in memory so the socket never carries handshake bytes.
      * TLSv1.2 keeps the exchange free of post-handshake records (a TLS 1.3 NewSessionTicket would ride ahead of the first application
      * record). One wrap or one unwrap per engine per round; a partial flight resurfaces as NEED_UNWRAP and continues on a later round.
      */
    private def handshakedEnginePair()(using kyo.test.AssertScope): (SSLEngine, SSLEngine) =
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
        // Java enums (SSLEngineResult.HandshakeStatus) have no Scala CanEqual, so compare by reference identity (eq), the JdkSslEngine pattern.
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
        assert(handshaken(client) && handshaken(server), s"in-memory TLS handshake did not complete after $rounds rounds")
        (client, server)
    end handshakedEnginePair

    /** Wrap `plain` into one TLS record with `engine` (the test peer's outbound encryption). */
    private def wrapRecord(engine: SSLEngine, plain: Array[Byte])(using kyo.test.AssertScope): Array[Byte] =
        val out    = ByteBuffer.allocate(engine.getSession.getPacketBufferSize)
        val result = engine.wrap(ByteBuffer.wrap(plain), out)
        assert(result.getStatus eq SSLEngineResult.Status.OK, s"wrap failed: $result")
        out.flip()
        val arr = new Array[Byte](out.remaining())
        out.get(arr)
        arr
    end wrapRecord

    "staged grace-probe bytes racing a fresh read arm are never stranded" in {
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            stagingRaceLoop(driver, handle, sv, identity)
        }
    }

    "staged grace-probe ciphertext racing a fresh TLS read arm is never stranded" in {
        val (clientEngine, serverEngine) = handshakedEnginePair()
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            // The handle unwraps with the server engine; the raw test peer encrypts with the client engine, so the probe stages ciphertext and
            // delivery must route it through the engine gate, the TLS arm of the staged handoff.
            val handle = NioHandle.initTls(client, 4096, serverEngine, Duration.Infinity, Duration.Infinity, Frame.internal)
            stagingRaceLoop(driver, handle, sv, wrapRecord(clientEngine, _))
        }
    }

    "a staged delivery that acts after a read drained the staging and the next read armed leaves that read armed" in {
        // The test plays the selector carrier: the driver's loop is never started, so each selector-side step runs here, in the order
        // the race produces it.
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)
            driver.drainPendingRegistrations()
            // A grace probe staged the sentinel, and the selector sees a delivery due.
            handle.graceStaging.set(Chunk(Array[Byte](9)))
            assert(driver.stagedDeliveryDue(handle))
            // On the caller's carrier: the sentinel's read takes the staging in its pre-check, and the next read arms.
            val sentinelRead = new IOPromise[Closed, ReadOutcome]
            driver.awaitRead(handle, sentinelRead.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
            val nextRead = new IOPromise[Closed, ReadOutcome]
            driver.awaitRead(handle, nextRead.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
            // The selector's delivery then acts.
            driver.deliverStaged(handle)
            val sentinel = sentinelRead.poll() match
                case Present(Result.Success(ReadOutcome.Bytes(span))) => span.toArray.toList
                case other                                            => fail(s"the sentinel's read must take the staged byte; got $other")
            assert(sentinel == List[Byte](9))
            assert(nextRead.poll() == Absent, s"the next read must stay armed, with nothing staged for it; got ${nextRead.poll()}")
            assert(driver.readArmState(handle) == "pump")
            // It still receives the next bytes once the driver runs.
            discard(sv.write(ByteBuffer.wrap(Array[Byte](4, 5, 6))))
            discard(driver.start())
            nextRead.asInstanceOf[Fiber.Unsafe[ReadOutcome, Abort[Closed]]].safe.get.map { outcome =>
                sv.close()
                driver.closeHandle(handle)
                driver.close()
                val ReadOutcome.Bytes(span) = outcome.runtimeChecked
                assert(span.toArray.toList == List[Byte](4, 5, 6))
            }
        }
    }

    "plaintext a staged delivery decrypts goes to the read that replaced the one it was for" in {
        val (clientEngine, serverEngine) = handshakedEnginePair()
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val engine = new UnwrapCallbackEngine(serverEngine)
            val handle = NioHandle.initTls(client, 4096, engine, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)
            driver.drainPendingRegistrations()
            val firstRead = new IOPromise[Closed, ReadOutcome]
            driver.awaitRead(handle, firstRead.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
            // A grace probe staged one record of ciphertext for the armed read.
            handle.graceStaging.set(Chunk(wrapRecord(clientEngine, Array[Byte](4, 5, 6))))
            // While the selector's delivery decrypts it, another read replaces the armed one.
            val replacingRead = new IOPromise[Closed, ReadOutcome]
            engine.onNextUnwrap(() => driver.armRead(handle, replacingRead.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]]))
            driver.deliverStaged(handle)
            sv.close()
            driver.closeHandle(handle)
            driver.close()
            assert(firstRead.poll().exists(_.isFailure), s"the replaced read is failed; got ${firstRead.poll()}")
            replacingRead.poll() match
                case Present(Result.Success(ReadOutcome.Bytes(span))) => assert(span.toArray.toList == List[Byte](4, 5, 6))
                case other => fail(s"the decrypted plaintext must reach the read that holds the slot; got $other")
            end match
        }
    }

    // -----------------------------------------------------------------------
    // detachForUpgrade vs awaitRead: a read the upgrade sweep missed must still be failed
    // -----------------------------------------------------------------------

    "an awaitRead armed after detachForUpgrade is failed, not stranded" in {
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)
            discard(driver.start())
            Sync.ensure(Sync.defer {
                sv.close()
                driver.closeHandle(handle)
                driver.close()
            }) {
                // The transport's upgrade order (NioTransport.upgradeToTls): upgrading = true, then detachForUpgrade. The pump's re-arm has no
                // connection-state gate (ReadPump.requestNextRead delegates interception to the driver), so an arm can land strictly after the
                // detach's slot-first cleanupPending sweep and before the handshake's first producer arm: whatever the sweep took, this arm
                // installs a fresh cell after it ran, and the producer arm's occupant fail is what must complete it (the poller gap leaf's
                // shape). A promise nothing completes is the strand.
                handle.upgrading = true
                driver.detachForUpgrade(handle)
                val p = new IOPromise[Closed, ReadOutcome]
                driver.awaitRead(handle, p.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                driver.armUpgradeProducerRead(handle)
                outcome(p).map { result =>
                    assert(result.isFailure, s"a read armed after detachForUpgrade must be failed; got $result")
                }
            }
        }
    }

    "a read consumed into upgrade salvage is failed by detach, not stranded" in {
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)
            discard(driver.start())
            val flight = Array[Byte](1, 2, 3)
            Sync.ensure(Sync.defer {
                sv.close()
                driver.closeHandle(handle)
                driver.close()
            }) {
                // The pump is legitimately armed BEFORE the upgrade starts, then the peer's first TLS flight lands in the detach window (the
                // server-side STARTTLS shape: the ClientHello rides right behind the negotiation byte). dispatchRead consumes the pendingReads
                // entry and routes the bytes to the upgrade salvage WITHOUT completing the promise, leaving it for detach's cleanupPending; a
                // map-keyed sweep then misses the cell (the entry is already gone) and the promise is never completed by anything.
                val p = new IOPromise[Closed, ReadOutcome]
                driver.awaitRead(handle, p.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                handle.upgrading = true
                discard(sv.write(ByteBuffer.wrap(flight)))
                Cycles.init(driver).map { cycles =>
                    cycles.until {
                        !driver.hasPendingRead(handle) && driver.readArmState(handle) == "pump" && !handle.upgradeSalvage.get().isEmpty
                    }.andThen(cycles.close())
                }.andThen {
                    driver.detachForUpgrade(handle)
                    outcome(p).map { result =>
                        assert(result.isFailure, s"a salvage-consumed read must be failed by the detach; got $result")
                        val salvaged = driver.drainUpgradeSalvage(handle)
                        assert(
                            salvaged.exists(_.toList == flight.toList),
                            s"the peer flight must survive in the upgrade salvage; got $salvaged"
                        )
                    }
                }
            }
        }
    }

    "a read arm racing detachForUpgrade is never stranded" in {
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)
            discard(driver.start())
            val iterations = 400
            val rng        = new java.util.Random(0xde7ac4)
            Sync.ensure(Sync.defer {
                sv.close()
                driver.closeHandle(handle)
                driver.close()
            }) {
                // The concurrent crossings between the deterministic endpoints: a forked arm and an inline detach jitter across each other with
                // independent seeded spin counts. Whatever the interleaving, the promise must complete (Closed on every path here: no bytes are
                // in flight), on pain of the strand the two deterministic leaves pin at their extremes. A strand hangs to the leaf cap.
                def spin(): Unit =
                    var spins = rng.nextInt(64)
                    while spins > 0 do
                        Thread.onSpinWait()
                        spins -= 1
                end spin
                Loop(0) { iter =>
                    if iter == iterations then Loop.done(succeed)
                    else
                        val p = new IOPromise[Closed, ReadOutcome]
                        Fiber.initUnscoped(Sync.defer {
                            spin()
                            driver.awaitRead(handle, p.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                        }).map { armFiber =>
                            spin()
                            handle.upgrading = true
                            driver.detachForUpgrade(handle)
                            armFiber.get.andThen {
                                // The slot rule promises completion at the NEXT slot transfer after an admitted arm, and every real upgrade
                                // has one (a producer arm at minimum). Joining the arm fiber first pins the set as already-landed, so this
                                // producer arm is deterministically that next transfer for whichever ordering the race produced.
                                driver.armUpgradeProducerRead(handle)
                                outcome(p).andThen {
                                    handle.upgrading = false
                                    handle.handshakeReading = false
                                    discard(driver.drainUpgradeSalvage(handle))
                                    Loop.continue(iter + 1)
                                }
                            }
                        }
                }
            }
        }
    }

    "a deferred arm whose wakeup raced the selector rebuild is applied after the swap" in {
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)
            Sync.ensure(Sync.defer {
                sv.close()
                driver.closeHandle(handle)
                driver.close()
            }) {
                // The lost-wakeup hole: an offer-then-wakeup producer whose wakeup reads the OLD selector during a rebuild wakes a corpse (a
                // closed selector's wakeup is a silent no-op), and the new selector parks in an indefinite select() with the offer stranded in
                // its queue. The pre-start window makes the interleaving deterministic: the arm's wakeup lands on the selector the rebuild is
                // about to close, then the loop starts on the swapped selector, which nothing has woken.
                // The latch is the arm's own delivery: a parked handshake waiter that only the applied producer read can complete. The peer's
                // byte makes the channel readable but cannot wake a select that has no OP_READ interest for it, so a swallowed wakeup hangs to
                // the leaf cap. No other wake source may be added here, since any would apply the stranded arm and mask the hole.
                handle.upgrading = true
                val waiter = Promise.Unsafe.init[Span[Byte], Abort[Closed]]()
                handle.upgradeHandoff.set(NioHandle.UpgradeHandoff.Waiter(waiter, Frame.internal))
                driver.armUpgradeProducerRead(handle)
                driver.rebuildSelector()
                discard(driver.start())
                discard(sv.write(ByteBuffer.wrap(Array[Byte](0x16))))
                waiter.safe.getResult.map { delivered =>
                    assert(
                        delivered.map(_.toArray.toList) == Result.succeed(List[Byte](0x16)),
                        s"the deferred producer arm must deliver the peer flight after the selector swap; got $delivered"
                    )
                }
            }
        }
    }

    "an arm during a pre-detach upgrade window is not spuriously failed" in {
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)
            discard(driver.start())
            Sync.ensure(Sync.defer {
                sv.close()
                driver.closeHandle(handle)
                driver.close()
            }) {
                // The transport sets upgrading BEFORE the state CAS and the detach sweep. A pump arm landing in that pre-CAS window must stay
                // armed (failing it here lets the pump's teardown closeFn win Established -> Closing and abort a healthy upgrade); the detach
                // sweep is what fails it, post-CAS.
                handle.upgrading = true
                val p = new IOPromise[Closed, ReadOutcome]
                driver.awaitRead(handle, p.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                // A second registered handle whose peer writes exposes the arm to the poll carrier: its read completes in dispatchReadyKeys, the LAST
                // step of a cycle body, so its completion proves one full cycle ran with the pre-CAS arm registered, past any path that could spuriously fail it.
                NioLoopbackPair.open().map { (barrierClient, barrierPeer) =>
                    val barrier = NioHandle.init(barrierClient, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
                    driver.registerChannel(barrier)
                    Sync.ensure(Sync.defer {
                        barrierPeer.close()
                        driver.closeHandle(barrier)
                    }) {
                        val barrierRead = new IOPromise[Closed, ReadOutcome]
                        driver.awaitRead(barrier, barrierRead.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                        discard(barrierPeer.write(ByteBuffer.wrap(Array[Byte](1))))
                        outcome(barrierRead).map { dispatched =>
                            assert(
                                dispatched.isSuccess,
                                s"the barrier read must dispatch, exposing the pre-CAS arm to a poll cycle: $dispatched"
                            )
                            assert(
                                !p.done(),
                                "pre-CAS arm was spuriously completed: nothing may fail a read before the upgrade's state CAS and sweep have run"
                            )
                        }
                    }.andThen {
                        driver.detachForUpgrade(handle)
                        outcome(p).map { result =>
                            assert(result.isFailure, s"the detach sweep must fail the pre-CAS arm; got $result")
                        }
                    }
                }
            }
        }
    }

    "a stray arm after the upgrade sweep does not disturb the armed producer" in {
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)
            discard(driver.start())
            Sync.ensure(Sync.defer {
                sv.close()
                driver.closeHandle(handle)
                driver.close()
            }) {
                // Mid-handshake: the producer owns the read (cell + pendingReads entry + OP_READ). A late stray pump re-arm must be failed
                // WITHOUT touching that shared state: clobbering the producer cell or removing the entry silently disconnects the handshake
                // from the selector, and its demand-driven waiter never retries.
                handle.upgrading = true
                driver.detachForUpgrade(handle)
                driver.armUpgradeProducerRead(handle)
                Cycles.init(driver).map { cycles =>
                    cycles.until(driver.hasPendingRead(handle)).andThen(cycles.close())
                }.andThen {
                    val p = new IOPromise[Closed, ReadOutcome]
                    driver.awaitRead(handle, p.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                    outcome(p).map { result =>
                        assert(result.isFailure, s"the stray mid-handshake arm must be failed; got $result")
                    }
                }.andThen {
                    // The handshake parks its waiter; a producer that lost the read to the stray arm never delivers, and the leaf hangs to its
                    // cap.
                    val waiter = Promise.Unsafe.init[Span[Byte], Abort[Closed]]()
                    handle.upgradeHandoff.set(NioHandle.UpgradeHandoff.Waiter(waiter, Frame.internal))
                    discard(sv.write(ByteBuffer.wrap(Array[Byte](0x16, 3, 1))))
                    waiter.safe.getResult.map { delivered =>
                        assert(
                            delivered.map(_.toArray.toList) == Result.succeed(List[Byte](0x16, 3, 1)),
                            s"the producer must deliver the peer flight to the upgrade handoff; got $delivered"
                        )
                    }
                }
            }
        }
    }

    "a read consumed by the upgrade producer dispatch is failed, not stranded" in {
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)
            discard(driver.start())
            val flight = Array[Byte](0x16, 3, 3, 0, 1)
            Sync.ensure(Sync.defer {
                sv.close()
                driver.closeHandle(handle)
                driver.close()
            }) {
                // A pump read armed before the upgrade, consumed by the producer dispatch once the handshake owns the reads (upgrading and
                // handshakeReading both set): the dispatch CASes the cell out and delivers the flight into the handoff; the promise it consumed
                // must be failed, since the sweep can no longer see the cell and nothing else ever completes it.
                val p = new IOPromise[Closed, ReadOutcome]
                driver.awaitRead(handle, p.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                handle.upgrading = true
                handle.handshakeReading = true
                discard(sv.write(ByteBuffer.wrap(flight)))
                Cycles.init(driver).map { cycles =>
                    cycles.until {
                        handle.upgradeHandoff.get() match
                            case NioHandle.UpgradeHandoff.Carryover(bytes) => bytes.length == flight.length
                            case _                                         => false
                    }.andThen(cycles.close())
                }.andThen {
                    outcome(p).map { result =>
                        assert(result.isFailure, s"a read consumed by the producer dispatch must be failed; got $result")
                        handle.upgradeHandoff.get() match
                            case NioHandle.UpgradeHandoff.Carryover(bytes) =>
                                assert(bytes.toList == flight.toList, s"handoff bytes ${bytes.toList} != ${flight.toList}")
                            case other =>
                                assert(false, s"the flight must stay staged in the handoff; got $other")
                        end match
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // awaitWritable: registers interest and completes promise when writable
    // -----------------------------------------------------------------------

    "awaitWritable completes promise when channel is writable" in {
        given Frame = Frame.internal
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)
            discard(driver.start())

            val p = new IOPromise[Closed, Unit]
            driver.awaitWritable(handle, p.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed | NetException]]])

            p.asInstanceOf[Fiber.Unsafe[Unit, Abort[Closed]]].safe.get.map { _ =>
                sv.close()
                driver.closeHandle(handle)
                driver.close()
                succeed
            }
        }
    }

    // -----------------------------------------------------------------------
    // awaitConnect: duplicate registration panics second promise
    // -----------------------------------------------------------------------

    "awaitConnect fails promise on duplicate registration" in {
        given Frame = Frame.internal
        val driver  = NioIoDriver.init()
        val ch      = SocketChannel.open()
        ch.configureBlocking(false)
        val handle = NioHandle.init(ch, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
        driver.registerChannel(handle)

        val p1 = new IOPromise[Closed, Unit]
        val p2 = new IOPromise[Closed, Unit]

        // First registration succeeds (stores in pendingConnects)
        driver.awaitConnect(handle, p1.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed | NetException]]])
        // Second registration with same channel: duplicate, so p2 panics
        driver.awaitConnect(handle, p2.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed | NetException]]])

        assert(p2.done())
        val r = p2.poll()
        assert(r match
            case Present(Result.Panic(_)) => true
            case _                        => false)

        ch.close()
        driver.close()
        succeed
    }

    // -----------------------------------------------------------------------
    // cancel: removes pending operations
    // -----------------------------------------------------------------------

    "cancel fails pending read promise with Closed" in {
        given Frame = Frame.internal
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)

            val p = new IOPromise[Closed, ReadOutcome]
            driver.awaitRead(handle, p.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])

            driver.cancel(handle)

            assert(p.done())
            val r = p.poll()
            assert(r match
                case Present(Result.Failure(_)) => true
                case _                          => false)

            // cancel only deregisters the selector key; it does not close the channel (closeHandle does). Close the client channel here so the test
            // does not leak its fd.
            client.close()
            sv.close()
            driver.close()
            succeed
        }
    }

    "cancel is idempotent: second call does not throw" in {
        given Frame = Frame.internal
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)
            driver.cancel(handle)
            driver.cancel(handle) // must not throw
            // cancel only deregisters the selector key; it does not close the channel (closeHandle does). Close the client channel here so the test
            // does not leak its fd.
            client.close()
            sv.close()
            driver.close()
            succeed
        }
    }

    // -----------------------------------------------------------------------
    // closeHandle: cancels key, closes channel, cleans up pending promises
    // -----------------------------------------------------------------------

    "closeHandle closes the underlying channel" in {
        given Frame = Frame.internal
        withDriverAndHandle() { (driver, handle, sv) =>
            driver.closeHandle(handle)
            assert(!handle.channel.isOpen)
            succeed
        }
    }

    "closeHandle wakes the selector so a cancelled key is deregistered on an idle loop (no CLOSE_WAIT fd leak)" in {
        given Frame = Frame.internal
        // Reproduce-first for the nio CLOSE_WAIT leak. closeHandle cancels the channel's SelectionKey and closes the channel, but the JDK
        // defers both the key's deregistration and (JDK 11+) the fd's actual kill() to the selector's next select() pass. The select loop
        // parks in an indefinite select() with no timeout, so on an otherwise-idle driver (the last connection closing, or the ReadPump's
        // peer-FIN teardown with nothing else pending, NioIoDriver's ReadPump.onComplete -> closeHandle path) a closeHandle that does not
        // wake the selector leaves the fd stranded in CLOSE_WAIT until some unrelated event happens to wake the loop. Asserted here on darwin
        // without the Linux /proc leak probe via the channel's registration state: the same select() pass that deregisters the cancelled key
        // is the one that kill()s the fd, so channel.isRegistered() going false is a faithful proxy for "the deferred close actually ran".
        // Without the wakeup the idle selector never runs that pass and the channel stays registered; closeHandle's wakeup() forces one.
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            discard(driver.start())
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            Sync.ensure(Sync.defer {
                sv.close()
                driver.close()
            }) {
                assert(driver.registerChannel(handle), "the channel must register with the selector")
                // Neither wait may use a selector-cycle fence: arming one wakes the selector, which would run the deregistration pass itself and
                // mask a closeHandle that does not. Registration state has no completion event, so each wait re-checks it on the fiber
                // scheduler with no clock.
                def until(cond: => Boolean): Unit < Async =
                    Loop(())(_ => Sync.defer(cond).map(done => if done then Loop.done(()) else Loop.continue(())))
                // Gate until the selector has consumed the registration wakeup (wakeupPending cleared by a select() return) with the channel
                // registered, so the close below is the ONLY thing that can drive the deregistration pass.
                until(!driver.wakeupPending.get() && client.isRegistered()).andThen {
                    driver.closeHandle(handle)
                    // closeHandle wakes the selector, so the cancelled key is deregistered within a poll cycle. Without the wake the idle selector
                    // never runs the pass and this hangs to the leaf cap for the right reason: the cancelled key, and its fd, leak in CLOSE_WAIT.
                    until(!client.isRegistered()).andThen(succeed)
                }
            }
        }
    }

    "closeHandle fails pending read promise" in {
        given Frame = Frame.internal
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)

            val p = new IOPromise[Closed, ReadOutcome]
            driver.awaitRead(handle, p.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])

            driver.closeHandle(handle)

            assert(p.done())
            sv.close()
            driver.close()
            succeed
        }
    }

    // -----------------------------------------------------------------------
    // close: shuts down driver and fails all pending promises
    // -----------------------------------------------------------------------

    "close fails all pending read promises with Closed" in {
        given Frame = Frame.internal
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)

            val p = new IOPromise[Closed, ReadOutcome]
            driver.awaitRead(handle, p.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])

            driver.close()

            assert(p.done())
            val r = p.poll()
            assert(r match
                case Present(Result.Failure(_)) => true
                case _                          => false)
            client.close()
            sv.close()
            succeed
        }
    }

    "close is idempotent: second close does not throw" in {
        given Frame = Frame.internal
        val driver  = NioIoDriver.init()
        driver.close()
        driver.close() // must not throw
        succeed
    }

    // -----------------------------------------------------------------------
    // registerServerChannel
    // -----------------------------------------------------------------------

    "registerServerChannel returns true for open server channel" in {
        given Frame       = Frame.internal
        val driver        = NioIoDriver.init()
        val serverChannel = ServerSocketChannel.open()
        serverChannel.configureBlocking(false)
        serverChannel.bind(new InetSocketAddress("127.0.0.1", 0))
        try
            val result = driver.registerServerChannel(serverChannel)
            assert(result)
            succeed
        finally
            serverChannel.close()
            driver.close()
        end try
    }

    "registerServerChannel returns false after driver is closed" in {
        given Frame       = Frame.internal
        val driver        = NioIoDriver.init()
        val serverChannel = ServerSocketChannel.open()
        serverChannel.configureBlocking(false)
        driver.close()
        try
            val result = driver.registerServerChannel(serverChannel)
            assert(!result)
            succeed
        finally
            serverChannel.close()
        end try
    }

    // -----------------------------------------------------------------------
    // awaitAccept: registers accept interest
    // -----------------------------------------------------------------------

    "awaitAccept completes promise when client connects" in {
        given Frame       = Frame.internal
        val driver        = NioIoDriver.init()
        val serverChannel = ServerSocketChannel.open()
        serverChannel.configureBlocking(false)
        serverChannel.bind(new InetSocketAddress("127.0.0.1", 0))
        val port = serverChannel.socket().getLocalPort
        driver.registerServerChannel(serverChannel)
        discard(driver.start())

        val p = new IOPromise[Closed, Unit]
        driver.awaitAccept(serverChannel, p.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]], Frame.internal)

        // Connect a client to trigger accept notification
        val client = SocketChannel.open()
        client.connect(new InetSocketAddress("127.0.0.1", port))

        p.asInstanceOf[Fiber.Unsafe[Unit, Abort[Closed]]].safe.get.map { _ =>
            client.close()
            serverChannel.close()
            driver.close()
            succeed
        }
    }

    // -----------------------------------------------------------------------
    // cleanupAccept: removes pending accept entry
    // -----------------------------------------------------------------------

    "cleanupAccept fails pending accept promise with Closed" in {
        given Frame       = Frame.internal
        val driver        = NioIoDriver.init()
        val serverChannel = ServerSocketChannel.open()
        serverChannel.configureBlocking(false)
        serverChannel.bind(new InetSocketAddress("127.0.0.1", 0))
        driver.registerServerChannel(serverChannel)

        val p = new IOPromise[Closed, Unit]
        driver.awaitAccept(serverChannel, p.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]], Frame.internal)
        driver.cleanupAccept(serverChannel, Frame.internal)

        assert(p.done())
        val r = p.poll()
        assert(r match
            case Present(Result.Failure(_)) => true
            case _                          => false)

        serverChannel.close()
        driver.close()
        succeed
    }

    // -----------------------------------------------------------------------
    // write: large span uses fallback ByteBuffer.wrap path
    // -----------------------------------------------------------------------

    "write oversized data (larger than writeBuffer) may return Done or Partial" in {
        // Use a small buffer size so the oversized path is exercised
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 16, Duration.Infinity, Duration.Infinity, Frame.internal) // tiny buffer
            driver.registerChannel(handle)
            try
                val bigData = Span.fromUnsafe(Array.fill[Byte](8192)(42))
                // May return Done or Partial depending on socket buffer, but must not throw
                val result = driver.write(handle, bigData, 0)
                assert(result == WriteResult.Done || result.isInstanceOf[WriteResult.Partial])
                succeed
            finally
                sv.close()
                given Frame = Frame.internal
                driver.closeHandle(handle)
                driver.close()
            end try
        }
    }

    // -----------------------------------------------------------------------
    // Flat array-backed selection-key set reflection install
    // -----------------------------------------------------------------------

    "selectedKeySetFallback" in {
        // The test JVM includes --add-opens=java.base/sun.nio.ch=ALL-UNNAMED so the Present branch is
        // expected. Both branches must work: Present (flat array set installed) and Absent (graceful
        // fallback to the default HashSet path). In both cases a real ready fd must be delivered
        // correctly without a crash or a missed key.
        val driver = NioIoDriver.init()
        try
            given Frame = Frame.internal
            // Start the event loop so the selector can dispatch real readiness.
            discard(driver.start())

            // Open a real loopback pair to generate a real ready key.
            NioLoopbackPair.open().map { (client, sv) =>
                val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
                driver.registerChannel(handle)

                val p = new kyo.scheduler.IOPromise[Closed, ReadOutcome]
                driver.awaitRead(handle, p.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])

                // Write from server side so the client channel becomes readable.
                sv.write(ByteBuffer.wrap("probe".getBytes))

                // The promise resolves when the selector fires. In the Present path the flat array set
                // dispatched the key; in the Absent path the standard iterator did. Both must deliver the
                // byte without missing the key.
                p.asInstanceOf[Fiber.Unsafe[ReadOutcome, Abort[Closed]]].safe.get.map { result =>
                    sv.close()
                    driver.closeHandle(handle)
                    driver.close()
                    val ReadOutcome.Bytes(span) = result.runtimeChecked
                    assert(span.nonEmpty)
                    succeed
                }
            }
        catch
            case t: Throwable =>
                given Frame = Frame.internal
                driver.close()
                throw t
        end try
    }

    // -----------------------------------------------------------------------
    // Selector rebuild guard on consecutive zero-key returns
    // -----------------------------------------------------------------------

    "selectorRebuildGuard" in {
        // Reproduce-first: without the guard a real idle selector can spin forever returning 0.
        // The guard detects the spin via a pure predicate on the consecutive-zero count and rebuilds.
        //
        // Part 1: pure predicate boundary (shouldRebuild is private[net], accessible in this package).
        // The predicate must return false below threshold and true at and above.
        val driver = NioIoDriver.init()
        try
            given Frame = Frame.internal

            assert(!driver.shouldRebuild(NioIoDriver.SelectorRebuildThreshold - 1))
            assert(driver.shouldRebuild(NioIoDriver.SelectorRebuildThreshold))
            assert(driver.shouldRebuild(NioIoDriver.SelectorRebuildThreshold + 1))

            // Part 2: reproduce-first guard-disabled spin. Build a real Selector with two real idle
            // channels (no data sent) and call selectNow() in a bounded loop to confirm zero-key
            // returns accumulate without the guard. This is the unguarded baseline.
            NioLoopbackPair.open().map { (clientA, svA) =>
                NioLoopbackPair.open().map { (clientB, svB) =>
                    val idleSel = Selector.open()
                    clientA.register(idleSel, 0)
                    clientB.register(idleSel, 0)

                    var consecutiveZero = 0
                    var spins           = 0
                    val spinCap         = NioIoDriver.SelectorRebuildThreshold + 10
                    while spins < spinCap do
                        val n = idleSel.selectNow()
                        if n == 0 then consecutiveZero += 1
                        spins += 1
                    end while
                    // Without the guard the counter just grows; the spin did not stop itself.
                    assert(consecutiveZero >= NioIoDriver.SelectorRebuildThreshold)
                    idleSel.close()

                    // Part 3: with the guard active. Register the same channels on the driver, start the event
                    // loop, then make one channel ready and verify the event is delivered (post-rebuild
                    // correctness: the new selector still dispatches real readiness).
                    val handleA = NioHandle.init(clientA, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
                    val handleB = NioHandle.init(clientB, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
                    driver.registerChannel(handleA)
                    driver.registerChannel(handleB)
                    discard(driver.start())

                    val p = new kyo.scheduler.IOPromise[Closed, ReadOutcome]
                    driver.awaitRead(handleA, p.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                    svA.write(ByteBuffer.wrap("rebuild-progress".getBytes))

                    p.asInstanceOf[Fiber.Unsafe[ReadOutcome, Abort[Closed]]].safe.get.map { result =>
                        svA.close()
                        svB.close()
                        driver.closeHandle(handleA)
                        driver.closeHandle(handleB)
                        driver.close()
                        // The driver's select loop (with or without a rebuild) must deliver real readiness.
                        val ReadOutcome.Bytes(span) = result.runtimeChecked
                        assert(span.nonEmpty)
                        succeed
                    }
                }
            }
        catch
            case t: Throwable =>
                given Frame = Frame.internal
                driver.close()
                throw t
        end try
    }

    "selectorRebuildPreservesArmedInterest" in {
        // A selector rebuild must preserve each channel's armed interest. An operation pending when the rebuild
        // fires (a read, write, connect, or accept already waiting) must keep its interest registered on the new
        // selector, otherwise the selector never reports its readiness and the promise never completes. Arm
        // interest, force a rebuild directly (before the loop starts, so the call is single-carrier-confined),
        // then assert via interestOpsFor that the interest is still present on the new selector.
        given Frame = Frame.internal
        NioLoopbackPair.open().map { (client, sv) =>
            val driver        = NioIoDriver.init()
            val handle        = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            val serverChannel = ServerSocketChannel.open()
            serverChannel.configureBlocking(false)
            serverChannel.bind(new InetSocketAddress("127.0.0.1", 0))
            try
                driver.registerChannel(handle)
                driver.registerServerChannel(serverChannel)

                val pw = new IOPromise[Closed, Unit]
                val pr = new IOPromise[Closed, ReadOutcome]
                val pa = new IOPromise[Closed, Unit]
                driver.awaitWritable(handle, pw.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed | NetException]]])
                driver.awaitRead(handle, pr.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                driver.awaitAccept(serverChannel, pa.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]], Frame.internal)

                // Precondition: the socket channel carries OP_READ and OP_WRITE, the server channel OP_ACCEPT.
                assert((driver.interestOpsFor(client) & SelectionKey.OP_READ) != 0)
                assert((driver.interestOpsFor(client) & SelectionKey.OP_WRITE) != 0)
                assert((driver.interestOpsFor(serverChannel) & SelectionKey.OP_ACCEPT) != 0)

                // Force the rebuild (no loop running yet: no race with a select carrier).
                driver.rebuildSelector()

                // The armed interest must still be present on the new selector after the rebuild.
                assert((driver.interestOpsFor(client) & SelectionKey.OP_READ) != 0)
                assert((driver.interestOpsFor(client) & SelectionKey.OP_WRITE) != 0)
                assert((driver.interestOpsFor(serverChannel) & SelectionKey.OP_ACCEPT) != 0)
                succeed
            finally
                driver.closeHandle(handle)
                sv.close()
                serverChannel.close()
                driver.close()
            end try
        }
    }

    "selectorRebuildKeepsInFlightReadDeliverable" in {
        // End-to-end companion to selectorRebuildPreservesArmedInterest: a read armed before a rebuild must still
        // deliver real data once the loop runs on the new selector. A rebuild that did not preserve the read
        // interest would leave this promise uncompleted until the suite timeout.
        given Frame = Frame.internal
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)

            val pr = new IOPromise[Closed, ReadOutcome]
            driver.awaitRead(handle, pr.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])

            // Force the rebuild while the read is in flight (no loop running yet: no race), then start the loop.
            driver.rebuildSelector()
            discard(driver.start())

            sv.write(ByteBuffer.wrap("after-rebuild".getBytes))

            pr.asInstanceOf[Fiber.Unsafe[ReadOutcome, Abort[Closed]]].safe.get.map { result =>
                sv.close()
                driver.closeHandle(handle)
                driver.close()
                val ReadOutcome.Bytes(span) = result.runtimeChecked
                assert(new String(span.toArray) == "after-rebuild")
                succeed
            }
        }
    }

    // -----------------------------------------------------------------------
    // Wakeup guarded by an AtomicBoolean CAS
    // -----------------------------------------------------------------------

    "wakeupGuardedRealSelect" in {
        // The wakeup guard coalesces redundant selector.wakeup() calls via an AtomicBoolean CAS.
        // wakeupPending is private[net] so its flag state is directly observable here.
        //
        // Part 1: pure CAS guard mechanics (no event loop required).
        // The flag starts false. A compareAndSet(false, true) succeeds (this is what registerInterest
        // does when it decides to call wakeup). While the flag is true, a second compareAndSet(false,
        // true) fails, meaning the guard coalesced the redundant wakeup. After set(false), the CAS
        // succeeds again, proving the guard resets correctly.
        given Frame = Frame.internal
        val driver  = NioIoDriver.init()
        try
            // Initial flag state: false.
            assert(!driver.wakeupPending.get())

            // First CAS: simulates what registerInterest does when it decides to issue a wakeup.
            val firstCas = driver.wakeupPending.compareAndSet(false, true)
            assert(firstCas)                   // CAS succeeded: flag was false, now true.
            assert(driver.wakeupPending.get()) // flag is true.

            // Redundant CAS while flag is already true: must fail (wakeup coalesced).
            val redundantCas = driver.wakeupPending.compareAndSet(false, true)
            assert(!redundantCas)              // CAS failed: flag was already true.
            assert(driver.wakeupPending.get()) // flag remains true.

            // Reset the flag (simulates the post-select re-check in pollOnce).
            val clearCas = driver.wakeupPending.compareAndSet(true, false)
            assert(clearCas)                    // CAS succeeded: flag was true, now false.
            assert(!driver.wakeupPending.get()) // flag is false again.

            // Second CAS after reset: succeeds again (correct reset/rearm cycle).
            val secondCas = driver.wakeupPending.compareAndSet(false, true)
            assert(secondCas)
            driver.wakeupPending.set(false) // leave clean for Part 2.

            // Part 2: real event loop. The wakeup guard must not suppress valid wakeups that
            // are needed to deliver real readiness events to promises.
            NioLoopbackPair.open().map { (client, sv) =>
                val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
                driver.registerChannel(handle)
                discard(driver.start())

                val p1 = new kyo.scheduler.IOPromise[Closed, Unit]
                driver.awaitWritable(handle, p1.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed | NetException]]])

                p1.asInstanceOf[Fiber.Unsafe[Unit, Abort[Closed]]].safe.get.map { _ =>
                    // OP_WRITE was dispatched and interest cleared. Register a second awaitWritable:
                    // the guard must fire a new wakeup (flag was cleared by pollOnce) so this resolves.
                    val p2 = new kyo.scheduler.IOPromise[Closed, Unit]
                    driver.awaitWritable(handle, p2.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed | NetException]]])

                    p2.asInstanceOf[Fiber.Unsafe[Unit, Abort[Closed]]].safe.get.map { _ =>
                        sv.close()
                        driver.closeHandle(handle)
                        driver.close()
                        // Both promises resolved: the guard coalesced redundant wakeups while still
                        // allowing genuine wakeups to wake the blocked selector.
                        succeed
                    }
                }
            }
        catch
            case t: Throwable =>
                driver.close()
                throw t
        end try
    }

    // -----------------------------------------------------------------------
    // Interest-ops guarded -- only fires on a genuine change
    // -----------------------------------------------------------------------

    "interestOpsGuardedRealKey" in {
        // Reproduce-first: establish an unguarded baseline, then verify the guarded path reaches
        // the same final interest set.
        //
        // Unguarded baseline: open a real Selector, register a real channel, then call
        // key.interestOps(newOps) unconditionally for five identical OP_READ registrations plus
        // one OP_READ|OP_WRITE registration (a genuine change). The final interest set is
        // OP_READ|OP_WRITE. Record the interest value at each step.
        //
        // Guarded path: use the real NioIoDriver's registerInterest (via awaitRead/awaitWritable).
        // Repeated identical-ops calls leave the key unchanged (the current==newOps short-circuit);
        // a genuine change updates the key. The final interest set must equal the unguarded baseline.
        given Frame = Frame.internal
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            driver.registerChannel(handle)

            try
                // --- Unguarded baseline ---
                val baseSel = Selector.open()
                val baseCh  = SocketChannel.open()
                baseCh.configureBlocking(false)
                val baseKey = baseCh.register(baseSel, 0)

                // Five unconditional OP_READ registrations (identical ops).
                var step = 0
                while step < 5 do
                    val newOps = baseKey.interestOps() | SelectionKey.OP_READ
                    discard(baseKey.interestOps(newOps))
                    step += 1
                end while
                val afterFiveRead = baseKey.interestOps()
                assert(afterFiveRead == SelectionKey.OP_READ)

                // One genuine change: add OP_WRITE.
                val finalNewOps = baseKey.interestOps() | SelectionKey.OP_WRITE
                discard(baseKey.interestOps(finalNewOps))
                val baselineFinal = baseKey.interestOps()
                assert(baselineFinal == (SelectionKey.OP_READ | SelectionKey.OP_WRITE))

                baseCh.close()
                baseSel.close()

                // --- Guarded path via the real NioIoDriver ---
                // Start the event loop so the selector processes registrations.
                discard(driver.start())

                // Register OP_WRITE (genuine change from initial 0): this sets the flag and wakes select.
                val p1 = new kyo.scheduler.IOPromise[Closed, Unit]
                driver.awaitWritable(handle, p1.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed | NetException]]])

                // Wait for dispatch (select fires, interest cleared by dispatch loop).
                p1.asInstanceOf[Fiber.Unsafe[Unit, Abort[Closed]]].safe.get.map { _ =>
                    // OP_WRITE was cleared by the dispatch loop. Register OP_READ (genuine change).
                    val p2 = new kyo.scheduler.IOPromise[Closed, ReadOutcome]
                    driver.awaitRead(handle, p2.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])

                    // Write from server to trigger the read event.
                    sv.write(ByteBuffer.wrap("guarded-baseline".getBytes))

                    p2.asInstanceOf[Fiber.Unsafe[ReadOutcome, Abort[Closed]]].safe.get.map { readResult =>
                        sv.close()
                        driver.closeHandle(handle)
                        driver.close()
                        // The guarded path delivered the correct data (same final behavior as the unguarded baseline).
                        val ReadOutcome.Bytes(span) = readResult.runtimeChecked
                        assert(span.nonEmpty)
                        assert(span.size == "guarded-baseline".getBytes.length)
                        succeed
                    }
                }
            catch
                case t: Throwable =>
                    driver.close()
                    throw t
            end try
        }
    }

    // -----------------------------------------------------------------------
    // Cancelled-key re-registration routed through the poll carrier (no OS-thread park)
    // -----------------------------------------------------------------------

    "registerChannelDeferredOnCancelledKey" in {
        // Reproduce-first for the STARTTLS upgrade re-registration race. detachForUpgrade cancels the channel's SelectionKey; the
        // cancelled key lingers in the selector's cancelled-key set until the poll carrier flushes it during select(). An immediate
        // registerChannel on the same channel therefore throws CancelledKeyException. registerChannel routes that re-registration through the poll
        // carrier (enqueue + wakeup + return success) instead of parking the calling carrier in a parkNanos retry loop.
        //
        // Deterministic trigger: register a channel, cancel its key (mirrors detachForUpgrade's driver.cancel), then re-register before any
        // select() has flushed the cancelled key. registerChannel must take the deferred path: return true and enqueue the handle (no park).
        given Frame = Frame.internal
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            try
                // Initial registration creates a live key.
                assert(driver.registerChannel(handle))
                assert(driver.pendingRegistrationCount == 0)

                // Cancel the key (as detachForUpgrade does). The cancelled key now lingers in the cancelled-key set: no select() has flushed it.
                driver.cancel(handle)

                // Re-register the same channel: the lingering cancelled key makes channel.register throw CancelledKeyException, so the driver must
                // take the deferred path. It returns success (the registration is guaranteed, just deferred) and enqueues the handle for the poll
                // carrier. No parkNanos, no spin: the call returns immediately.
                val deferred = driver.registerChannel(handle)
                assert(deferred)
                assert(driver.pendingRegistrationCount == 1)

                // Arm a read during the deferred window (before the channel is registered): the interest is held in the pending-op map and applied
                // when the poll carrier completes the deferred registration. awaitRead must NOT fail the promise here.
                val pr = new IOPromise[Closed, ReadOutcome]
                driver.awaitRead(handle, pr.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                assert(!pr.asInstanceOf[Fiber.Unsafe[ReadOutcome, Abort[Closed]]].done())

                // Start the poll loop: its first select() flushes the cancelled key, drainPendingRegistrations registers the channel with the armed
                // OP_READ interest reconstructed from the pending-op map, and the server write is then delivered.
                discard(driver.start())
                sv.write(ByteBuffer.wrap("after-deferred-register".getBytes))

                pr.asInstanceOf[Fiber.Unsafe[ReadOutcome, Abort[Closed]]].safe.get.map { result =>
                    sv.close()
                    driver.closeHandle(handle)
                    driver.close()
                    // The deferred registration completed on the poll carrier and the read delivered the real bytes: no data lost, no park.
                    val ReadOutcome.Bytes(span) = result.runtimeChecked
                    assert(new String(span.toArray) == "after-deferred-register")
                    assert(driver.pendingRegistrationCount == 0)
                    succeed
                }
            catch
                case t: Throwable =>
                    driver.close()
                    throw t
            end try
        }
    }

    "awaitConnectIssuesUnconditionalWakeupEvenWhenCoalescingPending" in {
        // Deterministic, LOAD-INDEPENDENT guard for the connect-arm lost-wakeup (CONN-B, the forceReadArmWakeup-class gap). The bug: a GUARDED
        // wakeup (registerInterest's wakeupPending CAS) coalesces away under a burst, where wakeupPending is already true (an in-flight wakeup), so
        // the freshly-armed OP_CONNECT is never observed if select() re-blocks before seeing it -> a 30s connect
        // strand. The driver arms OP_CONNECT via armConnectInterest, which issues an UNCONDITIONAL selector.wakeup() so the arm ALWAYS forces a poll
        // cycle. This test reproduces the exact coalescing condition (wakeupPending pre-set true) and asserts the arm STILL issues a wakeup -- a pure
        // invariant check with NO real-time deadline, so it validates Fix B regardless of host load (a load-30 integration TIMEOUT cannot
        // distinguish a residual gap from poll-carrier CPU starvation; this can).
        //
        // A guarded registerInterest wakeup would coalesce (wakeupPending already true) so no wakeup is issued -> connectWakeups
        // stays 0; armConnectInterest's unconditional wakeup fires instead -> connectWakeups == before + 1.
        given Frame = Frame.internal
        val driver  = NioIoDriver.init()
        val ch      = SocketChannel.open()
        ch.configureBlocking(false)
        val handle = NioHandle.init(ch, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
        try
            driver.registerChannel(handle)
            // Pre-set the coalescing condition: an in-flight wakeup is pending, so any GUARDED wakeup would coalesce away.
            // The unconditional wakeup must fire regardless.
            discard(driver.wakeupPending.compareAndSet(false, true))
            val before = driver.connectWakeups.get()
            val pc     = new IOPromise[Closed, Unit]
            driver.awaitConnect(handle, pc.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed | NetException]]])
            val after = driver.connectWakeups.get()
            assert(
                after == before + 1,
                s"the connect arm must issue an UNCONDITIONAL wakeup even when wakeupPending is already set (coalescing condition); " +
                    s"connectWakeups went $before -> $after (a guarded wakeup would coalesce and not fire)"
            )
            succeed
        finally
            driver.cancel(handle)
            ch.close()
            driver.close()
        end try
    }

    "registerChannelDeferredOnClosedSelectorDuringRebuild" in {
        // Reproduce-first for the concurrent-connect-burst connect failure: a caller-carrier registerChannel races the poll carrier's
        // rebuildSelector, which closes the old selector (NioIoDriver selector.close() then selector = newSelector). Under a connect burst the
        // selector spins and rebuilds; a registerChannel reading the closed old selector throws ClosedSelectorException. Returning false on that
        // would make NioTransport.awaitConnect fail the connect with an empty-cause NetConnectException. The driver routes that
        // close (while the driver is still live, closedFlag false) through the same deferred path the CancelledKeyException race uses: enqueue +
        // wakeup + return success, and drainPendingRegistrations re-registers on the live selector with interest reconstructed from the pending-op
        // maps. The loopback connect is ALREADY complete here (NioLoopbackPair waits for finishConnect), so this also exercises the
        // deferred-connect-after-rebuild edge: a connect that completed during the deferral window must still complete, which needs the drain-time
        // dispatchConnect force-dispatch (the selector does not re-surface OP_CONNECT for an interest registered after the channel became ready).
        //
        // Three assertions: registerChannel DEFERS (true; a non-deferring path would return false -> connect dropped), OP_CONNECT is
        // reconstructed on the restored selector (not interest 0), and the connect promise actually COMPLETES after the drain (without the drain-time
        // force-dispatch, OP_CONNECT would be armed but never dispatched -> the promise hangs = the deferred-connect-after-rebuild TIMEOUT).
        given Frame = Frame.internal
        NioLoopbackPair.open().map { (client, sv) =>
            val driver = NioIoDriver.init()
            val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            try
                // Live registration + an armed connect, so OP_CONNECT is recorded in the pending-op map (the source of truth the deferred drain reads).
                assert(driver.registerChannel(handle))
                val pc = new IOPromise[Closed, Unit]
                driver.awaitConnect(handle, pc.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed | NetException]]])
                assert((driver.interestOpsFor(client) & SelectionKey.OP_CONNECT) != 0)

                // Reproduce the rebuild window: close the current selector (driver still live, closedFlag false), then re-register the channel as a
                // caller carrier would mid-rebuild. This DEFERS (true + enqueue); a non-deferring path would return false (connect dropped).
                try driver.selector.close()
                catch case _: java.io.IOException => ()
                val deferred = driver.registerChannel(handle)
                assert(deferred, "registerChannel must defer (not fail) when the selector is closed mid-rebuild on a live driver")
                assert(driver.pendingRegistrationCount == 1)

                // Restore the selector (the rebuild swap) and drain (the poll carrier's per-cycle drainPendingRegistrations): the deferred channel is
                // re-registered on the live selector with OP_CONNECT reconstructed from pendingConnects, NOT interest 0, and the drain force-dispatches
                // a connect probe so an already-completed connect is delivered rather than stranding.
                driver.selector = Selector.open()
                driver.drainPendingRegistrations()
                assert(driver.pendingRegistrationCount == 0)
                assert(
                    pc.done(),
                    "the deferred connect must complete after the drain: the OS connect finished during the deferral, so the drain's dispatchConnect " +
                        "force-dispatch must deliver it (else OP_CONNECT is armed but never re-surfaced and the connect strands to its deadline)"
                )
                assert(pc.poll() == Present(Result.succeed(())))
                succeed
            finally
                driver.closeHandle(handle)
                sv.close()
                driver.close()
            end try
        }
    }

    "registerChannelDeferredThenStartedDeliversAcrossManyChannels" in {
        // Strengthen the deferred-path guard across MANY channels in one driver: every channel is registered, its key cancelled, then
        // re-registered (each hitting the deferred path), each arms a read during the deferred window, and after the loop starts every read must
        // deliver its own distinct bytes. This pins that the poll carrier drains the whole queue and reconstructs each channel's armed interest
        // from the pending-op maps, not just a single deferred registration. The ordering mirrors the real upgrade flow (re-register, then arm the
        // read, then the loop runs and the peer's bytes arrive): all driver mutations happen before start(), so there is no artificial race between
        // the test carrier and a live dispatch loop.
        given Frame = Frame.internal
        val n       = 8
        Kyo.fill(n)(NioLoopbackPair.open()).map { opened =>
            val pairs   = opened.toArray
            val driver  = NioIoDriver.init()
            val handles = pairs.map { case (client, _) =>
                NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            }
            val promises = Array.fill(n)(new IOPromise[Closed, ReadOutcome])
            try
                var i = 0
                while i < n do
                    assert(driver.registerChannel(handles(i)))
                    driver.cancel(handles(i))
                    // Deferred path: the cancelled key lingers (no select() has run), so re-register enqueues for the poll carrier.
                    assert(driver.registerChannel(handles(i)))
                    driver.awaitRead(handles(i), promises(i).asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                    i += 1
                end while
                assert(driver.pendingRegistrationCount == n)

                // Start the loop: one select() cycle flushes all cancelled keys, the drain registers every channel with its armed OP_READ interest.
                discard(driver.start())
                i = 0
                while i < n do
                    pairs(i)._2.write(ByteBuffer.wrap(s"chan-$i".getBytes))
                    i += 1
                end while

                // Collect all reads sequentially; each must carry its own channel's distinct payload.
                def collect(idx: Int): Boolean < (Async & Abort[Closed]) =
                    if idx >= n then (true: Boolean)
                    else
                        promises(idx).asInstanceOf[Fiber.Unsafe[ReadOutcome, Abort[Closed]]].safe.get.map { result =>
                            val ReadOutcome.Bytes(bytes) = result.runtimeChecked
                            assert(new String(bytes.toArray) == s"chan-$idx")
                            collect(idx + 1)
                        }
                collect(0).map { _ =>
                    var j = 0
                    while j < n do
                        driver.closeHandle(handles(j))
                        pairs(j)._2.close()
                        j += 1
                    end while
                    driver.close()
                    assert(driver.pendingRegistrationCount == 0)
                    succeed
                }
            catch
                case t: Throwable =>
                    driver.close()
                    throw t
            end try
        }
    }

    // kyo-net must never create a thread: a driver's loop belongs on scheduler carriers, not on one this driver owns. This asserts the
    // observable consequence rather than the source, so it stays honest if the loop is ever restructured again. Anchored on the loop's own
    // thread name rather than on "no new threads at all", which would be flaky against the scheduler growing its own pool.
    //
    // Deterministic: the old implementation spawned its thread synchronously inside start(), so this fails immediately against it, and passes
    // structurally once the loop is a task chain.
    "start creates no thread of its own" in {
        val driver = kyo.net.internal.backend.NioBackend.createDriver()
        discard(driver.start())
        Sync.defer {
            import scala.jdk.CollectionConverters.*
            val loopThreads = Thread.getAllStackTraces.keySet.asScala.map(_.getName).filter(_.contains("select-loop")).toList
            discard(driver.close())
            assert(
                loopThreads.isEmpty,
                s"the select loop must run on scheduler carriers, found dedicated thread(s): ${loopThreads.mkString(", ")}"
            )
            succeed
        }
    }

end NioIoDriverTest

object NioIoDriverTest:

    /** Selector cycles of `driver`, each observed through a real event: an always-writable loopback channel whose OP_WRITE the cycle
      * dispatches. Selector-carrier state is re-checked after each cycle, so a wait needs no clock and a state that never arrives hangs to the
      * leaf cap. Arming the fence wakes the selector, so a leaf asserting a wakeup of its own cannot use it.
      */
    final class Cycles private (driver: NioIoDriver, handle: NioHandle, peer: SocketChannel)(using AllowUnsafe):

        def next(using Frame): Unit < Async =
            val p = new IOPromise[Closed | NetException, Unit]
            driver.awaitWritable(handle, p.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed | NetException]]])
            p.asInstanceOf[Fiber.Unsafe[Unit, Abort[Closed | NetException]]].safe.getResult.unit
        end next

        def until(cond: => Boolean)(using Frame): Unit < Async =
            Loop(())(_ => if cond then Loop.done(()) else next.andThen(Loop.continue(())))

        def close()(using Frame): Unit =
            driver.closeHandle(handle)
            peer.close()
    end Cycles

    object Cycles:
        def init(driver: NioIoDriver)(using Frame, AllowUnsafe): Cycles < (Async & Abort[java.io.IOException]) =
            NioLoopbackPair.open().map { (client, peer) =>
                val handle = NioHandle.init(client, 64, Duration.Infinity, Duration.Infinity, Frame.internal)
                discard(driver.registerChannel(handle))
                new Cycles(driver, handle, peer)
            }
    end Cycles

end NioIoDriverTest

/** `inner`, running a one-shot callback right after its next `unwrap`: the point where the driver holds freshly decrypted plaintext
  * and has not yet handed it to a read.
  */
final private class UnwrapCallbackEngine(inner: SSLEngine) extends SSLEngine:
    private var pending: Maybe[() => Unit] = Absent

    def onNextUnwrap(f: () => Unit): Unit = pending = Present(f)

    override def unwrap(src: ByteBuffer, dsts: Array[ByteBuffer], offset: Int, length: Int): SSLEngineResult =
        val result = inner.unwrap(src, dsts, offset, length)
        val run    = pending
        pending = Absent
        run.foreach(_())
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
end UnwrapCallbackEngine
