package kyo.net.internal.transport

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kyo.*
import kyo.net.Test
import kyo.net.internal.NioHandle
import kyo.net.internal.ReadArmCell

/** Read-delivery invariants for the NIO read-arm owner cell.
  *
  * These tests drive the read-arm owner cell directly, without a real I/O loop. They cover two
  * core properties:
  *
  *   - Exactly one delivery per armed read. When two completers race on the same
  *     armed cell, the first CAS to Absent wins and the second finds the cell already cleared.
  *   - NIO orphan interleaving: the stale pump re-arm scenario that historically hung STARTTLS
  *     handshakes. The handshake installs its cell after the pump's; a stale dispatch holding the
  *     pump's old cell object reference fails its CAS (the current cell is the handshake's fresh
  *     object); the live dispatch holding the handshake's cell succeeds and delivers to its promise.
  *
  * The orphan guard is object identity: each `ReadArmCell(promise)` call allocates a fresh wrapper
  * object. Two arms that carry the same promise are still distinguishable by cell reference
  * equality because `new ReadArmCell(p) ne new ReadArmCell(p)` by JVM identity.
  *
  * Placement in `jvm/src/test`: `NioHandle` (and thus `NioHandle.readArm`) is JVM-only. The
  * double-completion leaf tests the CAS semantics of `AtomicRef.Unsafe`, which are cross-platform,
  * but the test uses `NioHandle` directly and therefore lives here. The orphan-repro leaf is
  * explicitly NIO-specific. Neither leaf uses a real I/O selector or spawns threads; all
  * interleavings are deterministic and sequential.
  */
class NioHandleReadArmTest extends Test:

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

    "read delivered exactly once per armed read" - {

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

                // Build a fresh ReadArmCell (fresh allocation + promise) and install it as the armed read.
                // Each ReadArmCell allocation produces a distinct heap object; reference equality in
                // AtomicRef.compareAndSet is what gives the single-winner guarantee.
                val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                val cell    = Present(ReadArmCell(promise))
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
            // ReadArmCell reference which no longer matches the current cell content.
            "pass-after: handshake-cell-wins-cas-stale-pump-cell-loses" in {
                val (client, server) = openPair()
                try
                    val handle = NioHandle.init(client, 4096)

                    val pumpPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    val hsPromise   = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()

                    // Step 1: pump arms its read (installs pumpCell with a fresh ReadArmCell object).
                    val pumpArm  = ReadArmCell(pumpPromise)
                    val pumpCell = Present(pumpArm)
                    handle.readArm.set(pumpCell)

                    // Step 2: handshake arms its read (installs hsCell as a fresh ReadArmCell, overwriting pumpCell).
                    val hsArm  = ReadArmCell(hsPromise)
                    val hsCell = Present(hsArm)
                    handle.readArm.set(hsCell)

                    // Step 3: a stale pump dispatch holds the old pumpCell reference and tries to CAS.
                    // The current cell is hsCell, so the CAS fails: pumpCell and hsCell are distinct
                    // objects (reference equality), even if pumpPromise and hsPromise were the same.
                    val staleWin = handle.readArm.compareAndSet(pumpCell, Absent)
                    assert(!staleWin, "stale pump CAS must FAIL: current cell is handshake's object, not pump's")

                    // Cell must still be the handshake's cell (the stale CAS changed nothing): the stored
                    // cell's promise is hsPromise (reference equality confirms identity).
                    assert(
                        handle.readArm.get() match
                            case Present(c) => c.promise.asInstanceOf[AnyRef] eq hsPromise.asInstanceOf[AnyRef]
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

            // Stale-arm sub-test: when the SAME promise is reused across re-arms (e.g. a ReadPump
            // re-arming its persistent IOPromise), each armRead call allocates a fresh ReadArmCell
            // object. A stale dispatch holding the previous arm's cell must fail its CAS even
            // though both arms carry the same promise reference, because the two ReadArmCell wrapper
            // objects are distinct heap objects (new ReadArmCell(p) != new ReadArmCell(p) by reference).
            "stale-arm: same-promise-different-cell-object-cas-fails" in {
                val (client, server) = openPair()
                try
                    val handle = NioHandle.init(client, 4096)

                    // One promise, used for two consecutive arms (simulates a ReadPump re-using its IOPromise).
                    val sharedPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()

                    // First arm: allocate a fresh ReadArmCell wrapper object.
                    val arm1  = ReadArmCell(sharedPromise)
                    val cell1 = Present(arm1)
                    handle.readArm.set(cell1)

                    // Second arm: SAME promise, a different fresh ReadArmCell wrapper object.
                    val arm2  = ReadArmCell(sharedPromise)
                    val cell2 = Present(arm2)
                    handle.readArm.set(cell2)

                    // arm1 and arm2 carry the same promise but are distinct heap objects: the `new`
                    // in each ReadArmCell(sharedPromise) call produces a separate allocation.
                    assert(
                        (arm1: AnyRef) ne (arm2: AnyRef),
                        "arm1 and arm2 must be distinct heap objects even when they carry the same promise"
                    )

                    // Stale dispatch from the first arm: tries to CAS with cell1.
                    // Must FAIL: current cell is cell2, and Present(arm1) != Present(arm2) by reference.
                    val staleWin = handle.readArm.compareAndSet(cell1, Absent)
                    assert(!staleWin, "stale CAS must FAIL: same promise, but different ReadArmCell wrapper object")

                    // Current cell must still be arm2's cell (the second arm's cell is unchanged).
                    assert(
                        handle.readArm.get() match
                            case Present(c) => (c: AnyRef) eq (arm2: AnyRef)
                            case Absent     => false,
                        "current cell must still be arm2's ReadArmCell object after the stale CAS failed"
                    )

                    // Live dispatch from the second arm: CAS with cell2 succeeds.
                    val liveWin = handle.readArm.compareAndSet(cell2, Absent)
                    assert(liveWin, "live CAS must SUCCEED: it holds the current cell reference (arm2)")
                    assert(handle.readArm.get().isEmpty, "cell must be Absent after the live dispatch cleared it")
                    succeed
                finally
                    client.close()
                    server.close()
                end try
            }
        }
    }

end NioHandleReadArmTest
