package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** Reproduce-first confirmation of single-recv ordering: [[IoUringDriver.submitRecv]] must never let two recvs be
  * simultaneously kernel-owned for the SAME handle. Arming a second recv while the first is still in flight would submit a
  * second SQE that targets the identical staging buffer with zero synchronization -- the shared-buffer aliasing hazard the orphaned-producer
  * trigger ([[IoUringOrphanHandshakeRecvRoutingTest]]) exploits: the post-upgrade `ReadPump`'s first recv racing an orphaned handshake-window
  * recv still kernel-owned for the same handle.
  *
  * Recv submission is serialized per handle instead of blocking the caller: [[PosixHandle.isUpgraded]] handles gate `submitRecv` on
  * [[IoUringDriver.hasInFlightRead]] and QUEUE a second request ([[PosixHandle.queuedRecv]]) rather than submit a second SQE; [[IoUringDriver.
  * complete]] fires the queued request (`drainQueuedRecv`) the moment the in-flight recv's CQE is fully processed. Non-blocking by
  * construction: `onFinished` (`PosixTransport.upgradeRole`) never waits on this -- an earlier blocking-wait variant was tried and reverted
  * after it deadlocked `IoUringMutualTlsStressTest` (both peers of an upgrade can symmetrically wait on their own orphan to drain while that
  * orphan's bytes are exactly the OTHER peer's first post-upgrade write, which that peer's own symmetrically-blocked upgrade never reaches).
  *
  * This test arms two real recvs back-to-back for one handle (marked [[PosixHandle.isUpgraded]], mirroring a post-upgrade connection) and
  * proves: (1) the second request is QUEUED, not a second live SQE (its promise stays pending, no aliasing is even possible); (2) once the
  * first recv's real data reaps, the queued request fires automatically, in order, and resolves with the SECOND chunk sent -- not a stale or
  * duplicated read of the first.
  *
  * io_uring-only ([[PosixTestSockets.assumeUring]]): only io_uring submits a kernel-owned recv SQE this queue exists to serialize; the
  * pollers' synchronous reads have no such hazard (see [[PosixHandle.queuedRecv]]'s doc).
  *
  * Anti-flakiness: no `Thread.sleep`, no busy-spin. `awaitCondition` polls real, observable state transitions (`hasInFlightRead`, the
  * `queuedRecv` slot, a promise's `poll()`).
  */
class IoUringQueuedRecvOrderingTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    private def awaitCondition(bound: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            if cond then Loop.done(true)
            else if java.lang.System.nanoTime() >= deadline then Loop.done(false)
            else Async.sleep(2.millis).andThen(Loop.continue(()))
        }
    end awaitCondition

    "IoUringDriver queued recv ordering" - {

        "a second recv armed while the first is in flight is queued (not a second SQE) and fires in order once the first reaps" in {
            PosixTestSockets.assumeUring()
            given Frame = Frame.internal
            val depth   = math.max(256, kyo.net.TransportConfig.default.ioPoolSize * 64)
            val uring   = Ffi.load[IoUringBindings]
            val ring    = Buffer.alloc[Byte](uring.kyo_uring_sizeof().toInt)
            val rc      = uring.io_uring_queue_init(depth, ring, 0)
            if rc != 0 then
                ring.close()
                throw Closed("IoUringQueuedRecvOrderingTest", summon[Frame], s"queue_init failed: rc=$rc")
            val driver = TestDrivers.forBindings(uring, ring)
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    // Marks this a post-upgrade-shaped handle: submitRecv's single-recv-ordering gate only applies to handles that went
                    // through (or are simulating having gone through) a STARTTLS upgrade.
                    acceptedH.isUpgraded = true

                    val promise1 = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    driver.awaitRead(acceptedH, promise1)

                    awaitCondition(5.seconds)(driver.hasInFlightRead(acceptedH)).map { armed1 =>
                        assert(armed1, "recv #1's submitRecv never ran (a hang, not the ordering hazard under test)")

                        // Arm recv #2 for the SAME handle BEFORE recv #1 has completed.
                        val promise2 = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        driver.awaitRead(acceptedH, promise2)

                        awaitCondition(5.seconds) {
                            acceptedH.queuedRecv match
                                case _: PosixHandle.QueuedRecv.Queued => true
                                case _                                => false
                        }.map { queued =>
                            assert(
                                queued,
                                s"recv #2 was not queued (slot=${acceptedH.queuedRecv}); it may have raced a second SQE instead"
                            )
                            // recv #2 must NOT have resolved: it is sitting in the queue, no SQE submitted for it at all.
                            assert(promise2.poll().isEmpty, s"queued recv #2 must stay pending; got ${promise2.poll()}")

                            val payload1 = Array[Byte](1, 1, 1, 1)
                            val payload2 = Array[Byte](2, 2, 2, 2, 2)
                            assert(
                                sock.sendNow(client, Buffer.fromArray[Byte](payload1), payload1.length.toLong, 0).value ==
                                    payload1.length.toLong
                            )

                            awaitCondition(5.seconds)(promise1.poll().isDefined).map { resolved1 =>
                                assert(resolved1, "recv #1 never resolved after real data arrived")
                                promise1.poll() match
                                    case Present(Result.Success(ReadOutcome.Bytes(bytes))) =>
                                        assert(
                                            bytes.toArrayUnsafe.toSeq == payload1.toSeq,
                                            s"recv #1 resolved with ${bytes.toArrayUnsafe.toSeq} != sent payload1 ${payload1.toSeq}"
                                        )
                                    case other => fail(s"expected recv #1 Success(Bytes), got $other")
                                end match

                                // recv #1 completing must have drained the queue: recv #2's real SQE is now in flight.
                                awaitCondition(5.seconds)(driver.hasInFlightRead(acceptedH)).map { armed2 =>
                                    assert(armed2, "recv #2 was never submitted after recv #1 drained (queue never fired)")
                                    assert(
                                        acceptedH.queuedRecv match
                                            case _: PosixHandle.QueuedRecv.Queued => false
                                            case _ =>
                                                true
                                        ,
                                        s"queuedRecv slot must be cleared once drained; got ${acceptedH.queuedRecv}"
                                    )
                                    assert(
                                        sock.sendNow(client, Buffer.fromArray[Byte](payload2), payload2.length.toLong, 0).value ==
                                            payload2.length.toLong
                                    )

                                    awaitCondition(5.seconds)(promise2.poll().isDefined).map { resolved2 =>
                                        assert(resolved2, "recv #2 never resolved after its (queued, then fired) real data arrived")
                                        promise2.poll() match
                                            case Present(Result.Success(ReadOutcome.Bytes(bytes))) =>
                                                assert(
                                                    bytes.toArrayUnsafe.toSeq == payload2.toSeq,
                                                    s"recv #2 resolved with ${bytes.toArrayUnsafe.toSeq} != sent payload2 ${payload2.toSeq}"
                                                )
                                            case other => fail(s"expected recv #2 Success(Bytes), got $other")
                                        end match
                                        // Close both sides: `client` (the peer) and `accepted` (the driver-managed handle this test drove
                                        // directly, never wired to a Connection). Only closing `client` leaves `accepted`'s fd in CLOSE_WAIT
                                        // forever (driver.close() below tears down the ring, not individually-registered handle fds).
                                        discard(sock.close(client))
                                        discard(sock.close(accepted))
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

end IoUringQueuedRecvOrderingTest
