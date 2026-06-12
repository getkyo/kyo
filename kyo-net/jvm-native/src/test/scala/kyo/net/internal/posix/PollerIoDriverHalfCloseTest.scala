package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.scheduler.IOPromise

/** Correctness guard for the orderly half-close (peer `shutdown(SHUT_WR)`) path of [[PollerIoDriver]] on a real epoll / kqueue poller.
  *
  * kqueue reports a peer half-close by setting `EV_EOF` on the `EVFILT_READ` event, and there may still be buffered bytes in the socket
  * receive buffer when it does (kqueue(2): "It is possible for EOF to be returned while there is still data pending in the socket buffer").
  * The kqueue backend maps `EV_EOF` to `PollFlags.Error` (KqueuePollerBackend.scala:122, :155), so a half-close-with-buffered-data event
  * carries BOTH `PollFlags.Read` (the filter is EVFILT_READ) and `PollFlags.Error` (the EV_EOF bit). The risk this test pins: a regression
  * that routes such an event to `dispatchError` (which fails the pending read with `Closed`) instead of letting `dispatchRead` drain the
  * buffered bytes first and then deliver an empty-Span EOF. On epoll the same orderly close arrives as `EPOLLIN` delivering the data, then a
  * zero-byte read; both backends must therefore deliver every buffered byte before EOF and surface EOF as an empty Span, never a `Closed`.
  *
  * The peer sends a known payload and then half-closes for writing (the data and the FIN/EOF are both queued on the accepted side before the
  * reader runs), so the standing read observes the buffered bytes and the EOF in one orderly sequence. The assertions are: every payload byte
  * is delivered in order, AND the terminal read is an empty Span (Success), not a `Closed` failure.
  *
  * Built on the same real loopback + real epoll/kqueue infrastructure as PollerIoDriverStandingReadTest; the standing-read pattern mirrors
  * the transport's `ReadPump` (deliver a chunk, re-arm the next read). Deterministic: the payload and the half-close are both issued before
  * the reader is started, and the bounded await resolves on the real EOF rather than any timer.
  */
class PollerIoDriverHalfCloseTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PollerIoDriver needs epoll (Linux) or kqueue (macOS/BSD)")

    private def sock = Ffi.load[SocketBindings]

    /** Send `bytes` on `fd` to completion, looping past short writes (the test payload fits the kernel buffer). */
    private def sendAll(fd: Int, bytes: Array[Byte])(using Frame): Unit < Async =
        Loop(0) { sent =>
            if sent >= bytes.length then Loop.done(())
            else
                val rest = java.util.Arrays.copyOfRange(bytes, sent, bytes.length)
                val buf  = Buffer.fromArray[Byte](rest)
                Sync.ensure(Sync.defer(buf.close())) {
                    sock.send(fd, buf, rest.length.toLong, PosixConstants.MSG_NOSIGNAL).safe.get.map { r =>
                        val n = r.value.toInt
                        if n <= 0 then Loop.done(())
                        else Loop.continue(sent + n)
                    }
                }
        }
    end sendAll

    /** A standing reader that accumulates every delivered chunk and records the terminal outcome on the first non-data read: an empty Span
      * completes `done` with [[EofSeen]] (orderly close, correct), a `Closed` failure completes it with [[ClosedSeen]] (the regression). It
      * re-arms after every chunk, mirroring the transport's `ReadPump`. The result type lets the assertion distinguish "all bytes then EOF"
      * from "all bytes then a spurious Closed".
      */
    final private class HalfCloseReader(
        driver: PollerIoDriver,
        handle: PosixHandle,
        acc: java.io.ByteArrayOutputStream,
        done: Promise.Unsafe[String, Any]
    ) extends IOPromise[Closed, Span[Byte]]:

        private val self: Promise.Unsafe[Span[Byte], Abort[Closed]] =
            this.asInstanceOf[Promise.Unsafe[Span[Byte], Abort[Closed]]]

        def start()(using AllowUnsafe, Frame): Unit = driver.awaitRead(handle, self)

        override protected def onComplete(): Unit =
            import AllowUnsafe.embrace.danger
            poll() match
                case Present(Result.Success(bytes)) =>
                    if bytes.isEmpty then done.completeDiscard(Result.succeed(HalfCloseReader.EofSeen))
                    else
                        acc.write(bytes.toArrayUnsafe)
                        if becomeAvailable() then driver.awaitRead(handle, self)
                        else done.completeDiscard(Result.succeed(HalfCloseReader.BecomeAvailableFailed))
                case Present(Result.Failure(_: Closed)) => done.completeDiscard(Result.succeed(HalfCloseReader.ClosedSeen))
                case Present(Result.Panic(t))           => done.completeDiscard(Result.panic(t))
                case Absent                             => done.completeDiscard(Result.succeed(HalfCloseReader.NoResult))
            end match
        end onComplete
    end HalfCloseReader

    private object HalfCloseReader:
        val EofSeen: String               = "eof"
        val ClosedSeen: String            = "closed"
        val BecomeAvailableFailed: String = "becomeAvailable-failed"
        val NoResult: String              = "no-result"
    end HalfCloseReader

    "PollerIoDriver orderly half-close" - {
        "buffered bytes are delivered in full before EOF on a peer half-close, never as a Closed failure (8c)" in {
            assumePoller()
            val driver = PollerIoDriver.init(kyo.net.TransportConfig.default)
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    // A known payload, distinct per index so reordering or truncation is caught byte-for-byte.
                    val payload = Array.tabulate[Byte](4096)(i => (i % 251).toByte)
                    // Queue the data, THEN half-close the write side: the kernel delivers the buffered bytes on the accepted side and then an
                    // EOF (kqueue: EVFILT_READ + EV_EOF + data; epoll: EPOLLIN then a zero-byte read).
                    sendAll(client, payload).map { _ =>
                        PosixTestSockets.halfClose(sock, client)
                        val acc  = new java.io.ByteArrayOutputStream
                        val done = Promise.Unsafe.init[String, Any]()
                        val r    = new HalfCloseReader(driver, acceptedH, acc, done)
                        r.start()
                        Abort.run[Timeout](Async.timeout(10.seconds)(done.safe.get)).map { outcome =>
                            driver.closeHandle(acceptedH)
                            discard(sock.close(client))
                            outcome match
                                case Result.Success(HalfCloseReader.EofSeen) =>
                                    assert(
                                        acc.toByteArray.toList == payload.toList,
                                        s"buffered bytes not delivered in full before EOF: got ${acc.size()} of ${payload.length} bytes"
                                    )
                                case Result.Success(HalfCloseReader.ClosedSeen) =>
                                    fail(
                                        s"half-close surfaced Closed after ${acc.size()} of ${payload.length} bytes instead of an " +
                                            "empty-Span EOF (an EV_EOF -> Error event was routed to dispatchError)"
                                    )
                                case Result.Success(other) => fail(s"unexpected reader outcome: $other after ${acc.size()} bytes")
                                case Result.Failure(_: Timeout) =>
                                    fail(s"half-close read stalled after ${acc.size()} of ${payload.length} bytes (no EOF delivered)")
                                case other => fail(s"unexpected outcome: $other")
                            end match
                        }
                    }
                }
            }
        }
    }

end PollerIoDriverHalfCloseTest
