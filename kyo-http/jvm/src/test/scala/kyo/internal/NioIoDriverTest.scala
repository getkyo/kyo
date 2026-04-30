package kyo.internal

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kyo.*
import kyo.internal.transport.*
import kyo.scheduler.IOPromise

class NioIoDriverTest extends kyo.Test:

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
        val handle       = NioHandle.init(client, bufferSize)
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
            val handle = NioHandle.init(ch, 4096)
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
            val handle = NioHandle.init(ch, 4096)
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
            val handle = NioHandle.init(ch, 4096)
            val result = driver.registerChannel(handle)
            assert(!result)
            succeed
        finally
            given Frame = Frame.internal
            driver.close()
        end try
    }

    // -----------------------------------------------------------------------
    // write — plain TCP
    // -----------------------------------------------------------------------

    "writePlain returns Done when all bytes are written" in {
        withDriverAndHandle() { (driver, handle, sv) =>
            val data   = Span.fromUnsafe("hello".getBytes)
            val result = driver.write(handle, data)
            assert(result == WriteResult.Done)
            succeed
        }
    }

    "write returns Done for empty span" in {
        withDriverAndHandle() { (driver, handle, sv) =>
            val result = driver.write(handle, Span.empty[Byte])
            assert(result == WriteResult.Done)
            succeed
        }
    }

    "write returns Error after channel is closed" in {
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096)
        driver.registerChannel(handle)
        client.close()
        sv.close()
        try
            val data   = Span.fromUnsafe("hello".getBytes)
            val result = driver.write(handle, data)
            assert(result == WriteResult.Error)
            succeed
        finally
            given Frame = Frame.internal
            driver.close()
        end try
    }

    // -----------------------------------------------------------------------
    // awaitRead — registers interest and completes promise on read
    // -----------------------------------------------------------------------

    "awaitRead completes promise when data arrives" in run {
        given Frame      = Frame.internal
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096)
        driver.registerChannel(handle)
        discard(driver.start())

        val p = new IOPromise[Closed, Span[Byte]]
        driver.awaitRead(handle, p.asInstanceOf[Promise.Unsafe[Span[Byte], Abort[Closed]]])

        // Write data from server side so client can read
        sv.write(ByteBuffer.wrap("hello".getBytes))

        p.asInstanceOf[Fiber.Unsafe[Span[Byte], Abort[Closed]]].safe.get.map { result =>
            sv.close()
            driver.closeHandle(handle)
            driver.close()
            assert(result.nonEmpty)
            succeed
        }
    }

    // -----------------------------------------------------------------------
    // awaitWritable — registers interest and completes promise when writable
    // -----------------------------------------------------------------------

    "awaitWritable completes promise when channel is writable" in run {
        given Frame      = Frame.internal
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096)
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
    // awaitConnect — duplicate registration panics second promise
    // -----------------------------------------------------------------------

    "awaitConnect fails promise on duplicate registration" in {
        given Frame = Frame.internal
        val driver  = NioIoDriver.init()
        val ch      = SocketChannel.open()
        ch.configureBlocking(false)
        val handle = NioHandle.init(ch, 4096)
        driver.registerChannel(handle)

        val p1 = new IOPromise[Closed, Unit]
        val p2 = new IOPromise[Closed, Unit]

        // First registration succeeds (stores in pendingConnects)
        driver.awaitConnect(handle, p1.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
        // Second registration with same channel → duplicate → p2 panics
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
    // cancel — removes pending operations
    // -----------------------------------------------------------------------

    "cancel fails pending read promise with Closed" in {
        given Frame      = Frame.internal
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096)
        driver.registerChannel(handle)

        val p = new IOPromise[Closed, Span[Byte]]
        driver.awaitRead(handle, p.asInstanceOf[Promise.Unsafe[Span[Byte], Abort[Closed]]])

        driver.cancel(handle)

        assert(p.done())
        val r = p.poll()
        assert(r match
            case Present(Result.Failure(_)) => true
            case _                          => false)

        sv.close()
        driver.close()
        succeed
    }

    "cancel is idempotent — second call does not throw" in {
        given Frame      = Frame.internal
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096)
        driver.registerChannel(handle)
        driver.cancel(handle)
        driver.cancel(handle) // must not throw
        sv.close()
        driver.close()
        succeed
    }

    // -----------------------------------------------------------------------
    // closeHandle — cancels key, closes channel, cleans up pending promises
    // -----------------------------------------------------------------------

    "closeHandle closes the underlying channel" in {
        given Frame = Frame.internal
        withDriverAndHandle() { (driver, handle, sv) =>
            driver.closeHandle(handle)
            assert(!handle.channel.isOpen)
            succeed
        }
    }

    "closeHandle fails pending read promise" in {
        given Frame      = Frame.internal
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096)
        driver.registerChannel(handle)

        val p = new IOPromise[Closed, Span[Byte]]
        driver.awaitRead(handle, p.asInstanceOf[Promise.Unsafe[Span[Byte], Abort[Closed]]])

        driver.closeHandle(handle)

        assert(p.done())
        sv.close()
        driver.close()
        succeed
    }

    // -----------------------------------------------------------------------
    // close — shuts down driver and fails all pending promises
    // -----------------------------------------------------------------------

    "close fails all pending read promises with Closed" in {
        given Frame      = Frame.internal
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096)
        driver.registerChannel(handle)

        val p = new IOPromise[Closed, Span[Byte]]
        driver.awaitRead(handle, p.asInstanceOf[Promise.Unsafe[Span[Byte], Abort[Closed]]])

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

    "close is idempotent — second close does not throw" in {
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
    // awaitAccept — registers accept interest
    // -----------------------------------------------------------------------

    "awaitAccept completes promise when client connects" in run {
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
    // cleanupAccept — removes pending accept entry
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
    // write — large span uses fallback ByteBuffer.wrap path
    // -----------------------------------------------------------------------

    "write oversized data (larger than writeBuffer) may return Done or Partial" in {
        // Use a small buffer size so the oversized path is exercised
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 16) // tiny buffer
        driver.registerChannel(handle)
        try
            val bigData = Span.fromUnsafe(Array.fill[Byte](8192)(42))
            // May return Done or Partial depending on socket buffer, but must not throw
            val result = driver.write(handle, bigData)
            assert(result == WriteResult.Done || result.isInstanceOf[WriteResult.Partial])
            succeed
        finally
            sv.close()
            given Frame = Frame.internal
            driver.closeHandle(handle)
            driver.close()
        end try
    }

end NioIoDriverTest
