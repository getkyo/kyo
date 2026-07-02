package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.tls.TlsEngineLoopback
import kyo.net.internal.tls.TlsRealEngines
import kyo.net.internal.transport.ReadOutcome
import scala.jdk.CollectionConverters.*

/** Deterministic proof that [[IoUringDriver.complete]]'s post-onFinished orphan-feed path (see [[IoUringOrphanHandshakeRecvRoutingTest]] /
  * [[IoUringStalePumpRecvRoutingTest]]) is race-free against the review concern that its `h.tls.isDefined` discriminator ("onFinished ran, no
  * consumer will ever drain `upgradeHandoff` again") could drift out of sync with the actual retirement of `driveUpgradeRead`'s consumer: either
  * a live consumer still draining `upgradeHandoff` at the same moment the direct-feed ALSO fires (DUPLICATED bytes), or the consumer retiring
  * before `tls` actually becomes `Present` (bytes LOST after all, the original bug).
  *
  * Why the discriminator is race-free by construction (not by luck): `PosixTransport`'s `onFinished` closure (`handle.upgradeActive = false`
  * through `upgraded.start()`, including the `tls = Present(engine)` write AND the pre-existing "Post-FINISHED slot drain" that claims any
  * `upgradeHandoff` Carryover staged before this point) is ONE indivisible synchronous call with no `submitEngineOp` boundary inside it, and it
  * always runs on the single reap carrier that also processes every CQE for this handle. No other CQE can be routed "in the middle" of it: a
  * reaping recv either observes the ENTIRE pre-onFinished state (`tls` Absent, the slot-drain not yet run) or the ENTIRE post-onFinished state
  * (`tls` Present, the slot-drain already complete) -- never a torn mix of the two. Separately, `driveUpgradeRead` only ever parks a NEW
  * `Waiter` as a direct, synchronous continuation of consuming the immediately-prior one (the handshake step loop is a strict continuation
  * chain: `WantRead` -> `recvAndFeed` -> `driveUpgradeRead` -> park -> the waiter's own fulfillment calls `step()` again), so if the step loop
  * ever reaches `Done` (leading to `onFinished`), any waiter it parked earlier has ALREADY been consumed -- there is no way for a live,
  * yet-unfulfilled `Waiter` to still be sitting in `upgradeHandoff` at the moment `onFinished` runs. Finally, the post-onFinished direct-feed
  * path (this fix) and the pre-existing Post-FINISHED slot-drain operate on textually and temporally DISJOINT data: the slot-drain only ever
  * touches whatever Carryover was ALREADY staged in `upgradeHandoff` before `onFinished`'s closure started running; the direct-feed path only
  * ever fires for a CQE reaping in a STRICTLY LATER reap-loop turn, reading straight from the recv's own kernel-written buffer
  * (`handle.readBuffer` or `recvStagingFor`, via `armedForStaging`), never touching `upgradeHandoff` at all. Two disjoint data sources cannot
  * double-feed the same bytes.
  *
  * This test builds the exact scenario with REAL, decodable ciphertext (a completed in-memory handshake via [[TlsEngineLoopback.handshake]],
  * unlike the sibling tests' never-handshaked engine, which can only prove "reached a terminal state" since its bytes are undecodable garbage)
  * so it can assert on the ACTUAL decoded plaintext:
  *   1. Arm an orphan recv pre-upgrade (`tls` Absent), exactly as [[IoUringStalePumpRecvRoutingTest]] does.
  *   2. Flip flags to `onFinished`'s exact final state while the orphan stays kernel-owned.
  *   3. Arm a SECOND recv immediately, while the orphan is still in flight -- this must QUEUE ([[PosixHandle.queuedRecv]]), not submit a
  *      competing SQE, mirroring a real post-upgrade `ReadPump`'s first read racing a still-in-flight orphan.
  *   4. Send chunk1's real ciphertext: confirm it is fed to the engine and delivered via `inboundSink` EXACTLY ONCE (not lost, not
  *      duplicated), and that the second recv's promise has NOT resolved (not contaminated by chunk1).
  *   5. Confirm the queued second recv then drains to a genuine new SQE (`IoUringDriver.complete`'s `drainQueuedRecv`, called immediately
  *      after the orphan's direct-feed `submitEngineOp` is already enqueued -- this ordering is what guarantees chunk1 decodes before chunk2
  *      even reaches the wire-order question).
  *   6. Send chunk2's real ciphertext: confirm the second recv resolves with its OWN correct plaintext via the ORDINARY (non-orphan) TLS-feed
  *      path, uncontaminated by chunk1.
  *   7. Confirm both chunks are witnessed, in send order, in a single shared log spanning both delivery mechanisms (`inboundSink` for the
  *      orphan, the resolved promise for the ordinary recv) -- proving the FIFO enqueue ordering actually delivers chunk1 strictly before
  *      chunk2, with neither loss nor duplication.
  *
  * Both engines are allocated via [[TlsRealEngines.singleEngine]] (caller-owned) rather than [[TlsRealEngines.withEngines]] (auto-free on the
  * body's return), and freed explicitly right after this test's own assertions complete, mirroring the established fix from
  * `IoUringFatalRecordCloseRaceTest`'s test-construction hazard (an auto-freed engine racing a real driver's async engine-touching close):
  * this test never triggers a fatal record or `closeHandle` (the ciphertext is always valid), so there is no async teardown to race at all
  * during the body; only the final `driver.close()` (deferred to the outermost `Sync.ensure`, run after the engines are already freed,
  * matching the sibling tests' validated nesting order) touches the ring afterward.
  *
  * io_uring-only ([[PosixTestSockets.assumeUring]]): only io_uring has a kernel-owned recv that can outlive `onFinished` this way.
  *
  * Anti-flakiness: no `Thread.sleep`, no busy-spin; `awaitCondition` polls real, observable state transitions, mirroring
  * [[IoUringOrphanHandshakeRecvRoutingTest.awaitCondition]].
  */
class IoUringOrphanFeedOrderNoDuplicationTest extends Test:

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

    "IoUringDriver post-onFinished orphan feed" - {

        "delivers the orphan's chunk exactly once, in order, before a queued second recv's own chunk, over a real completed handshake" in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.assumeTlsReady()
            given Frame = Frame.internal

            val clientEngine = TlsRealEngines.singleEngine(isServer = false)
            val serverEngine = TlsRealEngines.singleEngine(isServer = true)
            assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "in-memory handshake never completed")

            val depth = math.max(256, kyo.net.TransportConfig.default.ioPoolSize * 64)
            val uring = Ffi.load[IoUringBindings]
            val ring  = Buffer.alloc[Byte](uring.kyo_uring_sizeof().toInt)
            val rc    = uring.io_uring_queue_init(depth, ring, 0)
            if rc != 0 then
                ring.close()
                throw Closed("IoUringOrphanFeedOrderNoDuplicationTest", summon[Frame], s"queue_init failed: rc=$rc")
            val driver = TestDrivers.forBindings(uring, ring)
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    Sync.ensure(Sync.defer { clientEngine.free(); serverEngine.free() }) {
                        val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)

                        // Shared ordered log spanning BOTH delivery mechanisms: inboundSink (the orphan's path) and the ordinary recv's own
                        // promise (the second, queued-then-drained recv's path). One queue makes "in order, no loss, no duplication" a single,
                        // direct assertion instead of separately-timestamped signals.
                        val order = new java.util.concurrent.ConcurrentLinkedQueue[String]()

                        // Step 1: arm the orphan recv exactly as a pre-upgrade ReadPump continuation would (handshakeOwned=false, tls Absent,
                        // so submitRecv targets handle.readBuffer).
                        val orphanPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        driver.awaitRead(acceptedH, orphanPromise)

                        awaitCondition(5.seconds)(driver.hasInFlightRead(acceptedH)).map { orphanArmed =>
                            assert(orphanArmed, "orphan recv's submitRecv never ran (a hang, not the race under test)")

                            // Step 2: flip flags to onFinished's exact final state (PosixTransport.scala:1307-1313's write order) while the
                            // orphan recv stays kernel-owned and in flight.
                            acceptedH.isUpgraded = true
                            acceptedH.upgradeActive = true
                            acceptedH.upgradeActive = false
                            acceptedH.handshakeReading = false
                            acceptedH.tls = Present(serverEngine)
                            acceptedH.upgrading = false
                            acceptedH.inboundSink = bytes =>
                                discard(order.add(s"orphan:${new String(bytes.toArrayUnsafe, java.nio.charset.StandardCharsets.US_ASCII)}"))

                            // Step 3: arm the SECOND recv immediately, while the orphan is still kernel-owned. isUpgraded=true &&
                            // hasInFlightRead=true must QUEUE this (PosixHandle.queuedRecv), never submit a competing SQE against the same
                            // buffer -- exactly a real post-upgrade ReadPump's first read racing a still-in-flight orphan.
                            val secondPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                            driver.awaitRead(acceptedH, secondPromise)

                            def isQueued: Boolean = acceptedH.queuedRecv match
                                case _: PosixHandle.QueuedRecv.Queued => true
                                case _                                => false

                            awaitCondition(5.seconds)(isQueued).map { queued =>
                                assert(queued, "second recv never queued behind the in-flight orphan -- it may have raced a competing SQE")

                                // Step 4: send chunk1's REAL ciphertext (encrypted by the client engine against the completed handshake) so the
                                // already-armed orphan recv's real SQE reaps it.
                                val chunk1Cipher = TlsEngineLoopback.encrypt(
                                    clientEngine,
                                    "chunk1".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
                                )
                                assert(sock.sendNow(
                                    client,
                                    Buffer.fromArray[Byte](chunk1Cipher),
                                    chunk1Cipher.length.toLong,
                                    0
                                ).value == chunk1Cipher.length.toLong)

                                // The orphan's feed must land in `order` exactly once, via inboundSink (never via secondPromise, never
                                // duplicated): confirms the fix drains the orphan's real bytes -- feeding them to the SAME (handshaked) engine
                                // every other recv on this handle shares -- rather than losing them in a dead upgradeHandoff slot.
                                awaitCondition(5.seconds)(!order.isEmpty).map { orphanDelivered =>
                                    assert(orphanDelivered, "orphan's chunk1 never reached inboundSink (lost, not fed)")
                                    assert(order.size() == 1, s"orphan delivered more than once (duplicated): ${order.asScala.toSeq}")
                                    assert(
                                        secondPromise.poll().isEmpty,
                                        s"second recv resolved before its own chunk arrived (contaminated by chunk1?); got ${secondPromise.poll()}"
                                    )

                                    // Step 5: the queued second recv must now have drained to a genuine new SQE (IoUringDriver.complete's
                                    // drainQueuedRecv, called immediately after the orphan's direct-feed submitEngineOp is already enqueued:
                                    // this ordering is what guarantees chunk1 decodes before chunk2 even reaches the wire-order question).
                                    awaitCondition(5.seconds)(driver.hasInFlightRead(acceptedH)).map { secondArmed =>
                                        assert(secondArmed, "second recv's queued request never drained to a real SQE (a hang)")

                                        // Step 6: send chunk2's REAL ciphertext so the second recv's real SQE reaps it, taking the ORDINARY
                                        // (non-orphan) TLS-feed path this time (armedPostUpgrade=true at its own arm time, since isUpgraded was
                                        // already true) -- its own promise resolves directly, uncontaminated by chunk1.
                                        val chunk2Cipher = TlsEngineLoopback.encrypt(
                                            clientEngine,
                                            "chunk2".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
                                        )
                                        assert(sock.sendNow(
                                            client,
                                            Buffer.fromArray[Byte](chunk2Cipher),
                                            chunk2Cipher.length.toLong,
                                            0
                                        ).value == chunk2Cipher.length.toLong)

                                        awaitCondition(5.seconds)(secondPromise.poll().isDefined).map { resolved =>
                                            assert(resolved, "second recv never resolved after its own real chunk arrived")
                                            secondPromise.poll() match
                                                case Present(Result.Success(ReadOutcome.Bytes(bytes))) =>
                                                    discard(order.add(
                                                        s"second:${new String(bytes.toArrayUnsafe, java.nio.charset.StandardCharsets.US_ASCII)}"
                                                    ))
                                                case other => fail(s"expected Success(Bytes), got $other")
                                            end match

                                            // The core race-freedom assertion: exactly two entries, in send order, each carrying its OWN
                                            // uncontaminated plaintext -- neither lost (both present) nor duplicated (exactly one each) nor
                                            // reordered (orphan strictly first, matching engineQueue's FIFO enqueue order).
                                            assert(
                                                order.asScala.toSeq == Seq("orphan:chunk1", "second:chunk2"),
                                                s"expected exactly [orphan:chunk1, second:chunk2] in order, got ${order.asScala.toSeq}"
                                            )
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
    }

end IoUringOrphanFeedOrderNoDuplicationTest
