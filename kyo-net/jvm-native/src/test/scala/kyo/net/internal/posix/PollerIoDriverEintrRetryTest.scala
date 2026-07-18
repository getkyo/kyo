package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Reproduction + regression guard for finding #13 (POSIX recv/send EINTR handling, CWE-252 mishandled return value) in [[PollerIoDriver]].
  *
  * On a non-blocking socket a recv or send can return -1 with errno `EINTR` when a signal is delivered before any byte is transferred. POSIX
  * says to retry the call: no data was lost, the socket is unchanged. The accept path already retries `EINTR` in place, bounded
  * (`PosixTransport.acceptAll`). The recv/send dispatch path did not: `isWouldBlock` matched only `EAGAIN`/`EWOULDBLOCK`, so an `EINTR` fell
  * into the hard-error branch and failed the read/write promise `Closed`, dropping a healthy connection.
  *
  * A real mid-syscall signal is not deterministically injectable, so the interruption is reproduced at the bindings seam:
  * [[RecordingSocketBindings.injectRecvEintrOnce]] / [[RecordingSocketBindings.injectSendEintrOnce]] make the next recv/send return
  * `(-1, EINTR)` exactly once, then clear themselves. The real socket still holds its data, so the driver's retry then delivers the bytes /
  * completes the send for real. Without the retry these leaves would FAIL for the right reason: the injected `EINTR` fails the read/write `Closed`.
  *
  * Gate: `PosixTestSockets.assumePoller()` (real loopback pair, real epoll/kqueue). JVM+Native, where the poller runs.
  *
  * Anti-flakiness: the recv leaf writes the peer bytes and arms the injection BEFORE registering read interest, so the very first recvNow on
  * the fd (after the read-ready event) hits the one-shot injection; the retried recvNow reads the real bytes. The write leaf arms the injection
  * before the single `driver.write`, so the first send hits it; the retried send writes the bytes the peer then drains. `Async.timeout` is only
  * the deadlock ceiling. No sleep, no busy-spin.
  */
class PollerIoDriverEintrRetryTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Send the whole array to `fd` in one or more send calls until every byte is accepted (the kernel buffer is ample for these payloads). */
    private def sendAll(fd: Int, bytes: Array[Byte])(using Frame): Unit < Async =
        Loop(bytes) { rest =>
            if rest.isEmpty then Loop.done(())
            else
                val buf = Buffer.fromArray[Byte](rest)
                Sync.ensure(Sync.defer(buf.close())) {
                    sock.send(fd, buf, rest.length.toLong, PosixConstants.MSG_NOSIGNAL).safe.get.map { r =>
                        val n = r.value.toInt
                        if n <= 0 then Loop.done(()) // EAGAIN on a non-blocking send is not expected at these sizes; stop rather than spin.
                        else if n >= rest.length then Loop.done(())
                        else Loop.continue(rest.drop(n))
                    }
                }
        }
    end sendAll

    /** Drain `want` bytes from `fd` via recvNow into one running accumulator (the peer is a plain socket, no driver involved). */
    private def recvAll(fd: Int, want: Int)(using AllowUnsafe): Array[Byte] =
        val out = new java.io.ByteArrayOutputStream()
        val buf = Buffer.alloc[Byte](65536)
        try
            while out.size() < want do
                val r = sock.recvNow(fd, buf, 65536L, PosixConstants.MSG_DONTWAIT)
                val n = r.value.toInt
                if n > 0 then out.write(Buffer.copyToArray[Byte](buf, 0, n))
            end while
            out.toByteArray
        finally buf.close()
        end try
    end recvAll

    "PollerIoDriver EINTR retry" - {
        "a recv interrupted by EINTR is retried, delivering the data, not failed Closed" in {
            PosixTestSockets.assumePoller()
            PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
                val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                val real     = PollerBackend.default()
                val pollerFd = real.create()
                val backend  = RecordingPollerBackend(real)
                val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
                val handle   = PosixHandle.socket(acceptedFd, PosixHandle.DefaultReadBufferSize, Absent)

                discard(driver.start())
                val payload = Array.tabulate[Byte](16)(i => (i + 1).toByte)

                for
                    // Peer writes the bytes first; they sit in acceptedFd's recv buffer waiting for a read.
                    _ <- sendAll(clientFd, payload)
                    // Arm the one-shot EINTR injection BEFORE registering read interest, so the very first recvNow the driver issues for this fd
                    // returns the injected (-1, EINTR). The retried recvNow (injection now cleared) reads the real bytes.
                    _           = spy.injectRecvEintrOnce.set(true)
                    readPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    _           = driver.awaitRead(handle, readPromise)
                    // Bounded so the test fails fast rather than hanging if the read is never delivered.
                    outcome <- Abort.run[Timeout | Closed](Async.timeout(5.seconds)(readPromise.safe.get))
                    _ <- Sync.defer {
                        driver.closeHandle(handle)
                        driver.close()
                        PosixTestSockets.closePeerForEof(spy, clientFd)
                    }
                yield outcome match
                    case Result.Success(ReadOutcome.Bytes(got)) =>
                        assert(
                            got.toArray.toList == payload.toList,
                            s"the retried recv must deliver the full payload; got ${got.toArray.toList}"
                        )
                        assert(
                            spy.injectRecvEintrOnce.get() == false,
                            "the one-shot EINTR injection must have fired (and a retry consumed it)"
                        )
                    case Result.Success(other) =>
                        fail(s"expected ReadOutcome.Bytes, got $other")
                    case Result.Failure(_: Closed) =>
                        fail("EINTR on recv was treated as a hard error and failed the read Closed; it must be retried (POSIX recv)")
                    case Result.Failure(_: Timeout) =>
                        fail("the read hung: an EINTR retry never delivered the data")
                    case other => fail(s"unexpected read outcome: $other")
                end for
            }
        }

        "a send interrupted by EINTR is retried, completing the write, not failed Error" in {
            PosixTestSockets.assumePoller()
            PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
                val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                val real     = PollerBackend.default()
                val pollerFd = real.create()
                val backend  = RecordingPollerBackend(real)
                val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
                val handle   = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)

                discard(driver.start())
                val payload = Array.tabulate[Byte](16)(i => (i + 1).toByte)

                for
                    // Arm the one-shot EINTR injection BEFORE the single write, so the first send the driver issues returns (-1, EINTR) with no
                    // bytes sent. The driver must retry the send; the retried send writes the bytes the peer (acceptedFd) then drains.
                    _ = spy.injectSendEintrOnce.set(true)
                    result <- Sync.defer(driver.write(handle, Span.fromUnsafe(payload), 0))
                    // The retried send must complete the write (Done), not bail Error on the interrupted call.
                    _ = assert(
                        result == WriteResult.Done,
                        s"EINTR on send was treated as a hard error and bailed $result; it must be retried (POSIX send)"
                    )
                    _ = assert(
                        spy.injectSendEintrOnce.get() == false,
                        "the one-shot EINTR injection must have fired (and a retry consumed it)"
                    )
                    // Confirm the bytes actually reached the peer: a true retry resent the full payload.
                    got = recvAll(acceptedFd, payload.length)
                    _ <- Sync.defer {
                        driver.closeHandle(handle)
                        driver.close()
                        PosixTestSockets.closePeerForEof(spy, acceptedFd)
                    }
                yield assert(got.toList == payload.toList, s"the retried send must deliver the full payload to the peer; got ${got.toList}")
                end for
            }
        }
    }

end PollerIoDriverEintrRetryTest
