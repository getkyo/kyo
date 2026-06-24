package kyo.net.internal

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kyo.*
import kyo.net.Test

/** A NIO handshake-deadline reap removes the driver's pendingReads entry for the reaped handle.
  *
  * The server TLS accept path arms a read on the accepted handle (creating the driver's `pendingReads[channel] -> handle` entry) while the
  * handshake runs. When the handshake fails or its deadline fires, the connPromise teardown ran a bare `clientChannel.close()` and never reaped
  * the handle through the driver, so the `pendingReads` entry (and its armed promise) stayed stranded: a slowloris handshake-stall leaks one
  * driver map entry per connection. The fix routes that teardown through `driver.closeHandle(handle)` (the same seam PosixTransport.teardown
  * uses), which removes the entry and fails the parked read.
  *
  * This drives the driver seam the fix relies on deterministically (no timing): arm a read to create the entry, then show that the bare channel
  * close the buggy path used leaves the entry stranded, while `driver.closeHandle` (what the deadline teardown now calls) removes it. The
  * `hasPendingRead` accessor is the empty-map leak witness. The cross-backend behavioral reap is covered by TransportHandshakeTimeoutTest; this
  * pins the NIO-specific internal cleanup the bare close skipped.
  */
class NioTransportHandshakeReapTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    private def openLoopbackPair(): (SocketChannel, SocketChannel) =
        val serverSock = ServerSocketChannel.open()
        serverSock.configureBlocking(true)
        serverSock.bind(new InetSocketAddress("127.0.0.1", 0))
        val port   = serverSock.socket().getLocalPort
        val client = SocketChannel.open()
        client.configureBlocking(false)
        client.connect(new InetSocketAddress("127.0.0.1", port))
        val server = serverSock.accept()
        discard(client.finishConnect())
        serverSock.close()
        (client, server)
    end openLoopbackPair

    "a bare channel close leaves the pendingReads entry stranded, driver.closeHandle removes it (the fix)" in {
        val driver       = NioIoDriver.init()
        val (client, sv) = openLoopbackPair()
        val handle       = NioHandle.init(client, 4096)
        try
            discard(driver.registerChannel(handle))
            // Arm a read: this is what the server handshake does while waiting for the ClientHello; it creates the pendingReads[channel] -> handle
            // entry that the deadline teardown must clean up.
            val readPromise = Promise.Unsafe.init[Span[Byte], Abort[Closed]]()
            driver.awaitRead(handle, readPromise)
            assert(driver.hasPendingRead(handle), "arming a read must register the pendingReads entry")

            // The buggy teardown path: closing only the channel does NOT remove the driver's pendingReads entry.
            try client.close()
            catch case _: java.io.IOException => ()
            assert(driver.hasPendingRead(handle), "a bare channel close must leave the pendingReads entry (the leak this reproduces)")

            // The fix: routing through driver.closeHandle removes the entry and fails the parked read.
            driver.closeHandle(handle)
            assert(!driver.hasPendingRead(handle), "driver.closeHandle must remove the pendingReads entry (the reap seam)")
            assert(readPromise.done(), "driver.closeHandle must complete the parked read promise")
        finally
            sv.close()
            driver.close()
        end try
    }

end NioTransportHandshakeReapTest
