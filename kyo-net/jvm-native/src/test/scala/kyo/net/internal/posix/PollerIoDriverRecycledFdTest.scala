package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** No driver operation ever uses a freed fd: the two inherited recycled-fd defects, each driven white-box over a real driver.
  *
  *   - io_uring: a recv parked in `stalledSubmits` on a full submission queue must be failed Closed when the handle closes, NOT
  *     re-armed on the now-closed fd. If `closeNow` cleared only `stalledSends`, the reap loop would re-arm the parked recv on the closed fd
  *     (an EBADF, or a recv on the fd's recycled successor). `closeNow` drains `stalledSubmits` for the handle before the fd close.
  *   - poller: a stale `OpDeregister` whose fd was closed and recycled into a NEW handle must not evict the new handle's
  *     registration. An unconditional deregister would remove the fd's map entries and kernel filter regardless of owner. Each removal is gated on
  *     `activeFds(fd) == id`, so a deregister carrying a different generation than the live owner is skipped.
  *   - poller, the register-side re-arm race: the `ReadPump` always re-arms, so a closed handle can issue a dangling `awaitRead` that races the
  *     fd close and recycle. A registration whose fd was closed and recycled into a NEW handle must not (re-)claim it: an unconditional `applyRegistration`
  *     would apply every registration regardless of state, so the dead handle's dangling re-arm would overwrite the new owner's `activeFds`/`pendingReads` entry
  *     and re-arm the kernel under the dead id, stranding the new connection's read. The apply is gated on `!handle.isClosing()`.
  *
  * Pins that no driver operation ever uses a freed file descriptor, across the inherited recycled-fd paths: the io_uring stalled-submit close
  * path and both poller recycled-fd paths (the stale deregister and the register-side re-arm race).
  */
class PollerIoDriverRecycledFdTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Submit a marker engine op and return a promise that completes when the reap carrier runs it. submitEngineOp wakes the reap loop, so each
      * barrier forces a reap turn (which runs reArmStalledSubmits) even with no CQE pending; two in sequence give a base re-arm of the parked recv
      * its chance to submit a prep_recv on the closed fd, so the post-close recv count is settled deterministically without waiting on a CQE.
      */
    private def fifoBarrier(driver: IoUringDriver)(using AllowUnsafe): Promise.Unsafe[Unit, Any] =
        val p = Promise.Unsafe.init[Unit, Any]()
        driver.submitEngineOp(() => p.completeDiscard(Result.succeed(())))
        p
    end fifoBarrier

    /** Allocate a REAL io_uring ring at `depth`, wrap it in a recording spy, build a driver with its reap loop started, run `body`, then close the
      * driver. Mirrors IoUringRawWriteSqFullTest.
      */
    private def withRecordingDriver[A](depth: Int)(
        body: (IoUringDriver, RecordingIoUringBindings) => A < (Abort[Closed] & Async)
    )(using Frame): A < (Abort[Closed] & Async) =
        val realUring = Ffi.load[IoUringBindings]
        val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
        val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
        if rc != 0 then
            realRing.close()
            throw Closed("PollerIoDriverRecycledFdTest", summon[Frame], s"queue_init failed: rc=$rc")
        val recording = RecordingIoUringBindings(realUring, realRing)
        val driver    = TestDrivers.forBindings(recording, realRing)
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(body(driver, recording))
    end withRecordingDriver

    "a recv parked on SQ-full is failed Closed on close, never re-armed on the closed fd" in {
        PosixTestSockets.assumeUring()
        // Depth-1 ring, reap carrier pinned by a latch so the filler read (consumes the one SQE), the parked recv (get_sqe Absent -> stalledSubmits),
        // and the close all enqueue and drain in ONE pass before the wait submits anything. closeNow must drain stalledSubmits for the handle, so the
        // parked recv is failed Closed and never re-armed: exactly one prep_recv (the filler) ever reaches the ring.
        withRecordingDriver(1) { (drv, rec) =>
            PosixTestSockets.loopbackPair().map { case (fillerClient, fillerAccepted) =>
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val fillerH = PosixHandle.socket(fillerAccepted, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                    val handle  = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                    val gate    = new java.util.concurrent.CountDownLatch(1)
                    val pinIn   = Promise.Unsafe.init[Unit, Abort[Closed]]()
                    drv.submitEngineOp { () =>
                        pinIn.completeDiscard(Result.succeed(()))
                        gate.await()
                    }
                    pinIn.safe.get.map { _ =>
                        val fillerP = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        drv.awaitRead(fillerH, fillerP) // consumes the one SQE, stays in flight (no peer bytes)
                        val recvP = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        drv.awaitRead(handle, recvP) // get_sqe Absent -> parks in stalledSubmits
                        drv.closeHandle(handle)      // closeNow must drain stalledSubmits -> fail recvP Closed, NOT re-arm on the closed fd
                        gate.countDown()
                        Abort.run[Closed](recvP.safe.get).map { result =>
                            // Settle a couple reap turns so a base re-arm (reArmStalledSubmits) would have submitted a recv on the closed fd by now.
                            // Use engine-op barriers, not awaitReap: nothing is re-armed here so no CQE reaps, and an awaitReap would hang.
                            fifoBarrier(drv).safe.get.andThen(fifoBarrier(drv).safe.get).map { _ =>
                                drv.closeHandle(fillerH)
                                discard(sock.close(fillerClient))
                                discard(sock.close(client))
                                assert(result.isFailure, s"the parked recv must be failed Closed by the close-time drain, got $result")
                                assert(
                                    rec.recvLens.size() == 1,
                                    s"the closed handle's parked recv must NOT be re-armed on the closed fd; prep_recv count=${rec.recvLens.size()}"
                                )
                            }
                        }
                    }
                }
            }
        }.map(_ => succeed)
    }

    "a close between staging an interest and submitting the poll never registers a freed fd" in {
        if !PosixConstants.isMacOrBsd then cancel("kqueue is macOS/BSD-only")
        PosixTestSockets.loopbackPair().map { case (client, accepted) =>
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, sock)
            val handle   = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
            val entered  = Promise.Unsafe.init[Unit, Any]()
            val heldPoll = Promise.Unsafe.init[Int, Any]()
            backend.setPrePollLatch(entered)
            backend.setPrePollHold(heldPoll)
            // Queue the read before start so the held first poll owns a real staged registration.
            val read = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
            driver.awaitRead(handle, read)
            val stopped = driver.start()
            Scope.ensure {
                Sync.defer {
                    driver.closeHandle(handle)
                    driver.close()
                    heldPoll.completeDiscard(Result.succeed(0))
                }.andThen(stopped.safe.get).andThen(sock.close(client).safe.get.unit)
            }.andThen {
                entered.safe.get.map { _ =>
                    val scratch = backend.lastScratch
                    val kq      = scratch.kqueueData.get
                    assert(kq.nChanges == 1, s"expected the read registration to be staged before close, got ${kq.nChanges}")
                    // The driver is suspended on heldPoll, so it cannot drain the close command or touch scratch. Plaintext close runs its
                    // synchronous JVM/Native close path on this carrier. Submit the held batch here before allowing the driver to continue.
                    driver.closeHandle(handle)
                    real.poll(pollerFd, 0, kq.changelistBuf, kq.nChanges, scratch).safe.get.map { n =>
                        val errors = (0 until n).filter(i =>
                            scratch.ids(i) == handle.id.packed && (scratch.flags(i) & PollFlags.Error) != 0
                        ).map(i => KEvent.data(kq.eventsBuffer, i)).toList
                        heldPoll.completeDiscard(Result.succeed(n))
                        assert(errors == List.empty[Long], s"poll submitted an interest after its fd was freed: errno=$errors")
                    }
                }
            }
        }
    }

    "terminal teardown releases a registration staged after the final poll" in {
        if !PosixConstants.isMacOrBsd then cancel("kqueue is macOS/BSD-only")
        PosixTestSockets.loopbackPair().map { case (client, accepted) =>
            val real     = PollerBackend.default()
            val spy      = RecordingSocketBindings(sock)
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, real.create(), spy)
            val handle   = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
            val entered  = Promise.Unsafe.init[Unit, Any]()
            val heldPoll = Promise.Unsafe.init[Int, Any]()
            backend.setPrePollLatch(entered)
            backend.setPrePollHold(heldPoll)
            val stopped = driver.start()
            Scope.ensure {
                Sync.defer {
                    driver.closeHandle(handle)
                    driver.close()
                    heldPoll.completeDiscard(Result.succeed(0))
                }.andThen(stopped.safe.get).andThen(sock.close(client).safe.get.unit)
            }.andThen {
                entered.safe.get.map { _ =>
                    val read = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    driver.awaitRead(handle, read)
                    // The post-poll FIFO drain stages this read, then closes the handle and driver. No later poll can submit that batch,
                    // so terminal teardown must release its registration hold and let the socket's deferred physical close finish.
                    driver.submitEngineOp { () =>
                        driver.closeHandle(handle)
                        driver.close()
                    }
                    heldPoll.completeDiscard(Result.succeed(0))
                    stopped.safe.get.map { _ =>
                        assert(backend.registeredReadFds.contains(accepted))
                        assert(spy.closeCounts.getOrDefault(accepted, 0) == 1)
                        assert(handle.readBuffer.isClosed)
                    }
                }
            }
        }
    }

    "canceling a staged registration releases only its fd hold without another poll" in {
        if !PosixConstants.isMacOrBsd then cancel("kqueue is macOS/BSD-only")
        PosixTestSockets.loopbackPair().map { case (client, accepted) =>
            PosixTestSockets.loopbackPair().map { case (otherClient, otherAccepted) =>
                val real    = PollerBackend.default()
                val spy     = RecordingSocketBindings(sock)
                val backend = RecordingPollerBackend(real)
                val driver  = TestDrivers.forBackend(backend, real.create(), spy)
                val handle  = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                val other   = PosixHandle.socket(otherAccepted, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                Scope.ensure(Sync.defer {
                    driver.closeHandle(handle)
                    driver.closeHandle(other)
                    driver.close()
                }.andThen(sock.close(client).safe.get.unit).andThen(sock.close(otherClient).safe.get.unit)).andThen {
                    driver.awaitRead(handle, Promise.Unsafe.init[ReadOutcome, Abort[Closed]]())
                    driver.awaitRead(other, Promise.Unsafe.init[ReadOutcome, Abort[Closed]]())
                    driver.drainFifos()
                    assert(backend.registeredReadFds.contains(accepted))
                    assert(backend.registeredReadFds.contains(otherAccepted))

                    // Connection release withdraws its interests before requesting physical close. Once both commands have removed this fd's
                    // staged changes, an idle poll must not be needed to release its hold and complete close.
                    driver.cancel(handle)
                    driver.closeHandle(handle)
                    driver.drainFifos()
                    assert(spy.closeCounts.getOrDefault(accepted, 0) == 1)
                    assert(handle.readBuffer.isClosed)

                    // The other fd is still borrowed by its staged registration. Closing it cannot recycle it until its own deregistration drains.
                    driver.closeHandle(other)
                    assert(spy.closeCounts.getOrDefault(otherAccepted, 0) == 0)
                    assert(!other.readBuffer.isClosed)
                    driver.drainFifos()
                    assert(spy.closeCounts.getOrDefault(otherAccepted, 0) == 1)
                    assert(other.readBuffer.isClosed)
                }
            }
        }
    }

    "closing an unstarted driver releases registrations staged by a manual drain" in {
        if !PosixConstants.isMacOrBsd then cancel("kqueue is macOS/BSD-only")
        PosixTestSockets.loopbackPair().map { case (client, accepted) =>
            val real    = PollerBackend.default()
            val spy     = RecordingSocketBindings(sock)
            val backend = RecordingPollerBackend(real)
            val driver  = TestDrivers.forBackend(backend, real.create(), spy)
            val handle  = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
            Scope.ensure(Sync.defer {
                driver.closeHandle(handle)
                driver.close()
            }.andThen(sock.close(client).safe.get.unit)).andThen {
                val read = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                driver.awaitRead(handle, read)
                driver.drainFifos()
                driver.closeHandle(handle)
                driver.close()
                assert(backend.registeredReadFds.contains(accepted))
                assert(spy.closeCounts.getOrDefault(accepted, 0) == 1)
                assert(handle.readBuffer.isClosed)
            }
        }
    }

    "a stale deregister does not evict a recycled fd's new registration" in {
        PosixTestSockets.assumePoller()
        // Two handles share one fd, the recycled-fd shape: `live` is the new owner (registered for reads), `stale` is the prior handle whose
        // generation differs. A deregister for `stale` (its fd was closed and recycled into `live`) must leave `live`'s registration and kernel
        // filter intact: the HandleId guard skips the removal when activeFds(fd) carries `live`'s id, not `stale`'s. Verified end to end: after the
        // stale deregister, bytes sent to the fd still dispatch to `live`'s read. At base the unconditional deregister evicts `live` and the read
        // strands (a timeout).
        PosixTestSockets.loopbackPair().map { case (client, accepted) =>
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            discard(driver.start())

            val live = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
            val stale =
                PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal) // same fd, later generation differs
            assert(live.id.packed != stale.id.packed, "the two handles for the recycled fd must carry distinct HandleIds")

            val liveRead = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
            driver.awaitRead(live, liveRead) // registers `live` for reads on `accepted` (FIFO: applies before the deregister below)
            driver.cancel(stale)             // stale deregister for the prior handle on the same fd; the id guard must skip it

            // Send bytes to the fd: with `live`'s registration intact the read dispatches; if the stale deregister evicted it, the read strands.
            val payload = Array.tabulate[Byte](16)(i => (i + 1).toByte)
            val sendBuf = Buffer.fromArray[Byte](payload)
            discard(sock.sendNow(client, sendBuf, payload.length.toLong, PosixConstants.MSG_NOSIGNAL))
            sendBuf.close()

            Abort.run[Timeout | Closed](Async.timeout(5.seconds)(liveRead.safe.get)).map { outcome =>
                driver.closeHandle(live)
                discard(sock.close(client))
                Sync.defer(driver.close()).map { _ =>
                    outcome match
                        case Result.Success(ReadOutcome.Bytes(got)) =>
                            assert(
                                got.toArray.sameElements(payload),
                                s"the live registration must survive the stale deregister and deliver the bytes, got ${got.toArray.toList}"
                            )
                        case Result.Failure(_: Timeout) =>
                            fail("the live registration was evicted by the stale deregister: the read stranded")
                        case other =>
                            fail(s"unexpected read outcome for the surviving registration: $other")
                    end match
                }
            }
        }
    }

    "no-op-uses-a-freed-fd: a closing handle's dangling read re-arm does not evict a recycled fd's new registration" in {
        PosixTestSockets.assumePoller()
        // The register-side re-arm race. Two handles share one fd (the recycled-fd shape): `live` is the new owner registered for reads; `stale` is
        // the prior handle (later generation) whose connection has CLOSED. The ReadPump always re-arms (ReadPump.requestNextRead), so `stale` can
        // issue a dangling awaitRead after its close. applyRegistration must reject a registration from a closing handle: applying it would overwrite
        // `live`'s activeFds/pendingReads entry and re-arm the kernel under the dead handle's id, so `live`'s read would never dispatch. Verified end
        // to end: after the stale register, bytes sent to the fd still dispatch to `live`. At base (no isClosing guard) the stale register evicts
        // `live` and the read strands (a timeout).
        PosixTestSockets.loopbackPair().map { case (client, accepted) =>
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            discard(driver.start())

            val live = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
            val stale =
                PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal) // same fd, later generation differs
            assert(live.id.packed != stale.id.packed, "the two handles for the recycled fd must carry distinct HandleIds")

            val liveRead = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
            driver.awaitRead(live, liveRead) // registers `live` for reads on `accepted` (FIFO: applies before the stale register below)
            stale.requestClose() // mark `stale` closing without closing the shared fd (freeResources never closes the socket fd)
            val staleRead = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
            driver.awaitRead(stale, staleRead) // the dangling re-arm from the closed handle; applyRegistration must reject it

            // Send bytes to the fd: with `live`'s registration intact the read dispatches; if the stale register evicted it, the read strands.
            val payload = Array.tabulate[Byte](16)(i => (i + 1).toByte)
            val sendBuf = Buffer.fromArray[Byte](payload)
            discard(sock.sendNow(client, sendBuf, payload.length.toLong, PosixConstants.MSG_NOSIGNAL))
            sendBuf.close()

            Abort.run[Timeout | Closed](Async.timeout(5.seconds)(liveRead.safe.get)).map { outcome =>
                driver.closeHandle(live)
                discard(sock.close(client))
                Sync.defer(driver.close()).map { _ =>
                    outcome match
                        case Result.Success(ReadOutcome.Bytes(got)) =>
                            assert(
                                got.toArray.sameElements(payload),
                                s"the live registration must survive the closing handle's stale register and deliver the bytes, got ${got.toArray.toList}"
                            )
                        case Result.Failure(_: Timeout) =>
                            fail("the live registration was evicted by the closing handle's dangling read re-arm: the read stranded")
                        case other =>
                            fail(s"unexpected read outcome for the surviving registration: $other")
                    end match
                }
            }
        }
    }

end PollerIoDriverRecycledFdTest
