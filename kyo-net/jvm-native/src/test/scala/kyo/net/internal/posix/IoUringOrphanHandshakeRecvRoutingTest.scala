package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.transport.ReadOutcome

/** Reproduce-first confirmation of two guarantees for the "orphaned handshakeOwned recv" routing bug: a STARTTLS handshake's own
  * producer recv (armed via [[IoUringDriver.awaitReadHandshake]], tagged `handshakeOwned`) that is STILL kernel-owned and in flight when the
  * handshake reaches `onFinished` -- possible because `driveUpgradeRead`'s "no stale recv in flight" check races the reap carrier's own
  * engine-FIFO enqueue ordering (a TOCTOU: enqueued-for-registration is not yet registered) -- must never be fed straight
  * into the ordinary TLS-feed branch, even though by the time its CQE reaps, `upgradeActive`/`upgrading` have already cleared and `tls` is
  * already `Present` (onFinished's flag-clear order, `PosixTransport.upgradeRole`). Routing that keyed purely on `upgradeActive`/`upgrading`
  * would let this orphan fall through into the ordinary TLS-feed branch: it would feed its ciphertext directly
  * into the engine (racing/interleaving with whatever the post-upgrade ReadPump's own recv feeds concurrently -- exactly the bad_record_mac
  * corruption shape) and complete its own throwaway producer promise with the result, which nothing observes (a silently dropped
  * application-data flight).
  *
  * First guarantee (routing): [[IoUringDriver.complete]]'s 3rd clause (`handshakeOwned && h.isUpgraded`) identifies this recv as an orphan instead of
  * letting it fall through to the ordinary TLS-feed branch.
  *
  * Second guarantee (this test's actual scope, matching [[IoUringStalePumpRecvRoutingTest]]'s non-`handshakeOwned` sibling case): once identified as
  * an orphan, staging the bytes as an `upgradeHandoff` Carryover is correct only while a live `driveUpgradeRead` consumer could
  * still drain it. But this recv reaps AFTER `onFinished` has ALREADY run to completion (`h.tls` is `Present`): the handshake-driving fiber that
  * used to consume `upgradeHandoff` is DONE and will never check the slot again, so staging a Carryover here would silently lose these bytes
  * forever, leaving a permanent gap in the ciphertext stream (the actual "Closed at collect" mechanism: the engine desyncs on whatever chunk
  * reaps next). The driver feeds these bytes to the engine directly and delivers any resulting plaintext through `PosixHandle.inboundSink` instead.
  *
  * This test drives the EXACT reap-time state the orphan reaches, without needing to win the real TOCTOU race: it arms a real `handshakeOwned`
  * recv directly via [[IoUringDriver.awaitReadHandshake]] (mirroring `armUpgradeProducerRead`) while the handle is in the upgrade window
  * (`upgradeActive`/`upgrading` true, `tls` still `Absent`, exactly as `driveUpgradeRead`'s producer-arm branch runs), then flips the handle's
  * flags in the SAME order `onFinished` does (`upgradeActive`/`upgrading` false, `tls` attached) WHILE that recv is still kernel-owned and in
  * flight, and only then lets the peer's bytes reap it for real over io_uring. The routing decision under test is [[IoUringDriver.complete]]'s;
  * nothing about the SQE/CQE path is faked. The sent bytes are not valid ciphertext for the never-handshaked `serverEngine` (constructing a
  * real paired handshake is exactly what the full-stack `IoUringMutualTlsStressTest` exercises), so the observable outcome is a fatal-record
  * teardown rather than delivered plaintext; the assertions that matter here are that (a) `upgradeHandoff` NEVER becomes a `Carryover` (the
  * bytes are never routed to the dead slot) and (b) the handle reaches a terminal state (the new path was genuinely exercised, not silently
  * skipped).
  *
  * io_uring-only ([[PosixTestSockets.assumeUring]]): only io_uring has a kernel-owned recv that can outlive `onFinished` this way.
  *
  * Anti-flakiness: no `Thread.sleep`, no busy-spin. `awaitCondition` polls a real, observable state transition, mirroring
  * [[IoUringQueuedRecvOrderingTest.awaitCondition]].
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

        "reaping after upgradeActive/upgrading clear (onFinished already ran) feeds the engine directly instead of staging a now-dead upgrade-handoff Carryover" in {
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

                            // Observe whether the new path is exercised: inboundSink is the delivery target for a successful decode (not
                            // reached here, since the bytes below are not valid ciphertext for this never-handshaked engine, but installing
                            // it confirms the code never falls back to completing `producer` or leaving the connection silently open).
                            val delivered = new java.util.concurrent.atomic.AtomicReference[Array[Byte]](null)
                            acceptedH.inboundSink = bytes => delivered.set(bytes.toArray)

                            val payload = Array[Byte](1, 2, 3, 4, 5)
                            assert(sock.sendNow(
                                client,
                                Buffer.fromArray[Byte](payload),
                                payload.length.toLong,
                                0
                            ).value == payload.length.toLong)

                            // Not valid TLS ciphertext for a never-handshaked engine: the expected outcome is a fatal-record teardown (the
                            // same `closeHandle` path IoUringMutualTlsStressTest's real handshakes exercise on genuinely bad data), not
                            // delivered plaintext. What this test actually pins is the ABSENCE of the buggy behavior.
                            awaitCondition(5.seconds)(acceptedH.isClosing()).map { closed =>
                                assert(
                                    closed,
                                    "orphan recv's post-onFinished feed never reached a terminal state (a hang, not the fix under test)"
                                )
                                // The core regression guard: the bytes must NEVER be staged as a Carryover. Once onFinished has fully run, that
                                // slot has no consumer left, so staging it there would silently lose the bytes forever (the actual "Closed at
                                // collect" mechanism this fix closes) instead of being fed to the engine (whatever the engine then does with them).
                                acceptedH.upgradeHandoff.get() match
                                    case _: PosixHandle.UpgradeHandoff.Carryover =>
                                        fail(s"orphan recv's bytes were staged as a Carryover (slot=${acceptedH.upgradeHandoff.get()}) " +
                                            "instead of being fed to the engine -- they would be lost forever, no consumer remains")
                                    case _ => ()
                                end match
                                // The raw bytes aren't a valid TLS record for the never-handshaked engine, so the fatal-record path fires
                                // before any plaintext is produced: inboundSink must never see a delivery here.
                                assert(
                                    delivered.get() == null,
                                    s"unexpected plaintext delivered from an undecodable record: ${delivered.get().toSeq}"
                                )
                                // The orphan's own throwaway producer promise must NEVER complete: its bytes are delivered via inboundSink (or
                                // dropped on a fatal record), never through this recv's own promise (armUpgradeProducerRead's design -- "the
                                // handshake observes only the slot").
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
