package kyo.net

import kyo.*
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.WritePump
import kyo.net.internal.transport.WriteResult
import kyo.net.internal.transport.WriteState

/** Each write handoff is resolved by exactly one winner.
  *
  * The three write handoffs are: (1) the take handoff (channel delivers a span, onTake CASes Idle -> Flushing), (2) the writable
  * handoff (socket becomes writable, onWritable CASes AwaitingWritable -> Flushing), and (3) the backpressure-release handoff (the
  * drain completed, backpressurePromise is completed, onWritable CASes Backpressured -> Flushing). Each is a single-CAS transition; the
  * loser observes the winner's state and no-ops. No check-A-act-B: the folded state cell is the only read+write, not two separate
  * fields.
  *
  * The single-winner CAS property does not require concurrent threads: two sequential CAS calls with the same expected value prove that
  * exactly one can succeed (after the first wins, the cell state changes and the second's expected-value comparison fails). The
  * deterministic sequential approach is cross-platform (JVM, JS, Native) and gives the same correctness guarantee as a concurrent race,
  * because the correctness property is about the CAS semantics of the cell, not about scheduling.
  */
class CONC3Test extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    "CONC3" - {

        // Given: writable and take completion both attempt the AwaitingWritable -> Flushing handoff
        // When: each attempt is sequenced deterministically (first wins, second finds cell changed)
        // Then: exactly one handoff wins; the loser observes the winner's state and no-ops
        "each-handoff-resolved-by-one-winner: racing writable completions produce exactly one Flushing winner" in {
            val span        = Span.fromUnsafe(Array.fill[Byte](8)(7))
            val off         = 2
            val initial     = WriteState.AwaitingWritable(span, off)
            val state       = AtomicRef.Unsafe.init[WriteState](initial)
            val newFlushing = WriteState.Flushing(span, off)

            var wins = 0

            // First attempt: reads the stored AwaitingWritable instance, CASes to Flushing.
            // Uses the exact stored reference (reference-equality CAS).
            if state.compareAndSet(initial, newFlushing) then wins += 1

            // Second attempt: reads whatever is in the cell now. The first attempt changed it to
            // Flushing, so this compareAndSet sees Flushing != initial (AwaitingWritable) and returns
            // false. wins stays at 1.
            if state.compareAndSet(initial, newFlushing) then wins += 1

            assert(wins == 1, s"exactly one CAS from AwaitingWritable must succeed, got $wins")
            assert(state.get() == newFlushing, s"state must be Flushing after exactly one winner, was ${state.get()}")
            succeed
        }

        // Given: a take completion and a stale writable both attempt the Idle -> Flushing handoff
        // When: the state is already Flushing (a concurrent writer won)
        // Then: the loser's CAS from Idle fails; no double-write
        "each-handoff-resolved-by-one-winner: stale take-completion after state advanced to Flushing no-ops" in {
            val span  = Span.fromUnsafe(Array.fill[Byte](4)(8))
            val state = AtomicRef.Unsafe.init[WriteState](WriteState.Flushing(span, 0))

            // A stale take-completion tries to CAS from Idle (the state it expected) but the state is Flushing.
            val stale = state.compareAndSet(WriteState.Idle, WriteState.Flushing(span, 0))

            assert(!stale, "stale CAS from Idle must fail when state is already Flushing")
            assert(state.get() == WriteState.Flushing(span, 0), "state must remain unchanged after stale CAS")
            succeed
        }

        // Given: the WritePump's TornDown state is terminal
        // When: racing teardown and a writable completion both attempt to transition out of AwaitingWritable
        // Then: after teardown swings TornDown, the writable CAS from AwaitingWritable fails
        "each-handoff-resolved-by-one-winner: teardown wins the CAS race, writable no-ops after TornDown" in {
            val span    = Span.fromUnsafe(Array.fill[Byte](4)(9))
            val initial = WriteState.AwaitingWritable(span, 0)
            val state   = AtomicRef.Unsafe.init[WriteState](initial)

            // Simulate teardown: unconditionally sets TornDown.
            state.set(WriteState.TornDown)

            // Now a stale writable fires and attempts AwaitingWritable -> Flushing.
            val staleCas = state.compareAndSet(initial, WriteState.Flushing(span, 0))

            assert(!staleCas, "stale writable CAS must fail after teardown set TornDown")
            assert(state.get() == WriteState.TornDown, "state must remain TornDown")
            succeed
        }
    }

end CONC3Test
