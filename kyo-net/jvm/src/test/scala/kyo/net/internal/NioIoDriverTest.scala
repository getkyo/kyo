package kyo.net.internal

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kyo.*
import kyo.net.Test
import kyo.net.internal.transport.*
import kyo.scheduler.IOPromise

class NioIoDriverTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Open a connected loopback pair (non-blocking client, blocking server). Caller must close both. */
    def openLoopbackPair(): (SocketChannel, SocketChannel) =
        val serverSock = ServerSocketChannel.open()
        serverSock.configureBlocking(true)
        serverSock.bind(new InetSocketAddress("127.0.0.1", 0))
        val port   = serverSock.socket().getLocalPort
        val client = SocketChannel.open()
        client.configureBlocking(false)
        client.connect(new InetSocketAddress("127.0.0.1", port))
        val server = serverSock.accept()
        client.finishConnect()
        serverSock.close()
        (client, server)
    end openLoopbackPair

    /** Create a driver, open a handle+channel, call body, then close everything. */
    def withDriverAndHandle[A](bufferSize: Int = 4096)(body: (NioIoDriver, NioHandle, SocketChannel) => A): A =
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, bufferSize, Duration.Infinity)
        given Frame      = Frame.internal
        try
            driver.registerChannel(handle)
            body(driver, handle, sv)
        finally
            driver.closeHandle(handle)
            sv.close()
            driver.close()
        end try
    end withDriverAndHandle

    /** Poll `cond` on the fiber scheduler (never a thread block) until it holds or `bound` elapses; returns whether it held. */
    private def awaitCondition(bound: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            if cond then Loop.done(true)
            else if java.lang.System.nanoTime() >= deadline then Loop.done(false)
            else Async.sleep(2.millis).andThen(Loop.continue(()))
        }
    end awaitCondition
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
            val handle = NioHandle.init(ch, 4096, Duration.Infinity)
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
            val handle = NioHandle.init(ch, 4096, Duration.Infinity)
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
            val handle = NioHandle.init(ch, 4096, Duration.Infinity)
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
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096, Duration.Infinity)
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

    // -----------------------------------------------------------------------
    // awaitRead: registers interest and completes promise on read
    // -----------------------------------------------------------------------

    "awaitRead completes promise when data arrives" in {
        given Frame      = Frame.internal
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096, Duration.Infinity)
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

    // -----------------------------------------------------------------------
    // isPeerClosed: the peer-close grace probe (poll-on-expiry) detector
    // -----------------------------------------------------------------------

    "isPeerClosed arms a probe that observes a peer FIN while backpressured, staging pre-FIN bytes ahead of the EOF" in {
        given Frame      = Frame.internal
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096, Duration.Infinity)
        driver.registerChannel(handle)
        discard(driver.start())

        // Backpressured: no awaitRead is armed (the pump is parked on a full inbound channel). The peer sends 3 bytes, then closes (FIN).
        discard(sv.write(ByteBuffer.wrap(Array[Byte](10, 20, 30))))
        sv.close()

        // First call arms the probe and returns false; the probe stages the 3 bytes and latches peerClosed on the FIN, so a later call reads true.
        assert(!driver.isPeerClosed(handle), "the first isPeerClosed arms the probe and returns false (not observed yet)")
        awaitCondition(2.seconds)(driver.isPeerClosed(handle)).map { latched =>
            assert(latched, "the probe must latch peerClosed after the peer FIN while backpressured")
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

    "isPeerClosed stays false for a live peer that has not closed" in {
        given Frame      = Frame.internal
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096, Duration.Infinity)
        driver.registerChannel(handle)
        discard(driver.start())

        // The peer stays open and sends nothing: the probe reads n == 0 and stays armed as a standing FIN watch, never latching.
        assert(!driver.isPeerClosed(handle), "the first isPeerClosed arms the probe and returns false")
        Async.sleep(300.millis).map { _ =>
            val stillOpen = !driver.isPeerClosed(handle)
            sv.close()
            driver.closeHandle(handle)
            driver.close()
            assert(stillOpen, "isPeerClosed must stay false for a live peer that has not sent a FIN")
            succeed
        }
    }

    "the grace probe stages at most GraceProbeBudgetChunks buffers per window, not the whole receive buffer" in {
        given Frame      = Frame.internal
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val bufSize      = 64
        val handle       = NioHandle.init(client, bufSize, Duration.Infinity)
        driver.registerChannel(handle)
        discard(driver.start())

        // Backpressured, no read armed. The peer sends twice the budget worth of data (no FIN). One probe window reads at most
        // GraceProbeBudgetChunks buffers, so it stages exactly that many and stops: staying armed with data present would refire the
        // level-triggered selector and drain the whole receive buffer in one window.
        val chunks = NioIoDriver.GraceProbeBudgetChunks
        discard(sv.write(ByteBuffer.wrap(Array.fill[Byte](chunks * 2 * bufSize)(7))))

        assert(!driver.isPeerClosed(handle), "the first isPeerClosed arms the probe and returns false")
        awaitCondition(2.seconds)(driver.stagedBytes(handle) >= chunks * bufSize).map { _ =>
            val staged = driver.stagedBytes(handle)
            sv.close()
            driver.closeHandle(handle)
            driver.close()
            assert(
                staged == chunks * bufSize,
                s"one probe window must stage exactly the budget ($chunks buffers of $bufSize = ${chunks * bufSize} bytes), not the whole receive buffer; got $staged"
            )
        }
    }

    "the grace probe stops staging past GraceProbeStagingCap, so a FIN behind the cap is not observed" in {
        given Frame      = Frame.internal
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 65536, Duration.Infinity)
        driver.registerChannel(handle)
        discard(driver.start())

        // A background writer floods more than the staging cap and then closes (FIN). The probe stages up to the cap and stops consuming, so the
        // FIN sits behind the cap and is never observed: isPeerClosed stays false. This is the deliberate trade that stops a live chatty peer
        // turning the descriptor fix into a heap leak (a FIN behind more than the cap keeps the pre-fix behavior).
        val cap = NioIoDriver.GraceProbeStagingCap
        val writer = new Thread(() =>
            try
                val buf = ByteBuffer.wrap(Array.fill[Byte](cap + (256 * 1024))(1))
                while buf.hasRemaining do discard(sv.write(buf))
                sv.close()
            catch case _: Throwable => ()
        )
        writer.setDaemon(true)
        writer.start()

        // Each poll arms a probe (isPeerClosed) and checks staging; once staging reaches the cap the probe stops arming and consuming.
        awaitCondition(5.seconds) {
            discard(driver.isPeerClosed(handle))
            driver.stagedBytes(handle) >= cap
        }.map { capped =>
            val staged = driver.stagedBytes(handle)
            val closed = driver.isPeerClosed(handle)
            writer.interrupt()
            driver.closeHandle(handle)
            driver.close()
            assert(capped, s"the probe must stage up to the cap ($cap); stagedBytes=$staged")
            assert(!closed, s"a FIN behind the staging cap must not be observed; isPeerClosed=$closed")
        }
    }

    "a read delivers the grace probe's staged plain bytes before fresh socket bytes" in {
        given Frame      = Frame.internal
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096, Duration.Infinity)
        driver.registerChannel(handle)
        discard(driver.start())

        // Backpressured: the peer sends [1,2,3] and a probe stages them. The pump then re-arms and takes over, so the fresh [4,5,6] arrives through
        // the pump's read path. The staged bytes must be delivered first (the pre-read staging drain), then the fresh socket bytes.
        discard(sv.write(ByteBuffer.wrap(Array[Byte](1, 2, 3))))
        awaitCondition(2.seconds) {
            discard(driver.isPeerClosed(handle))
            driver.stagedBytes(handle) >= 3
        }.andThen {
            val p1 = new IOPromise[Closed, ReadOutcome]
            driver.awaitRead(handle, p1.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
            p1.asInstanceOf[Fiber.Unsafe[ReadOutcome, Abort[Closed]]].safe.get
        }.map { r1 =>
            val ReadOutcome.Bytes(span1) = r1.runtimeChecked
            assert(span1.toArray.toList == List[Byte](1, 2, 3), s"the staged bytes must be delivered first; got ${span1.toArray.toList}")
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

    // -----------------------------------------------------------------------
    // awaitWritable: registers interest and completes promise when writable
    // -----------------------------------------------------------------------

    "awaitWritable completes promise when channel is writable" in {
        given Frame      = Frame.internal
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096, Duration.Infinity)
        driver.registerChannel(handle)
        discard(driver.start())

        val p = new IOPromise[Closed, Unit]
        driver.awaitWritable(handle, p.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])

        p.asInstanceOf[Fiber.Unsafe[Unit, Abort[Closed]]].safe.get.map { _ =>
            sv.close()
            driver.closeHandle(handle)
            driver.close()
            succeed
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
        val handle = NioHandle.init(ch, 4096, Duration.Infinity)
        driver.registerChannel(handle)

        val p1 = new IOPromise[Closed, Unit]
        val p2 = new IOPromise[Closed, Unit]

        // First registration succeeds (stores in pendingConnects)
        driver.awaitConnect(handle, p1.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
        // Second registration with same channel: duplicate, so p2 panics
        driver.awaitConnect(handle, p2.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])

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
        given Frame      = Frame.internal
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096, Duration.Infinity)
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

    "cancel is idempotent: second call does not throw" in {
        given Frame      = Frame.internal
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096, Duration.Infinity)
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
        val driver = NioIoDriver.init()
        discard(driver.start())
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096, Duration.Infinity)
        Sync.ensure(Sync.defer {
            sv.close()
            driver.close()
        }) {
            assert(driver.registerChannel(handle), "the channel must register with the selector")
            // Gate until the selector has consumed the registration wakeup (wakeupPending cleared by a select() return) and re-parked idle
            // with the channel registered, so the close below is the ONLY thing that can drive the deregistration pass.
            awaitCondition(5.seconds)(!driver.wakeupPending.get() && client.isRegistered()).map { parked =>
                assert(parked, "the selector never parked idle with the channel registered")
                driver.closeHandle(handle)
                // closeHandle wakes the selector, so the cancelled key is deregistered within a poll cycle. Without the wake the idle selector never
                // runs the pass and this times out for the right reason: the cancelled key, and its fd, leak in CLOSE_WAIT.
                awaitCondition(10.seconds)(!client.isRegistered()).map { deregistered =>
                    assert(
                        deregistered,
                        "closeHandle left the channel registered on an idle selector: the cancelled key and its fd leak (CLOSE_WAIT) until an unrelated wakeup"
                    )
                }
            }
        }
    }

    "closeHandle fails pending read promise" in {
        given Frame      = Frame.internal
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096, Duration.Infinity)
        driver.registerChannel(handle)

        val p = new IOPromise[Closed, ReadOutcome]
        driver.awaitRead(handle, p.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])

        driver.closeHandle(handle)

        assert(p.done())
        sv.close()
        driver.close()
        succeed
    }

    // -----------------------------------------------------------------------
    // close: shuts down driver and fails all pending promises
    // -----------------------------------------------------------------------

    "close fails all pending read promises with Closed" in {
        given Frame      = Frame.internal
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096, Duration.Infinity)
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
        driver.awaitAccept(serverChannel, p.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])

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
        driver.awaitAccept(serverChannel, p.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
        driver.cleanupAccept(serverChannel)

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
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 16, Duration.Infinity) // tiny buffer
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
            val (client, sv) = openLoopbackPair()
            val handle       = NioHandle.init(client, 4096, Duration.Infinity)
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
            val (clientA, svA) = openLoopbackPair()
            val (clientB, svB) = openLoopbackPair()

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
            val handleA = NioHandle.init(clientA, 4096, Duration.Infinity)
            val handleB = NioHandle.init(clientB, 4096, Duration.Infinity)
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
        given Frame       = Frame.internal
        val driver        = NioIoDriver.init()
        val (client, sv)  = openLoopbackPair()
        val handle        = NioHandle.init(client, 4096, Duration.Infinity)
        val serverChannel = ServerSocketChannel.open()
        serverChannel.configureBlocking(false)
        serverChannel.bind(new InetSocketAddress("127.0.0.1", 0))
        try
            driver.registerChannel(handle)
            driver.registerServerChannel(serverChannel)

            val pw = new IOPromise[Closed, Unit]
            val pr = new IOPromise[Closed, ReadOutcome]
            val pa = new IOPromise[Closed, Unit]
            driver.awaitWritable(handle, pw.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
            driver.awaitRead(handle, pr.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
            driver.awaitAccept(serverChannel, pa.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])

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

    "selectorRebuildKeepsInFlightReadDeliverable" in {
        // End-to-end companion to selectorRebuildPreservesArmedInterest: a read armed before a rebuild must still
        // deliver real data once the loop runs on the new selector. A rebuild that did not preserve the read
        // interest would leave this promise uncompleted until the suite timeout.
        given Frame      = Frame.internal
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096, Duration.Infinity)
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
            val (client, sv) = openLoopbackPair()
            val handle       = NioHandle.init(client, 4096, Duration.Infinity)
            driver.registerChannel(handle)
            discard(driver.start())

            val p1 = new kyo.scheduler.IOPromise[Closed, Unit]
            driver.awaitWritable(handle, p1.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])

            p1.asInstanceOf[Fiber.Unsafe[Unit, Abort[Closed]]].safe.get.map { _ =>
                // OP_WRITE was dispatched and interest cleared. Register a second awaitWritable:
                // the guard must fire a new wakeup (flag was cleared by pollOnce) so this resolves.
                val p2 = new kyo.scheduler.IOPromise[Closed, Unit]
                driver.awaitWritable(handle, p2.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])

                p2.asInstanceOf[Fiber.Unsafe[Unit, Abort[Closed]]].safe.get.map { _ =>
                    sv.close()
                    driver.closeHandle(handle)
                    driver.close()
                    // Both promises resolved: the guard coalesced redundant wakeups while still
                    // allowing genuine wakeups to wake the blocked selector.
                    succeed
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
        given Frame      = Frame.internal
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096, Duration.Infinity)
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
            driver.awaitWritable(handle, p1.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])

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
        given Frame      = Frame.internal
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096, Duration.Infinity)
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
        val handle = NioHandle.init(ch, 4096, Duration.Infinity)
        try
            driver.registerChannel(handle)
            // Pre-set the coalescing condition: an in-flight wakeup is pending, so any GUARDED wakeup would coalesce away.
            // The unconditional wakeup must fire regardless.
            discard(driver.wakeupPending.compareAndSet(false, true))
            val before = driver.connectWakeups.get()
            val pc     = new IOPromise[Closed, Unit]
            driver.awaitConnect(handle, pc.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
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
        // maps. The loopback connect is ALREADY complete here (openLoopbackPair calls finishConnect), so this also exercises the
        // deferred-connect-after-rebuild edge: a connect that completed during the deferral window must still complete, which needs the drain-time
        // dispatchConnect force-dispatch (the selector does not re-surface OP_CONNECT for an interest registered after the channel became ready).
        //
        // Three assertions: registerChannel DEFERS (true; a non-deferring path would return false -> connect dropped), OP_CONNECT is
        // reconstructed on the restored selector (not interest 0), and the connect promise actually COMPLETES after the drain (without the drain-time
        // force-dispatch, OP_CONNECT would be armed but never dispatched -> the promise hangs = the deferred-connect-after-rebuild TIMEOUT).
        given Frame      = Frame.internal
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096, Duration.Infinity)
        try
            // Live registration + an armed connect, so OP_CONNECT is recorded in the pending-op map (the source of truth the deferred drain reads).
            assert(driver.registerChannel(handle))
            val pc = new IOPromise[Closed, Unit]
            driver.awaitConnect(handle, pc.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
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

    "registerChannelDeferredThenStartedDeliversAcrossManyChannels" in {
        // Strengthen the deferred-path guard across MANY channels in one driver: every channel is registered, its key cancelled, then
        // re-registered (each hitting the deferred path), each arms a read during the deferred window, and after the loop starts every read must
        // deliver its own distinct bytes. This pins that the poll carrier drains the whole queue and reconstructs each channel's armed interest
        // from the pending-op maps, not just a single deferred registration. The ordering mirrors the real upgrade flow (re-register, then arm the
        // read, then the loop runs and the peer's bytes arrive): all driver mutations happen before start(), so there is no artificial race between
        // the test carrier and a live dispatch loop.
        given Frame  = Frame.internal
        val driver   = NioIoDriver.init()
        val n        = 8
        val pairs    = Array.fill(n)(openLoopbackPair())
        val handles  = pairs.map { case (client, _) => NioHandle.init(client, 4096, Duration.Infinity) }
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
