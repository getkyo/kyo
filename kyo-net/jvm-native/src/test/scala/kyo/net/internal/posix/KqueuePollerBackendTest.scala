package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test

/** Behavior of [[KqueuePollerBackend]] over a real kqueue (macOS/BSD), driven through the real [[PollerBackend.default]] singleton with real
  * loopback fds and the real `kevent` syscall (no injected bindings).
  *
  * A read interest registered on an fd fires in `poll` once that fd becomes readable, and does not fire while the fd has nothing to read. These
  * are the readiness guarantees the [[PollerIoDriver]] read path depends on.
  *
  * Gate: cancels off macOS/BSD where kqueue is absent. Deterministic: the readable leaf writes a byte before polling so the event is already
  * pending (kevent returns it without waiting); the not-readable leaf polls with a short bounded timeout and asserts zero events.
  */
class KqueuePollerBackendTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    private def assumeKqueue(): Unit =
        if !PosixConstants.isMacOrBsd then cancel("kqueue is macOS/BSD-only")

    "KqueuePollerBackend over a real kqueue" - {

        "a registered read interest fires in poll once the fd is readable" in {
            assumeKqueue()
            val backend  = PollerBackend.default()
            val pollerFd = backend.create()
            assert(pollerFd >= 0, s"kqueue create failed: $pollerFd")
            val scratch = backend.newPollScratch()
            PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                discard(backend.registerRead(pollerFd, accepted, accepted.toLong, scratch))
                // Make `accepted` readable, then poll: the readiness event is already pending so kevent returns it without waiting.
                // Pass the kqueue changelist from scratch (no pending changes at this call site; nChanges is 0).
                assert(sock.sendNow(client, Buffer.fromArray[Byte](Array[Byte](7)), 1L, 0).value == 1L)
                val kq1 = scratch.kqueueData.get
                backend.poll(pollerFd, 1000, kq1.changelistBuf, kq1.nChanges, scratch).safe.get.map { n =>
                    val firedFds = (0 until n).map(scratch.fds(_)).toList
                    scratch.close()
                    discard(sock.close(client))
                    discard(sock.close(accepted))
                    backend.close(pollerFd)
                    assert(n >= 1, s"expected the readable fd to fire, got $n events")
                    assert(firedFds.contains(accepted), s"expected poll to report the registered fd $accepted as ready, got $firedFds")
                }
            }
        }

        "a change batch larger than the changelist capacity flushes instead of overrunning the buffer" in {
            assumeKqueue()
            val backend  = PollerBackend.default()
            val pollerFd = backend.create()
            assert(pollerFd >= 0, s"kqueue create failed: $pollerFd")
            val scratch = backend.newPollScratch()
            PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                // The changelist batches interest changes until the next poll submits them, but it holds only MaxEvents slots. A single drain
                // that produces more than that used to encode past the buffer and fail the cycle with IndexOutOfBoundsException, which the
                // driver's containment then turned into a torn-down driver and stalled I/O. Two ways to reach it, both under ordinary load:
                // many fds re-arming in one cycle, and terminalTeardown's drain, which has no following poll to flush the batch at all.
                val batched = backend.MaxEvents * 3
                (0 until batched).foreach(_ => discard(backend.registerRead(pollerFd, accepted, accepted.toLong, scratch)))
                val kq = scratch.kqueueData.get
                assert(
                    kq.nChanges <= backend.MaxEvents,
                    s"the changelist must never hold more than ${backend.MaxEvents} entries, got ${kq.nChanges}"
                )
                // The registrations still took effect across the flushes: make the fd readable and confirm it fires.
                assert(sock.sendNow(client, Buffer.fromArray[Byte](Array[Byte](7)), 1L, 0).value == 1L)
                backend.poll(pollerFd, 1000, kq.changelistBuf, kq.nChanges, scratch).safe.get.map { n =>
                    val firedFds = (0 until n).map(scratch.fds(_)).toList
                    scratch.close()
                    discard(sock.close(client))
                    discard(sock.close(accepted))
                    backend.close(pollerFd)
                    assert(n >= 1, s"expected the registered fd to fire after an oversized change batch, got $n events")
                    assert(firedFds.contains(accepted), s"expected poll to report $accepted as ready, got $firedFds")
                }
            }
        }

        "a registered read interest does not fire while the fd has nothing to read" in {
            assumeKqueue()
            val backend  = PollerBackend.default()
            val pollerFd = backend.create()
            assert(pollerFd >= 0, s"kqueue create failed: $pollerFd")
            val scratch = backend.newPollScratch()
            PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                discard(backend.registerRead(pollerFd, accepted, accepted.toLong, scratch))
                // Nothing is written to `client`, so `accepted` has no readable data: poll returns 0 within the bounded timeout.
                val kq2 = scratch.kqueueData.get
                backend.poll(pollerFd, 100, kq2.changelistBuf, kq2.nChanges, scratch).safe.get.map { n =>
                    scratch.close()
                    discard(sock.close(client))
                    discard(sock.close(accepted))
                    backend.close(pollerFd)
                    assert(n == 0, s"expected no readiness event while the fd has nothing to read, got $n")
                }
            }
        }
    }

end KqueuePollerBackendTest
