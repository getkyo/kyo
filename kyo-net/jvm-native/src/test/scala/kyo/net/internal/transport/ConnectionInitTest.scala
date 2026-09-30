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
    // Lazy: Ffi.load opens the library, which exists only where assumePoller lets a leaf run.
    private lazy val sock = Ffi.load[SocketBindings]

    private def assumePoller(): Unit =
        PosixTestSockets.assumePoller()
        ()

    /** A signal completed on every take by [[drainAll]]: the consumer's progress is what frees kernel buffer space for a blocked sender. */
    final private class Progress:
        private val current                   = new java.util.concurrent.atomic.AtomicReference(Promise.Unsafe.init[Unit, Any]())
        def next(): Promise.Unsafe[Unit, Any] = current.get()
        def advance(): Unit                   = current.getAndSet(Promise.Unsafe.init[Unit, Any]()).completeDiscard(Result.succeed(()))
    end Progress

    /** Send `payload` to `peerFd` in pieces, so a slow consumer's backpressure (which fills the kernel buffers) does not drop bytes. On a
      * non-blocking `EAGAIN` the send waits for the consumer's next take: a full kernel buffer holds unread bytes, so that take always comes.
      * The wait is taken before the send, so a take landing between the two is not missed.
      */
    private def sendAll(peerFd: Int, payload: Array[Byte], progress: Progress)(using Frame): Unit < Async =
        Loop(0) { sent =>
            if sent >= payload.length then Loop.done(())
            else
                Sync.defer {
                    val next = progress.next()
                    val len  = math.min(16 * 1024, payload.length - sent)
                    val buf  = kyo.ffi.Buffer.alloc[Byte](len)
                    var i    = 0
                    while i < len do
                        buf.set(i, payload(sent + i)); i += 1
                    val n = sock.sendNow(peerFd, buf, len.toLong, 0).value
                    buf.close()
                    (n, next)
                }.map { (n, next) =>
                    if n > 0 then Loop.continue(sent + n.toInt)
                    else next.safe.get.andThen(Loop.continue(sent))
                }
        }
    end sendAll

    /** Drain `n` bytes from a connection's inbound channel, concatenated in delivery order, signalling `progress` on each take. */
    private def drainAll(channel: Channel.Unsafe[Span[Byte]], n: Int, progress: Progress)(using
        Frame
    ): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= n then Loop.done(acc)
            else
                channel.safe.take.map { span =>
                    progress.advance()
                    Loop.continue(acc ++ span.toArray)
                }
        }

    // Anti-flakiness: AtomicBoolean CAS in Connection.init is synchronous; spy counts readable immediately after close.
    "close is idempotent: second close is a no-op" in {
        assumePoller()
        val real = PollerIoDriver.init()
        val spy  = new RecordingIoDriver(real)
        discard(spy.start())
        PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
            val handle = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
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
            val handle = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
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
            val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
            val conn    = Connection.init(handle, spy, channelCapacity = 1)
            val payload = Array.tabulate[Byte](128 * 1024)(i => (i % 251).toByte)

            conn.start()
            val progress = Progress()
            Async.zip(sendAll(peerFd, payload, progress), drainAll(conn.inbound, payload.length, progress)).map { case (_, got) =>
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
            val handle = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
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

    // kyo-pod bounds a wait on onClosing with Async.timeout and kyo-http reads onClosing.done() as "the connection is closing", so a waiter
    // that gives up must not settle the signal for every other observer: awaiting a fiber links the awaiter's interrupt to it.
    "a waiter that gives up on onClosing does not complete it" in {
        assumePoller()
        val real = PollerIoDriver.init()
        val spy  = new RecordingIoDriver(real)
        discard(spy.start())
        PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
            val handle = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
            val conn   = Connection.init(handle, spy, channelCapacity = 8)
            conn.start()
            Async.race(conn.onClosing.safe.get.map(_ => "closing"), Kyo.lift("gave up")).map { winner =>
                val settled = conn.onClosing.done()
                val open    = conn.isOpen
                conn.close()
                spy.close()
                discard(sock.close(peerFd))
                assert(winner == "gave up")
                assert(!settled, "a waiter giving up on onClosing must leave it pending while the connection is open")
                assert(open, "the connection must still be open")
            }
        }
    }

    // The production trigger: a peer FIN drives the ReadPump to EOF/teardown, which reaches closeFn and completes onClosing.
    "onClosing completes on a peer-driven ReadPump teardown" in {
        assumePoller()
        val real = PollerIoDriver.init()
        val spy  = new RecordingIoDriver(real)
        discard(spy.start())
        PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
            val handle = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
            val conn   = Connection.init(handle, spy, channelCapacity = 8)
            conn.start()
            discard(sock.close(peerFd)) // peer FIN -> ReadPump EOF -> teardown -> closeFn
            conn.onClosing.safe.get.andThen {
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
            val handle = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
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
