package kyo.net.internal.transport

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.posix.PollerIoDriver
import kyo.net.internal.posix.PosixHandle
import kyo.net.internal.posix.PosixTestSockets
import kyo.net.internal.posix.RecordingIoDriver
import kyo.net.internal.posix.SocketBindings

/** Unit tests for the driver-backed Connection.init wiring.
  *
  * Tests unique to the driver wiring path (idempotent close, cancel-before-closeHandle ordering, inbound backpressure) over a real
  * PollerIoDriver wrapped in a RecordingIoDriver. Duplicate tests (init creates channels and handle, isOpen, start registers first read)
  * are removed: they are covered by ConnectionTest and TransportUnsafeTest respectively.
  *
  * Gate: assumePoller() cancels where no epoll (Linux) or kqueue (macOS/BSD) is available.
  */
class ConnectionInitTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    private val transportConfig = kyo.net.NetConfig.default
    private val sock            = Ffi.load[SocketBindings]

    private def assumePoller(): Unit =
        PosixTestSockets.assumePoller()
        ()

    /** Send `payload` to `peerFd` in pieces, retrying on a non-blocking `EAGAIN`, so a slow consumer's backpressure (which fills the kernel
      * buffers) does not drop bytes: the send resumes as the consumer drains.
      */
    private def sendAll(peerFd: Int, payload: Array[Byte])(using Frame): Unit < Async =
        Loop(0) { sent =>
            if sent >= payload.length then Loop.done(())
            else
                Sync.defer {
                    val len = math.min(16 * 1024, payload.length - sent)
                    val buf = kyo.ffi.Buffer.alloc[Byte](len)
                    var i   = 0
                    while i < len do
                        buf.set(i, payload(sent + i)); i += 1
                    val n = sock.sendNow(peerFd, buf, len.toLong, 0).value
                    buf.close()
                    n
                }.map { n =>
                    if n > 0 then Loop.continue(sent + n.toInt)
                    else Async.sleep(1.millis).andThen(Loop.continue(sent))
                }
        }
    end sendAll

    /** Drain `n` bytes from a connection's inbound channel, concatenated in delivery order. */
    private def drainAll(channel: Channel.Unsafe[Span[Byte]], n: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= n then Loop.done(acc)
            else channel.safe.take.map(span => Loop.continue(acc ++ span.toArray))
        }

    // Anti-flakiness: AtomicBoolean CAS in Connection.init is synchronous; spy counts readable immediately after close.
    "close is idempotent: second close is a no-op" in {
        assumePoller()
        val real = PollerIoDriver.init()
        val spy  = new RecordingIoDriver(real)
        discard(spy.start())
        PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
            val handle = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
            val conn   = Connection.init(handle, spy, channelCapacity = 8)
            conn.start()
            conn.close()
            conn.close()
            // The AtomicBoolean CAS in Connection.init ensures cancel and closeHandle run exactly once.
            assert(spy.cancelCalls.get() == 1, s"cancel must be called exactly once, got ${spy.cancelCalls.get()}")
            assert(spy.closeHandleCalls.get() == 1, s"closeHandle must be called exactly once, got ${spy.closeHandleCalls.get()}")
            spy.close()
            discard(sock.close(peerFd))
            succeed
        }
    }

    // Anti-flakiness: cancel and closeHandle run in the same synchronous closeFn lambda; the onCancel/onCloseHandle hooks
    // record the order into a ListBuffer with no async involved.
    "close closes channels and calls driver cancel before closeHandle" in {
        assumePoller()
        val real       = PollerIoDriver.init()
        val closeOrder = scala.collection.mutable.ListBuffer[String]()
        val spy        = new RecordingIoDriver(real)
        spy.onCancel = () => closeOrder += "cancel"
        spy.onCloseHandle = () => closeOrder += "closeHandle"

        discard(spy.start())
        PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
            val handle = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
            val conn   = Connection.init(handle, spy, channelCapacity = 8)
            conn.start()
            conn.close()

            assert(conn.inbound.closed(), "inbound channel must be closed")
            assert(conn.outbound.closed(), "outbound channel must be closed")
            assert(spy.cancelCalls.get() == 1, "cancel must be called exactly once")
            assert(spy.closeHandleCalls.get() == 1, "closeHandle must be called exactly once")
            assert(closeOrder.toList == List("cancel", "closeHandle"), s"cancel must precede closeHandle, got $closeOrder")
            spy.close()
            discard(sock.close(peerFd))
            succeed
        }
    }

    // The Connection wires a ReadPump over a capacity-1 inbound channel. A payload many readChunkSize chunks long, delivered over a real
    // loopback while a slow consumer drains one chunk at a time, drives the wired pump through repeated backpressure park/resume cycles. The
    // behavioral guarantee for the wiring: every byte reaches the inbound channel in order, none lost. The byte pattern makes loss or reordering
    // observable; the send and drain run concurrently so the kernel buffers cannot deadlock.
    "inbound backpressure delivers every byte in order to a slow consumer (channel wiring)" in {
        assumePoller()
        val real = PollerIoDriver.init()
        val spy  = new RecordingIoDriver(real)
        discard(spy.start())
        PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
            val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
            val conn    = Connection.init(handle, spy, channelCapacity = 1)
            val payload = Array.tabulate[Byte](128 * 1024)(i => (i % 251).toByte)

            conn.start()
            Async.zip(sendAll(peerFd, payload), drainAll(conn.inbound, payload.length)).map { case (_, got) =>
                assert(got.length == payload.length, s"expected ${payload.length} bytes through the wired channel, got ${got.length}")
                assert(got.sameElements(payload), "the wired inbound channel must deliver every byte in order under backpressure")
                conn.close()
                spy.close()
                discard(sock.close(peerFd))
                succeed
            }
        }
    }

    // onClosing (the parked-handler close signal kyo-http observes) completes exactly when closeFn wins the close, and not before.
    "onClosing completes when the connection is closed" in {
        assumePoller()
        val real = PollerIoDriver.init()
        val spy  = new RecordingIoDriver(real)
        discard(spy.start())
        PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
            val handle = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
            val conn   = Connection.init(handle, spy, channelCapacity = 8)
            conn.start()
            assert(!conn.onClosing.done(), "onClosing must not be complete on a live connection")
            conn.close()
            assert(conn.onClosing.done(), "onClosing must complete when close() wins the close")
            spy.close()
            discard(sock.close(peerFd))
            succeed
        }
    }

    // The production trigger: a peer FIN drives the ReadPump to EOF/teardown, which reaches closeFn and completes onClosing.
    "onClosing completes on a peer-driven ReadPump teardown" in {
        assumePoller()
        val real = PollerIoDriver.init()
        val spy  = new RecordingIoDriver(real)
        discard(spy.start())
        PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
            val handle = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
            val conn   = Connection.init(handle, spy, channelCapacity = 8)
            conn.start()
            discard(sock.close(peerFd)) // peer FIN -> ReadPump EOF -> teardown -> closeFn
            Async.timeout(5.seconds)(conn.onClosing.safe.get).andThen {
                assert(conn.onClosing.done(), "onClosing must complete on a peer-driven ReadPump teardown")
                spy.close()
                succeed
            }
        }
    }

    // Safety property (documented on Connection.onClosing): a STARTTLS detach leaves the fd for the in-place upgrade and does NOT
    // fire onClosing (state Upgrading bars closeFn's win branch); the upgraded connection is a fresh init with its own signal.
    "onClosing does not complete on detachForUpgrade" in {
        assumePoller()
        val real = PollerIoDriver.init()
        val spy  = new RecordingIoDriver(real)
        discard(spy.start())
        PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
            val handle = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
            val conn   = Connection.init(handle, spy, channelCapacity = 8)
            conn.start()
            discard(conn.detachForUpgrade())
            assert(!conn.onClosing.done(), "onClosing must NOT complete on a STARTTLS detach")
            // The detach left the fd open for the (absent) upgrade; close it directly so the test leaks no fd.
            discard(sock.close(clientFd))
            discard(sock.close(peerFd))
            spy.close()
            succeed
        }
    }

end ConnectionInitTest
