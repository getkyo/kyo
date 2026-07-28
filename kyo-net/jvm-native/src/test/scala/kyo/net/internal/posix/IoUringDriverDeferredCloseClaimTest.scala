package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** Reproduce-first regression for the io_uring stale `shutdown(SHUT_RD)` on a recycled fd: issuing [[IoUringDriver.registerDeferredClose]]'s deferred
  * shutdown UNCONDITIONALLY whenever an in-flight recv races a close, with no check that `handle` still owns the fd
  * number, races fd recycling. A concurrently finishing transport-path closer (`closeUnwiredHandle`'s connect-phase arm, or any closer that wins the claim and
  * closes immediately) can already have won [[PosixHandle.claimFdClose]] and closed the fd directly while this handle's recv SQE is still kernel-owned (`close(fd)`
  * alone does not complete an in-flight io_uring recv: the kernel holds its own file reference). The fd NUMBER is immediately free for reuse
  * (fd-table release is independent of the underlying file's refcount), so the kernel routinely hands it straight back to a fresh, unrelated
  * connection. A stale, unguarded shutdown would then land on that VICTIM's fd, injecting a spurious EOF into a socket this handle never owned.
  *
  * `registerDeferredClose` wins `claimFdClose` itself before issuing the shutdown (winning proves the fd is still owned and
  * un-recycled); losing it proves a racing closer already owns the fd, so the shutdown is skipped. [[PosixHandle.markDeferredFdClose]] /
  * [[PosixHandle.consumeDeferredFdClose]] then let the later `closeNow` still run the real `close(fd)` despite `claimFdClose` being spent by
  * the shutdown winner.
  *
  * Anti-flakiness: awaits `driver.hasInFlightRead(handle)` becoming true (the recv is genuinely kernel-owned) before simulating the race, and
  * a FIFO-ordered marker op (submitted right after `closeHandle`, so it runs strictly after `registerDeferredClose` on the single reap
  * carrier) before inspecting the spy, instead of a sleep. The "fd recycled" step drives a real second `socket()`/`connect()`/`accept()`
  * sequence and asserts Linux's lowest-free-fd allocator actually reused the closed fd number rather than assuming it; if a concurrently
  * running, unrelated test class raced that narrow window and stole the number first, the leaf cancels rather than reporting a false failure
  * (the same visible-cancel style [[PosixTestSockets.assumeUring]] uses for host capability gates).
  */
class IoUringDriverDeferredCloseClaimTest extends Test:

    import AllowUnsafe.embrace.danger

    "IoUringDriver deferred close claim" - {

        "skips the deferred shutdown(SHUT_RD) once a racing transport-path close already claimed the fd, so a recycled fd is never corrupted" in {
            PosixTestSockets.assumeUring()
            given Frame   = Frame.internal
            val depth     = math.max(256, kyo.net.ioPoolSize() * 64)
            val realUring = Ffi.load[IoUringBindings]
            val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
            val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
            if rc != 0 then
                realRing.close()
                throw Closed("IoUringDriverDeferredCloseClaimTest", summon[Frame], s"queue_init failed: rc=$rc")
            val spy    = RecordingSocketBindings(Ffi.load[SocketBindings])
            val driver = TestDrivers.forBindings(realUring, realRing, spy)
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair(spy).map { case (client, accepted) =>
                    val handle = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    handle.driver = driver
                    Sync.ensure(Sync.defer(discard(spy.close(client)))) {
                        val readPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        driver.awaitRead(handle, readPromise)
                        assertEventually(Sync.defer(driver.hasInFlightRead(handle))).map { _ =>
                            // Simulate an immediate-close transport-path closer: win claimFdClose and close the fd directly (bypassing
                            // the driver), leaving the recv SQE kernel-owned. The fd number is immediately free for reuse.
                            assert(handle.claimFdClose(), "test setup: nothing else has claimed the fd yet")
                            spy.close(accepted).safe.get.map { _ =>
                                PosixTestSockets.loopbackPair(spy).map { case (client2, accepted2) =>
                                    val recycledToClient   = client2 == accepted
                                    val recycledToAccepted = accepted2 == accepted
                                    assume(
                                        recycledToClient || recycledToAccepted,
                                        s"fd $accepted was not recycled by the fresh pair ($client2, $accepted2); the lowest-free-fd " +
                                            "assumption did not hold (likely unrelated concurrent fd churn)"
                                    )
                                    val victimFd = if recycledToClient then client2 else accepted2
                                    val peerFd   = if recycledToClient then accepted2 else client2

                                    val shutdownCallsBefore = spy.shutdownCalls.size()
                                    driver.closeHandle(handle)
                                    // FIFO-ordered barrier: submitEngineOp ops run strictly in submission order on the single reap carrier, so
                                    // this one (submitted right after closeHandle's own submitEngineOp) only completes once
                                    // registerDeferredClose has already run to completion -- a deterministic "the op under test has run" signal,
                                    // not a sleep.
                                    val barrier = Promise.Unsafe.init[Unit, Abort[Closed]]()
                                    driver.submitEngineOp(() => barrier.completeDiscard(Result.succeed(())))
                                    barrier.safe.get.map { _ =>
                                        import scala.jdk.CollectionConverters.*
                                        val staleShutdowns = spy.shutdownCalls.asScala.toList.drop(shutdownCallsBefore).filter {
                                            case (fd, how) => fd == victimFd && how == PosixConstants.SHUT_RD
                                        }
                                        assert(
                                            staleShutdowns.isEmpty,
                                            s"registerDeferredClose issued a stale shutdown(SHUT_RD) on the recycled fd $victimFd: $staleShutdowns"
                                        )
                                        // The victim connection must still round-trip a byte: no spurious EOF was injected into it.
                                        val probe = Array[Byte](7)
                                        assert(spy.sendNow(peerFd, Buffer.fromArray(probe), 1, 0).value == 1)
                                        val recvBuf = Buffer.alloc[Byte](1)
                                        Sync.ensure(Sync.defer(recvBuf.close())) {
                                            assertEventually(Sync.defer(spy.recvNow(victimFd, recvBuf, 1, 0).value == 1)).map { _ =>
                                                assert(
                                                    recvBuf.get(0) == 7.toByte,
                                                    "the victim connection must deliver the real byte, not a stale EOF"
                                                )
                                                discard(spy.close(peerFd).safe.get)
                                                discard(spy.close(victimFd).safe.get)
                                                succeed
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end IoUringDriverDeferredCloseClaimTest
