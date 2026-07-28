package kyo.net.internal.posix

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Deterministic regression coverage for the fd-close ordering guarantee: the winner of `claimFdClose` must never close the fd directly while another
  * carrier can still be mid-syscall on it under a live [[PosixHandle.beginWrite]] / [[PosixHandle.beginDispatch]] hold.
  * If `PollerIoDriver.closeHandle`'s plaintext branch closed `sockets.close(fd)` synchronously on the claiming carrier the instant it won the
  * claim, regardless of whether a pump write (`writeRaw`, guarded by `beginWrite`) or a poll-carrier read dispatch (`dispatchRead`, guarded
  * by `beginDispatch`) was still inside its send/recv syscall on that same fd, then under load the kernel could recycle a closed fd number to a
  * concurrently-connecting leaf before that in-flight syscall completes, corrupting an unrelated connection (a late send injects stale bytes
  * into it, or a late recv steals its bytes). The claim winner instead shuts the fd down immediately (safe: winning the claim proves it
  * is still owned) and defers the real `close(fd)` to [[PosixHandle.freeResources]] via `fdCloseSink`, the same exactly-once, zero-holders
  * point that already frees the engine and buffers, so the fd number inherits that same guarantee.
  *
  * Both leaves force the exact interleaving through `RecordingSocketBindings`' `onSend` / `onRecvNow` hooks, which fire synchronously,
  * before delegating to the real syscall, from inside the guarded write/dispatch call: `closeHandle` is invoked from the hook itself, so
  * the race is deterministic (one interleaving, not a probabilistic one) rather than relying on timing.
  */
class PollerIoDriverCloseDuringIoTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    "PollerIoDriver close racing an in-flight guarded syscall" - {

        "closeHandle racing a write in flight under beginWrite defers the real close until the write releases the guard" in {
            PosixTestSockets.assumePoller()
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val backend  = PollerBackend.default()
            val pollerFd = backend.create()
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            discard(driver.start())
            PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                val handle               = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                val closeCountAtSendTime = new AtomicInteger(-1)
                spy.onSend = () =>
                    // beginWrite is already held by write() at this point (writeRaw calls sockets.send from inside it, before this hook's
                    // caller returns): the claimed close must shut the fd down and defer the real close, never run it here.
                    driver.closeHandle(handle)
                    closeCountAtSendTime.set(spy.closeCounts.getOrDefault(accepted, 0))
                val result = driver.write(handle, Span.fromUnsafe(Array[Byte](1, 2)), 0)
                discard(sock.close(client))
                driver.close()
                assert(
                    closeCountAtSendTime.get() == 0,
                    s"the real close(fd) must not run while the write's send syscall is still in flight, was ${closeCountAtSendTime.get()}"
                )
                assert(
                    spy.closeCounts.getOrDefault(accepted, 0) == 1,
                    s"the deferred close must run exactly once after the write releases the guard, counts=${spy.closeCounts}"
                )
                assert(
                    spy.shutdownCalls.contains((accepted, PosixConstants.SHUT_RDWR)),
                    s"the claim winner must shut the fd down (SHUT_RDWR) immediately, shutdownCalls=${spy.shutdownCalls}"
                )
                val shutdownIdx = spy.order.indexOf(s"shutdown($accepted)")
                val sendIdx     = spy.order.indexOf(s"send($accepted)")
                val closeIdx    = spy.order.indexOf(s"close($accepted)")
                assert(
                    shutdownIdx >= 0 && sendIdx > shutdownIdx && closeIdx > sendIdx,
                    s"expected shutdown, send, close in that order, was ${spy.order}"
                )
                // The write itself observes the shut-down fd (send after a local SHUT_RDWR fails), not a hang or a silently-dropped byte count.
                assert(result != WriteResult.Done, s"expected the write to observe the shutdown (not a clean Done), got $result")
            }
        }

        "closeHandle racing a read dispatch in flight under beginDispatch defers the real close until the dispatch releases the guard" in {
            PosixTestSockets.assumePoller()
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val backend  = PollerBackend.default()
            val pollerFd = backend.create()
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            discard(driver.start())
            PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                val handle               = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                val closeCountAtRecvTime = new AtomicInteger(-1)
                spy.onRecvNow = (fd: Int) =>
                    // beginDispatch is already held by dispatchRead() at this point (recvNow is called from inside it, before this hook's
                    // caller returns): the claimed close must shut the fd down and defer the real close, never run it here.
                    driver.closeHandle(handle)
                    closeCountAtRecvTime.set(spy.closeCounts.getOrDefault(accepted, 0))
                val readPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                driver.awaitRead(handle, readPromise)
                // Make the fd readable from the client side so the poll loop's dispatchRead runs recvNow under beginDispatch, firing the hook.
                val wb = Buffer.fromArray[Byte](Array[Byte](7))
                Sync.ensure(Sync.defer(wb.close())) {
                    sock.send(client, wb, 1L, PosixConstants.MSG_NOSIGNAL).safe.get
                }.andThen {
                    // Await the deferred real close actually running (asynchronously, on the poll-loop carrier), not a fixed sleep.
                    spy.closed(accepted).safe.get.map { _ =>
                        discard(sock.close(client))
                        driver.close()
                        assert(
                            closeCountAtRecvTime.get() == 0,
                            s"the real close(fd) must not run while the read dispatch's recv syscall is still in flight, was ${closeCountAtRecvTime.get()}"
                        )
                        assert(
                            spy.closeCounts.getOrDefault(accepted, 0) == 1,
                            s"the deferred close must run exactly once after the dispatch releases the guard, counts=${spy.closeCounts}"
                        )
                        assert(
                            spy.shutdownCalls.contains((accepted, PosixConstants.SHUT_RDWR)),
                            s"the claim winner must shut the fd down (SHUT_RDWR) immediately, shutdownCalls=${spy.shutdownCalls}"
                        )
                        val shutdownIdx = spy.order.indexOf(s"shutdown($accepted)")
                        val recvIdx     = spy.order.indexOf(s"recv($accepted)")
                        val closeIdx    = spy.order.indexOf(s"close($accepted)")
                        assert(
                            shutdownIdx >= 0 && recvIdx > shutdownIdx && closeIdx > recvIdx,
                            s"expected shutdown, recv, close in that order, was ${spy.order}"
                        )
                    }
                }
            }
        }
    }

end PollerIoDriverCloseDuringIoTest
