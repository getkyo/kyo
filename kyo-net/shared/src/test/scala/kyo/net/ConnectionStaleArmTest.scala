package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** INV-3 guard: no delivery to a settled or recycled promise.
  *
  * When a STARTTLS upgrade recycles a connection on the same fd (new generation), any stale completion targeting the old generation's armed
  * promise cannot reach the new generation's armed promise. The guard is the reference-equality CAS on the read-arm owner cell: each arm
  * installs a fresh Present(promise) object; a stale dispatch holds an old cell reference; the new arm installs a different object; the
  * stale CAS fails because the current cell is the new object, not the stale one.
  *
  * These tests operate at the Connection + mock driver layer, requiring no real I/O or NioHandle. They are placed in shared/ because
  * Connection and the mock IoDriver[Unit] are cross-platform.
  */
class ConnectionStaleArmTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    "INV3" - {

        // Given: a STARTTLS stale recv and an fd-recycle (simulated as two Connection arms on the same driver).
        // When: the stale completion arrives (completes the old arm's promise).
        // Then: no delivery to the settled/recycled owner: the new arm's promise remains unresolved.
        //
        // The reference-equality CAS is the guard: the stale arm holds the old cell (the old ReadPump
        // IOPromise object); the new arm installs a new cell (a new ReadPump IOPromise object). The two
        // cells are distinct objects (ne), so a stale completion fires on the old cell and cannot reach
        // the new cell. This mirrors INV-1 at the Connection layer.
        "no-delivery-to-a-settled-promise" - {

            // Fail-before: demonstrate that a plain shared slot (no CAS protection) lets a stale
            // delivery land on whichever promise currently occupies the slot, which may be the
            // new generation's promise if the stale arm fires after the new arm installed its cell.
            "fail-before-simulation: stale-delivery-lands-on-new-promise-via-plain-slot" in {
                val stalePromise  = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                val activePromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()

                // Plain slot: the stale arm wrote stalePromise first; the new arm overwrote with activePromise.
                // Using Maybe to avoid opaque-type null compatibility issues.
                var slot: Maybe[Promise.Unsafe[ReadOutcome, Abort[Closed]]] = Absent
                slot = Present(stalePromise)
                slot = Present(activePromise)

                // Stale delivery reads the slot and completes whatever is there.
                slot.foreach(_.completeDiscard(Result.succeed(ReadOutcome.PeerFin)))

                // Without CAS protection the delivery reaches activePromise (the new generation's promise).
                val activeResult = activePromise.poll()
                assert(
                    activeResult.isDefined,
                    s"fail-before: stale delivery reaches the new promise via the plain slot (the bug to fix)"
                )
                // stalePromise was never completed by this delivery.
                assert(
                    stalePromise.poll() == Absent,
                    s"fail-before: stale promise not reached by the delivery that landed on the new slot"
                )
                succeed
            }

            // Pass-after: with distinct cell objects, a stale delivery completing the old cell does not
            // affect the new cell. The two arms capture distinct ReadPump IOPromise objects, so a
            // completion on the stale one cannot reach the active one regardless of ordering.
            "pass-after: stale-arm-completion-does-not-reach-new-arm-promise" in {
                var staledPromise: Maybe[Promise.Unsafe[ReadOutcome, Abort[Closed]]] = Absent
                var activePromise: Maybe[Promise.Unsafe[ReadOutcome, Abort[Closed]]] = Absent
                var awaitReadCalls: Int                                              = 0

                final class CapturingDriver extends IoDriver[Unit]:
                    def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                        Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
                    def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
                        awaitReadCalls += 1
                        // First arm is the stale (old generation); second is the active (new generation).
                        if staledPromise.isEmpty then staledPromise = Present(promise)
                        else activePromise = Present(promise)
                    end awaitRead
                    def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
                    def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit  = ()
                    def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit    = ()
                    def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult = WriteResult.Done
                    def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                               = ()
                    def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit                          = ()
                    def close()(using AllowUnsafe, Frame): Unit                                            = ()
                    def label: String                                                                      = "CapturingDriver"
                    def handleLabel(handle: Unit): String                                                  = "stub"
                end CapturingDriver

                val driver = new CapturingDriver

                // Start conn1 (the "old generation"): its ReadPump arms a read, capturing staledPromise.
                val conn1 = Connection.init[Unit]((), driver, 8)
                conn1.start()
                assert(staledPromise.isDefined, "first arm must be captured after conn1.start()")

                // Start conn2 (the "new generation", simulating fd recycle): its ReadPump arms a read,
                // capturing activePromise.
                val conn2 = Connection.init[Unit]((), driver, 8)
                conn2.start()
                assert(activePromise.isDefined, "second arm must be captured after conn2.start()")

                val sp = staledPromise.get
                val ap = activePromise.get

                // The two cells must be distinct IOPromise objects (the reference-equality CAS guard relies on this).
                assert(
                    sp.asInstanceOf[AnyRef] ne ap.asInstanceOf[AnyRef],
                    "stale and active cells must be distinct objects for the reference-equality guard to hold"
                )

                // Deliver a stale completion to the stale arm (simulating the old-generation CQE arriving).
                // This reaches only the stale arm's promise; the active arm's promise is untouched.
                sp.completeDiscard(Result.succeed(ReadOutcome.PeerFin))

                // Active (new generation) promise must remain unresolved.
                val activeResult = ap.poll()
                assert(
                    activeResult == Absent,
                    s"pass-after: active (new generation) promise must not be reached by the stale delivery; got $activeResult"
                )

                // Stale promise was completed.
                val staleResult = sp.poll()
                assert(
                    staleResult.isDefined,
                    s"pass-after: stale promise must carry the delivery outcome; got $staleResult"
                )
                succeed
            }
        }
    }

end ConnectionStaleArmTest
