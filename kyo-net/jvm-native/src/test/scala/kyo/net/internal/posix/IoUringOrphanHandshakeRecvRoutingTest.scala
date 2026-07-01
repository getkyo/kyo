package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.tls.TlsRealEngines
import kyo.net.internal.transport.ReadOutcome

/** Reproduce-first confirmation of the "orphaned handshakeOwned recv" routing bug (P10, cell-8): a STARTTLS handshake's own producer recv
  * (armed via [[IoUringDriver.awaitReadHandshake]], tagged `handshakeOwned`) that is STILL kernel-owned and in flight when the handshake
  * reaches `onFinished` -- possible because `driveUpgradeRead`'s "no stale recv in flight" check races the reap carrier's own engine-FIFO
  * enqueue ordering (a TOCTOU: enqueued-for-registration is not yet registered, see p10-fix-log.md) -- must still route through the
  * upgrade-handoff slot when its CQE eventually reaps, even though by then `upgradeActive`/`upgrading` have already cleared and `tls` is
  * already `Present` (onFinished's flag-clear order, `PosixTransport.upgradeRole`). Before the fix, [[IoUringDriver.complete]]'s routing keyed
  * purely on `upgradeActive`/`upgrading`, so this orphan fell through into the ordinary TLS-feed branch: it fed its ciphertext directly into
  * the engine (racing/interleaving with whatever the post-upgrade ReadPump's own recv feeds concurrently -- exactly the bad_record_mac
  * corruption shape) and completed its own throwaway producer promise with the result, which nothing observes (a silently dropped
  * application-data flight).
  *
  * This test drives the EXACT reap-time state the orphan reaches, without needing to win the real TOCTOU race: it arms a real `handshakeOwned`
  * recv directly via [[IoUringDriver.awaitReadHandshake]] (mirroring `armUpgradeProducerRead`) while the handle is in the upgrade window
  * (`upgradeActive`/`upgrading` true, `tls` still `Absent`, exactly as `driveUpgradeRead`'s producer-arm branch runs), then flips the handle's
  * flags in the SAME order `onFinished` does (`upgradeActive`/`upgrading` false, `tls` attached) WHILE that recv is still kernel-owned and in
  * flight, and only then lets the peer's bytes reap it for real over io_uring. The routing decision under test is [[IoUringDriver.complete]]'s;
  * nothing about the SQE/CQE path is faked.
  *
  * io_uring-only ([[PosixTestSockets.assumeUring]]): only io_uring has a kernel-owned recv that can outlive `onFinished` this way.
  *
  * Anti-flakiness: no `Thread.sleep`, no busy-spin. `awaitCondition` polls a real, observable state transition (`upgradeHandoff` leaving
  * `Idle`), mirroring [[IoUringQueuedRecvOrderingTest.awaitCondition]].
  */
class IoUringOrphanHandshakeRecvRoutingTest extends Test:

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

    "IoUringDriver orphaned handshakeOwned recv" - {

        "reaping after upgradeActive/upgrading clear (onFinished already ran) routes through the upgrade-handoff slot, not the normal TLS-feed branch" in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.assumeTlsReady()
            given Frame = Frame.internal
            val depth   = math.max(256, kyo.net.TransportConfig.default.ioPoolSize * 64)
            val uring   = Ffi.load[IoUringBindings]
            val ring    = Buffer.alloc[Byte](uring.kyo_uring_sizeof().toInt)
            val rc      = uring.io_uring_queue_init(depth, ring, 0)
            if rc != 0 then
                ring.close()
                throw Closed("IoUringOrphanHandshakeRecvRoutingTest", summon[Frame], s"queue_init failed: rc=$rc")
            val driver = TestDrivers.forBindings(uring, ring)
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val serverEngine = TlsRealEngines.singleEngine(isServer = true)
                    Sync.ensure(Sync.defer(serverEngine.free())) {
                        val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                        // Reach the upgrade-window state driveUpgradeRead's "no stale recv, arm producer" branch runs in (upgradeRole sets
                        // isUpgraded/upgradeActive/upgrading before detach; tls stays Absent until onFinished), then arm the orphaned producer
                        // recv directly -- exactly as armUpgradeProducerRead does (awaitReadHandshake, handshakeOwned=true) -- so submitRecv
                        // targets the SAME buffer (handle.readBuffer, since tls is still Absent here) the real orphan's SQE would target.
                        acceptedH.isUpgraded = true
                        acceptedH.upgradeActive = true
                        acceptedH.upgrading = true
                        val producer = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        driver.awaitReadHandshake(acceptedH, producer)

                        // awaitReadHandshake only ENQUEUES the arm onto the engine FIFO (submitDeferredRecv); the actual submitRecv -- and its
                        // tls-keyed buffer choice (handle.readBuffer here, since tls is still Absent) -- runs later, on the FIFO worker. Wait for
                        // that real submit (hasInFlightRead observes the registered PendingOp) before flipping the flags below: flipping them too
                        // early would have submitRecv itself observe tls=Present and target recvStagingFor instead, unlike the real orphan (whose
                        // SQE submits while driveUpgradeRead still holds upgradeActive, well before onFinished can run).
                        awaitCondition(5.seconds)(driver.hasInFlightRead(acceptedH)).map { armed =>
                            assert(armed, "orphan recv's submitRecv never ran (a hang, not the routing hazard under test)")

                            // Now simulate onFinished running WHILE the recv above is still kernel-owned and in flight (the TOCTOU outcome): clear
                            // upgradeActive/upgrading and attach tls, in the SAME order onFinished writes them (PosixTransport.upgradeRole).
                            acceptedH.upgradeActive = false
                            acceptedH.tls = Present(serverEngine)
                            acceptedH.upgrading = false

                            val payload = Array[Byte](1, 2, 3, 4, 5)
                            assert(sock.sendNow(
                                client,
                                Buffer.fromArray[Byte](payload),
                                payload.length.toLong,
                                0
                            ).value == payload.length.toLong)

                            awaitCondition(5.seconds) {
                                acceptedH.upgradeHandoff.get() match
                                    case _: PosixHandle.UpgradeHandoff.Carryover => true
                                    case _                                       => false
                            }.map { staged =>
                                assert(
                                    staged,
                                    s"orphan recv never staged a Carryover (slot=${acceptedH.upgradeHandoff.get()}); the routing fix did not fire"
                                )
                                acceptedH.upgradeHandoff.get() match
                                    case PosixHandle.UpgradeHandoff.Carryover(bytes) =>
                                        assert(
                                            bytes.toSeq == payload.toSeq,
                                            s"staged Carryover bytes ${bytes.toSeq} != sent payload ${payload.toSeq}"
                                        )
                                    case other => fail(s"expected Carryover, got $other")
                                end match
                                // The orphan's own throwaway producer promise must NEVER complete: routing it through upgradeHandoff means
                                // nothing touches `producer` (armUpgradeProducerRead's design -- "the handshake observes only the slot").
                                // Before the fix, the same bytes would instead have been fed to feedAndDecrypt and would have completed (or
                                // failed) `producer` directly.
                                assert(producer.poll().isEmpty, s"producer promise must stay pending (orphaned); got ${producer.poll()}")
                                discard(sock.close(client))
                                succeed
                            }
                        }
                    }
                }
            }
        }
    }

end IoUringOrphanHandshakeRecvRoutingTest
