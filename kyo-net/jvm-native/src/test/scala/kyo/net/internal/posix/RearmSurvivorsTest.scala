package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** ET-behavior witness: confirms that under edge-triggered (ET) registration the driver never submits a rearm call.
  *
  * A one-shot (EPOLLONESHOT / EV_ONESHOT) model would require a "survivor re-arm" after each event: when a fd has both read and write interest
  * and only one direction fires, the surviving direction has to be explicitly re-registered. Under ET (EPOLLET / EV_CLEAR) the
  * fd stays armed in the kernel for both directions; no rearm is needed or correct.
  *
  * These witnesses use a real driver backed by a RecordingPollerBackend to confirm that no "rearm(" entry appears in the call log after a single
  * read-only or write-only event, and that both directions remain reachable without a re-register.
  *
  * The driver exposes no survivor re-arm under ET; there is nothing to re-register after a partial fire. These witnesses assert that the ET
  * behavior (no lost wakeup after a partial fire) holds without one.
  *
  * Gate: PosixTestSockets.assumePoller() for the driver-level leaves.
  */
class RearmSurvivorsTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    "ET registration: no rearm in call log after a read-only event" in {
        PosixTestSockets.assumePoller()
        // Set up a loopback pair. Half-close the client write side so the accepted fd becomes read-ready (EOF). Fill the send buffer so the
        // accepted fd is NOT write-ready. Arm both directions. After the read event fires, assert the call log contains no "rearm(" entry.
        smallBuffers().map { case (clientFd, acceptedFd) =>
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            val handle   = PosixHandle.socket(acceptedFd, PosixHandle.DefaultReadBufferSize, Absent)
            discard(driver.start())

            fillSendBuffer(acceptedFd)
            PosixTestSockets.halfClose(spy, clientFd)

            val readPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
            driver.awaitRead(handle, readPromise)
            // Arm write too: under one-shot this would require a survivor re-arm after the read fires; under ET it must not.
            val writePromise = Promise.Unsafe.init[Unit, Abort[Closed]]()
            driver.awaitWritable(handle, writePromise)

            // Wait for the read promise to complete (the driver dispatched the EOF read event). Then inspect the call log.
            readPromise.safe.get.map { _ =>
                driver.close()
                // Close the loopback pair: this leaf only tore the driver down, which does not close the connection fds, so without these the
                // client + accepted fds leak (the second leaf below already closes them; this one was missing it).
                discard(sock.close(clientFd))
                discard(sock.close(acceptedFd))
                val log = backend.callLog
                assert(
                    !log.exists(_.startsWith("rearm(")),
                    s"ET registration must never produce a rearm call, but log contains: $log"
                )
                assert(
                    log.exists(_.startsWith("registerRead")),
                    s"expected a registerRead entry in call log: $log"
                )
                assert(
                    log.exists(_.startsWith("registerWrite")),
                    s"expected a registerWrite entry in call log: $log"
                )
            }
        }
    }

    "ET registration: write direction fires independently without a rearm after read" in {
        PosixTestSockets.assumePoller()
        // Set up a fresh loopback pair (no send-buffer fill this time). Arm read first. After the write direction fires independently
        // (the socket starts writable because the peer is connected), confirm no "rearm(" in the log.
        loopbackPair().map { case (clientFd, acceptedFd) =>
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            val handle   = PosixHandle.socket(acceptedFd, PosixHandle.DefaultReadBufferSize, Absent)
            discard(driver.start())

            // Register write-interest; the socket is already writable (connected), so the event fires quickly.
            val writePromise = Promise.Unsafe.init[Unit, Abort[Closed]]()
            driver.awaitWritable(handle, writePromise)

            writePromise.safe.get.map { _ =>
                driver.close()
                discard(sock.close(clientFd))
                discard(sock.close(acceptedFd))
                val log = backend.callLog
                assert(
                    !log.exists(_.startsWith("rearm(")),
                    s"ET registration must never produce a rearm call after a write event, but log contains: $log"
                )
            }
        }
    }

    /** Build a connected loopback pair with shrunk send/receive buffers on BOTH ends so the accepted fd's send buffer fills quickly when the
      * client does not read; returns (clientFd, acceptedFd).
      */
    private def smallBuffers()(using Frame, kyo.test.AssertScope): (Int, Int) < Async =
        val server = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(a.close())) {
            assert(sock.bind(server, a, l).value == 0)
            assert(sock.listen(server, 4).value == 0)
            val out = Buffer.alloc[Byte](SockAddr.inet4Size)
            val ol  = Buffer.alloc[Int](1)
            ol.set(0, SockAddr.inet4Size)
            val port =
                try
                    assert(sock.getsockname(server, out, ol).value == 0)
                    ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
                finally
                    out.close()
                    ol.close()
            val client = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
            PosixTestSockets.setIntSockOpt(client, PosixConstants.SO_RCVBUF, 2048)
            val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
            val connected =
                Sync.ensure(Sync.defer(ca.close()))(sock.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0)))
            connected.andThen {
                val noAddr = Buffer.alloc[Byte](SockAddr.inet4Size)
                val noLen  = Buffer.alloc[Int](1)
                noLen.set(0, SockAddr.inet4Size)
                Sync.ensure(Sync.defer { noAddr.close(); noLen.close() }) {
                    sock.accept(server, noAddr, noLen).safe.get.map(_.value)
                }.map { accepted =>
                    PosixTestSockets.setIntSockOpt(accepted, PosixConstants.SO_SNDBUF, 2048)
                    val shim = Ffi.load[PosixShimBindings]
                    assert(shim.kyo_posix_set_nonblocking(client) == 0, "set_nonblocking(client) failed")
                    assert(shim.kyo_posix_set_nonblocking(accepted) == 0, "set_nonblocking(accepted) failed")
                    sock.close(server).safe.get.map(_ => (client, accepted))
                }
            }
        }
    end smallBuffers

    private def loopbackPair()(using Frame, kyo.test.AssertScope): (Int, Int) < Async =
        val server = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(a.close())) {
            assert(sock.bind(server, a, l).value == 0)
            assert(sock.listen(server, 4).value == 0)
            val out = Buffer.alloc[Byte](SockAddr.inet4Size)
            val ol  = Buffer.alloc[Int](1)
            ol.set(0, SockAddr.inet4Size)
            val port =
                try
                    assert(sock.getsockname(server, out, ol).value == 0)
                    ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
                finally
                    out.close()
                    ol.close()
            val client   = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
            val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
            val connected =
                Sync.ensure(Sync.defer(ca.close()))(sock.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0)))
            connected.andThen {
                val noAddr = Buffer.alloc[Byte](SockAddr.inet4Size)
                val noLen  = Buffer.alloc[Int](1)
                noLen.set(0, SockAddr.inet4Size)
                Sync.ensure(Sync.defer { noAddr.close(); noLen.close() }) {
                    sock.accept(server, noAddr, noLen).safe.get.map(_.value)
                }.map { accepted =>
                    val shim = Ffi.load[PosixShimBindings]
                    assert(shim.kyo_posix_set_nonblocking(client) == 0, "set_nonblocking(client) failed")
                    assert(shim.kyo_posix_set_nonblocking(accepted) == 0, "set_nonblocking(accepted) failed")
                    sock.close(server).safe.get.map(_ => (client, accepted))
                }
            }
        }
    end loopbackPair

    /** Flood `fd`'s send buffer so the kernel reports it not-writable. */
    private def fillSendBuffer(fd: Int)(using AllowUnsafe): Unit =
        val chunk = Buffer.alloc[Byte](65536)
        try
            var more  = true
            var guard = 0
            while more && guard < 4096 do
                val r = sock.sendNow(fd, chunk, 65536L, PosixConstants.MSG_DONTWAIT | PosixConstants.MSG_NOSIGNAL)
                if r.value <= 0 then more = false
                guard += 1
            end while
        finally chunk.close()
        end try
    end fillSendBuffer

end RearmSurvivorsTest
