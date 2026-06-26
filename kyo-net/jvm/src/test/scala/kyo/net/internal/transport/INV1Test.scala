package kyo.net.internal.transport

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kyo.*
import kyo.net.Test
import kyo.net.internal.NioHandle

/** Read-delivery invariants for the NIO read-arm owner cell.
  *
  * These tests drive the read-arm owner cell directly, without a real I/O loop. They cover two
  * core properties:
  *
  *   - INV-1 (yardstick): exactly one delivery per armed read. When two completers race on the same
  *     armed cell, the first CAS to Absent wins and the second finds the cell already cleared.
  *   - NIO orphan interleaving: the stale pump re-arm scenario that historically hung STARTTLS
  *     handshakes. The handshake installs its cell after the pump's; a stale dispatch holding the
  *     pump's old cell reference fails its CAS (the current cell is the handshake's); the live
  *     dispatch holding the handshake's cell succeeds and delivers to the handshake's promise.
  *
  * Placement in `jvm/src/test`: `NioHandle` (and thus `NioHandle.readArm`) is JVM-only. The
  * double-completion leaf tests the CAS semantics of `AtomicRef.Unsafe`, which are cross-platform,
  * but the test uses `NioHandle` directly and therefore lives here. The orphan-repro leaf is
  * explicitly NIO-specific. Neither leaf uses a real I/O selector or spawns threads; all
  * interleavings are deterministic and sequential.
  */
class INV1Test extends Test:

    import AllowUnsafe.embrace.danger

    // Open a connected SocketChannel pair for NioHandle construction. The channels are closed at the
    // end of each test. The pair is needed because NioHandle wraps a SocketChannel; the channels are
    // never read or written here.
    private def openPair(): (SocketChannel, SocketChannel) =
        val ss = ServerSocketChannel.open()
        ss.bind(new InetSocketAddress("127.0.0.1", 0))
        val port = ss.socket().getLocalPort
        val c    = SocketChannel.open()
        c.configureBlocking(false)
        c.connect(new InetSocketAddress("127.0.0.1", port))
        ss.configureBlocking(true)
        val s = ss.accept()
        c.finishConnect()
        ss.close()
        (c, s)
    end openPair

    "INV-1" - {

        // Given: one armed read (a single Present(promise) cell installed in the readArm owner cell).
        // When: two completers both try to complete the armed promise via a CAS-clear of the cell.
        // Then: exactly one delivery reaches the reader. The first CAS from the stored cell reference
        //       to Absent wins; the second finds the cell already Absent and no-ops. The promise's
        //       complete() returns true for the winner and false for the loser, proving single-winner.
        //
        // The single-winner property holds because AtomicRef.compareAndSet uses reference equality:
        // both completers obtain the stored cell via get() and pass that same reference as the expected
        // value. Only one can atomically swap the cell to Absent; the other's CAS finds the cell already
        // changed and fails.
        "read-delivered-exactly-once-under-double-completion" in {
            val (client, server) = openPair()
            try
                val handle = NioHandle.init(client, 4096)

                // Build a fresh cell with one promise and install it as the armed read.
                val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                val cell    = Present(promise)
                handle.readArm.set(cell)

                // Both completers obtain the stored cell via get() and attempt to CAS it to Absent,
                // mirroring how dispatchRead works: read cell, then CAS-clear before completing.
                var wins = 0

                // First completer: reads the stored cell, CAS-clears, and on success completes promise.
                val stored = handle.readArm.get()
                if handle.readArm.compareAndSet(stored, Absent) then
                    wins += 1
                    promise.complete(Result.succeed(ReadOutcome.PeerFin))
                    ()
                end if

                // Second completer: uses the same stored cell reference. CAS must fail because the
                // cell is already Absent after the first CAS succeeded.
                if handle.readArm.compareAndSet(stored, Absent) then
                    wins += 1
                    promise.complete(Result.succeed(ReadOutcome.WouldBlock))
                    ()
                end if

                assert(wins == 1, s"exactly one CAS from the stored cell must succeed, got wins=$wins")
                assert(handle.readArm.get().isEmpty, "cell must be Absent after the single winner cleared it")

                // The promise must have received exactly one outcome (the winner's). Poll confirms it.
                val outcome = promise.poll()
                assert(
                    outcome match
                        case Present(Result.Success(v)) => v.asInstanceOf[ReadOutcome] match
                                case ReadOutcome.PeerFin => true
                                case _                   => false
                        case _ => false,
                    s"promise must carry exactly the winner's outcome (PeerFin), got $outcome"
                )
                succeed
            finally
                client.close()
                server.close()
            end try
        }

        // Given: a NIO connection mid-STARTTLS. The plaintext pump has an armed read cell installed on
        //        the handle's readArm. The handshake then arms its own read, overwriting the pump's
        //        cell with a new cell carrying the handshake's promise (this is the historical
        //        interleaving that orphaned the handshake's promise).
        // When: a stale pump dispatch (holding the pump's OLD cell reference) tries to CAS-clear the
        //       cell.
        // Then: the stale pump's CAS FAILS because the cell now holds the handshake's reference.
        //       The live handshake dispatch (holding the handshake's cell) CAS-clears successfully
        //       and delivers to the handshake's promise. The pump's promise is not delivered by the
        //       dispatch (it was cleared when the handshake overwrote the cell).
        //
        // Fail-before evidence: if the slot were a plain @volatile var (no CAS protection), a stale
        // pump re-arm that stores into the slot AFTER the handshake would leave the slot pointing at
        // pump's promise. Dispatch would then complete pump's promise and orphan the handshake's
        // promise. This is demonstrated in the "fail-before-simulation" sub-test below.
        //
        // Pass-after: with the AtomicRef cell and reference-equality CAS, the stale pump dispatch's
        // compareAndSet fails because the stored reference is the handshake's cell, not the pump's.
        // The live dispatch holding the handshake's cell wins the CAS and delivers correctly.
        "nio-stale-pump-rearm-after-handshake-arm-orphan-repro" - {

            // Fail-before simulation: demonstrate that a plain slot (no CAS protection) orphans the
            // handshake's promise when the pump re-arms AFTER the handshake installs its promise.
            "fail-before-simulation: plain-slot stale-pump-overwrites-handshake-promise" in {
                // Simulate the old @volatile var slot with a plain var (the historical bug substrate).
                var slot: Promise.Unsafe[ReadOutcome, Abort[Closed]] | Null = null

                val pumpPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                val hsPromise   = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()

                // Pump installs its promise.
                slot = pumpPromise
                // Handshake installs its promise, overwriting pump's.
                slot = hsPromise
                // Stale pump re-arms: overwrites the slot back with pump's promise.
                // (This is the race: the pump did not see the handshake's install.)
                slot = pumpPromise

                // Dispatch reads the slot: sees pumpPromise (the pump's stale re-arm overwrote).
                val dispatched = slot.nn
                dispatched.complete(Result.succeed(ReadOutcome.PeerFin))

                // hsPromise is never completed: it was orphaned.
                val hsResult = hsPromise.poll()
                assert(
                    hsResult == Absent,
                    s"fail-before: handshake promise must be orphaned (Absent) when pump overwrites slot, got $hsResult"
                )
                // pumpPromise was completed by the stale dispatch.
                val pumpPoll = pumpPromise.poll()
                assert(
                    pumpPoll match
                        case Present(Result.Success(v)) => v.asInstanceOf[ReadOutcome] match
                                case ReadOutcome.PeerFin => true
                                case _                   => false
                        case _ => false,
                    s"fail-before: pump promise must carry PeerFin, got $pumpPoll"
                )
                succeed
            }

            // Pass-after: the AtomicRef cell with CAS-clear prevents the stale pump dispatch from
            // completing the handshake's promise, because the stale dispatch holds the pump's old
            // cell reference which no longer matches the current cell content.
            "pass-after: handshake-cell-wins-cas-stale-pump-cell-loses" in {
                val (client, server) = openPair()
                try
                    val handle = NioHandle.init(client, 4096)

                    val pumpPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    val hsPromise   = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()

                    // Step 1: pump arms its read (installs pumpCell).
                    val pumpCell = Present(pumpPromise)
                    handle.readArm.set(pumpCell)

                    // Step 2: handshake arms its read (installs hsCell, overwriting pumpCell).
                    val hsCell = Present(hsPromise)
                    handle.readArm.set(hsCell)

                    // Step 3: a stale pump dispatch holds the old pumpCell reference and tries to CAS.
                    // The current cell is hsCell, so the CAS fails (reference equality: pumpCell != hsCell).
                    val staleWin = handle.readArm.compareAndSet(pumpCell, Absent)
                    assert(!staleWin, "stale pump CAS must FAIL: current cell is handshake's, not pump's")

                    // Cell must still be the handshake's cell (the stale CAS changed nothing): the stored
                    // promise is hsPromise (reference equality confirms identity).
                    assert(
                        handle.readArm.get() match
                            case Present(p) => p.asInstanceOf[AnyRef] eq hsPromise.asInstanceOf[AnyRef]
                            case Absent     => false,
                        "cell must remain the handshake's cell (hsPromise) after the stale pump CAS failed"
                    )

                    // Step 4: the live handshake dispatch holds hsCell and CAS-clears successfully.
                    val liveWin = handle.readArm.compareAndSet(hsCell, Absent)
                    assert(liveWin, "live handshake CAS must SUCCEED: it holds the current cell reference")
                    assert(handle.readArm.get().isEmpty, "cell must be Absent after the live dispatch cleared it")

                    // Step 5: live dispatch delivers to the handshake's promise.
                    hsPromise.complete(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(Array[Byte](1, 2, 3)))))

                    // The handshake's promise is delivered (not orphaned).
                    val hsResult = hsPromise.poll()
                    assert(
                        hsResult.isDefined,
                        s"handshake promise must be delivered (not orphaned); got $hsResult"
                    )
                    hsResult match
                        case Present(Result.Success(ReadOutcome.Bytes(span))) =>
                            assert(span.toArray.toList == List[Byte](1, 2, 3))
                        case other =>
                            fail(s"expected handshake promise to carry Bytes([1,2,3]), got $other")
                    end match

                    // The pump's promise was never completed by the dispatch (the stale CAS lost).
                    val pumpResult = pumpPromise.poll()
                    assert(
                        pumpResult == Absent,
                        s"pump promise must NOT be completed by the dispatch (stale CAS lost); got $pumpResult"
                    )
                    succeed
                finally
                    client.close()
                    server.close()
                end try
            }
        }
    }

end INV1Test
