package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome
import kyo.scheduler.IOPromise

/** Reproduction + regression guard for a LOST server-side read re-arm in [[PollerIoDriver]] under back-to-back reads on one fd.
  *
  * On epoll every readiness event arrives under `EPOLLONESHOT`: the kernel disables the whole fd when an event fires, and the poll loop's
  * `rearmSurvivors` additionally clears the fired direction's interest from the backend's `desired` map. The fired read is then expected to be
  * re-armed by the consumer's NEXT `awaitRead` (the standing-read model the `ReadPump` drives: deliver a chunk, then immediately request the
  * next read). If that re-arm is ever reordered behind, or lost relative to, the `rearmSurvivors` clear, the fd is left with no read interest
  * while data is sitting in the socket, and no further event ever fires: a permanent freeze with unread bytes (the kyo-http TLS-under-load
  * deadlock symptom).
  *
  * This leaf drives the REAL [[PollerIoDriver]] over a real loopback pair (epoll on Linux, kqueue on macOS/BSD). It mirrors the `ReadPump`
  * standing-read exactly with a reused [[IOPromise]] that, on every completion, re-arms the next read via `awaitRead`, and asserts that a burst
  * of N back-to-back peer writes is delivered in full and in order. An epoll path that lost a re-arm to the `rearmSurvivors` clear
  * would strand one of the writes and time out the bounded await for the expected byte count.
  */
class PollerIoDriverStandingReadTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PollerIoDriver needs epoll (Linux) or kqueue (macOS/BSD)")

    private def sock = Ffi.load[SocketBindings]

    /** Build a connected loopback pair, both ends non-blocking once connected (the driver's contract). */
    private def loopbackPair()(using Frame, kyo.test.AssertScope): (Int, Int) < Async =
        val server = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(a.close())) {
            assert(sock.bind(server, a, l).value == 0)
            assert(sock.listen(server, 16).value == 0)
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

    /** Send `bytes` on `fd` to completion, looping past short writes (the kernel buffer is ample for the test payloads). */
    private def sendAll(fd: Int, bytes: Array[Byte])(using Frame): Unit < Async =
        Loop(0) { sent =>
            if sent >= bytes.length then Loop.done(())
            else
                val rest = java.util.Arrays.copyOfRange(bytes, sent, bytes.length)
                val buf  = Buffer.fromArray[Byte](rest)
                Sync.ensure(Sync.defer(buf.close())) {
                    sock.send(fd, buf, rest.length.toLong, PosixConstants.MSG_NOSIGNAL).safe.get.map { r =>
                        val n = r.value.toInt
                        if n <= 0 then Loop.done(()) // EAGAIN on a non-blocking send is not expected at these sizes; stop rather than spin.
                        else Loop.continue(sent + n)
                    }
                }
        }
    end sendAll

    /** A standing-read driver over `handle`, mirroring [[kyo.net.internal.transport.ReadPump]]: a single reused [[IOPromise]] that, on each
      * completion with bytes, accumulates them and immediately re-arms the next read via `awaitRead`. Completes `done` once `expected` bytes
      * have been accumulated, or fails it on EOF / error before then.
      */
    final private class StandingReader(
        driver: PollerIoDriver,
        handle: PosixHandle,
        expected: Int,
        acc: java.io.ByteArrayOutputStream,
        done: Promise.Unsafe[Unit, Abort[Closed]]
    ) extends IOPromise[Closed, ReadOutcome]:

        private val self: Promise.Unsafe[ReadOutcome, Abort[Closed]] =
            this.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]]

        def start()(using AllowUnsafe, Frame): Unit = driver.awaitRead(handle, self)

        override protected def onComplete(): Unit =
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            poll() match
                case Present(Result.Success(ReadOutcome.Bytes(bytes))) =>
                    acc.write(bytes.toArrayUnsafe)
                    if acc.size() >= expected then done.completeDiscard(Result.succeed(()))
                    else if becomeAvailable() then driver.awaitRead(handle, self)
                    else done.completeDiscard(Result.fail(Closed("StandingReader", summon[Frame], "becomeAvailable failed")))
                case Present(Result.Success(ReadOutcome.PeerFin | ReadOutcome.LocalShutdown | ReadOutcome.CleanClose)) =>
                    // EOF before we got everything: surface as a failure so the test's await resolves rather than hangs.
                    done.completeDiscard(Result.fail(Closed("StandingReader", summon[Frame], "EOF before expected bytes")))
                case Present(Result.Success(_)) =>
                    done.completeDiscard(Result.fail(Closed("StandingReader", summon[Frame], "EOF before expected bytes")))
                case Present(Result.Failure(c: Closed)) => done.completeDiscard(Result.fail(c))
                case Present(Result.Panic(t))           => done.completeDiscard(Result.panic(t))
                case Absent => done.completeDiscard(Result.fail(Closed("StandingReader", summon[Frame], "no result")))
            end match
        end onComplete
    end StandingReader

    "PollerIoDriver standing read on one fd" - {
        "a burst of back-to-back peer writes is all delivered to a re-arming standing read (no lost re-arm)" in {
            assumePoller()
            val driver = PollerIoDriver.init()
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    // Many small back-to-back messages: each forces a fresh readiness event under EPOLLONESHOT, so each exercises the
                    // rearmSurvivors-clear followed by the standing read's awaitRead re-arm. A lost re-arm strands a message.
                    val count   = 200
                    val msgSize = 8
                    val total   = count * msgSize
                    val acc     = new java.io.ByteArrayOutputStream
                    val done    = Promise.Unsafe.init[Unit, Abort[Closed]]()
                    val reader  = new StandingReader(driver, acceptedH, total, acc, done)
                    reader.start()
                    // Blast all messages from the peer back-to-back. The reader must re-arm after every delivered chunk and collect all bytes.
                    Loop(0) { i =>
                        if i >= count then Loop.done(())
                        else
                            val payload = Array.tabulate[Byte](msgSize)(j => (i + j).toByte)
                            sendAll(client, payload).andThen(Loop.continue(i + 1))
                    }.andThen {
                        Abort.run[Timeout | Closed](Async.timeout(10.seconds)(done.safe.get)).map { outcome =>
                            driver.closeHandle(acceptedH)
                            discard(sock.close(client))
                            outcome match
                                case Result.Success(()) =>
                                    assert(acc.size() == total, s"collected ${acc.size()} bytes, expected $total")
                                case Result.Failure(_: Timeout) =>
                                    fail(s"lost re-arm: standing read stalled after ${acc.size()} of $total bytes")
                                case other => fail(s"unexpected standing-read outcome: $other")
                            end match
                        }
                    }
                }
            }
        }
    }

end PollerIoDriverStandingReadTest
