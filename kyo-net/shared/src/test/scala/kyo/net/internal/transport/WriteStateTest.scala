package kyo.net.internal.transport

import kyo.*
import kyo.net.Test

/** Tests that WriteState CAS transitions are exactly-once and stale completers lose the CAS structurally.
  *
  * These tests drive the WriteState cell directly, without a real driver or socket. They exercise the two core scenarios: (1) two
  * competing CAS attempts on the same AwaitingWritable state, where exactly one wins and the other loses (the loser observes the
  * winner's state and no-ops); (2) a stale writable for a Flushing(spanA, 0) state fires after the state has already advanced to Idle,
  * and the CAS fails (no-op-on-stale is structural, not assumed).
  *
  * The single-winner CAS property does not require concurrent threads to be demonstrated: two sequential CAS calls on the same cell with
  * the same expected value prove that exactly one can succeed (the second finds the cell already changed and fails). The deterministic
  * sequential approach is cross-platform (JVM, JS, Native) and gives the same correctness guarantee as a concurrent race, because the
  * correctness property is about the CAS semantics of the cell, not about scheduling.
  *
  * Note on CAS identity: [[AtomicRef.Unsafe.compareAndSet]] uses [[java.util.concurrent.atomic.AtomicReference]] reference equality on
  * JVM (and equivalent semantics on JS/Native). All CAS operations here obtain the stored instance via [[AtomicRef.Unsafe.get]] and pass
  * THAT reference as the expected value, matching how [[WritePump]] works.
  */
class WriteStateTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    "WriteState" - {

        // Given: a WritePump in AwaitingWritable(span, off)
        // When: two sequential CAS attempts both try to transition out of AwaitingWritable
        // Then: exactly ONE transition succeeds; the second CAS finds the cell already changed and returns false
        //
        // The single-winner property is structural: AtomicReference.compareAndSet (reference equality) means
        // only the holder of the stored reference can win. After the winner changes the cell, the loser's
        // compareAndSet finds a different state and returns false. This holds regardless of interleaving
        // because the cell guarantees exactly-once: the winner's write is globally visible before the loser
        // evaluates its expectedValue == currentValue check.
        "reentrant-writable-and-take-yields-one-transition" in {
            val span    = Span.fromUnsafe(Array.fill[Byte](8)(1))
            val off     = 0
            val initial = WriteState.AwaitingWritable(span, off)
            val state   = AtomicRef.Unsafe.init[WriteState](initial)

            var wins = 0

            // First CAS attempt: reads the stored AwaitingWritable reference, attempts the transition.
            state.get() match
                case s @ WriteState.AwaitingWritable(p, o) =>
                    if state.compareAndSet(s, WriteState.Flushing(p, o)) then wins += 1
                case _ =>
            end match

            // Second CAS attempt: reads whatever is in the cell now. Since the first attempt succeeded
            // (state is now Flushing), the stored value is no longer AwaitingWritable(span, off) and
            // the match arm does not fire. If somehow we still see AwaitingWritable (unreachable here),
            // the CAS would fail because the reference already changed. Either way wins stays at 1.
            state.get() match
                case s @ WriteState.AwaitingWritable(p, o) =>
                    if state.compareAndSet(s, WriteState.Flushing(p, o)) then wins += 1
                case _ =>
            end match

            assert(wins == 1, s"exactly one CAS from AwaitingWritable must succeed, got wins=$wins")
            val finalState = state.get()
            assert(
                finalState.isInstanceOf[WriteState.Flushing],
                s"state must be Flushing after the winner's CAS, was $finalState"
            )
            succeed
        }

        // Given: a WritePump that has advanced past Flushing(spanA, 0) to Idle
        // When: a stale writable completion for spanA fires (the reused-promise hazard)
        // Then: its CAS from a stale Flushing reference fails (state is Idle), so it no-ops; the
        //       no-op-on-stale property is exercised, not assumed
        "stale-flush-loses-the-cas" in {
            val spanA = Span.fromUnsafe(Array.fill[Byte](8)(2))
            // Simulate: pump stored this Flushing instance, then transitioned to Idle.
            val stale = WriteState.Flushing(spanA, 0)
            val state = AtomicRef.Unsafe.init[WriteState](WriteState.Idle)

            // A stale writable tries to CAS from the old Flushing instance.
            // The state is now Idle, so the CAS fails regardless of reference equality.
            val casResult = state.compareAndSet(stale, WriteState.Flushing(spanA, 0))

            assert(!casResult, "stale CAS from Flushing(spanA,0) must fail when state is Idle")
            assert(state.get() == WriteState.Idle, s"state must remain Idle after stale CAS, was ${state.get()}")
            succeed
        }

        // Given: state is TornDown
        // When: a live-state CAS attempt (Idle -> Flushing) is made after teardown
        // Then: the CAS fails because the current state is TornDown, not Idle
        "torn-down-state-rejects-live-transitions" in {
            val span  = Span.fromUnsafe(Array.fill[Byte](4)(3))
            val state = AtomicRef.Unsafe.init[WriteState](WriteState.TornDown)

            // A racing take fires after teardown sets TornDown. It tries Idle -> Flushing.
            // The state is TornDown (not Idle), so this CAS fails.
            val cas = state.compareAndSet(WriteState.Idle, WriteState.Flushing(span, 0))

            assert(!cas, "CAS from Idle to Flushing must fail when state is TornDown (not Idle)")
            assert(state.get() == WriteState.TornDown, "state must remain TornDown")
            succeed
        }

        // Given: two distinct Flushing states with different spans/offsets
        // When: the CAS uses the stored reference (same object) vs. a new instance (different object)
        // Then: reference-equal CAS succeeds; a new instance with the same fields fails
        "flushing-cas-uses-reference-equality" in {
            val spanA  = Span.fromUnsafe(Array.fill[Byte](4)(4))
            val stored = WriteState.Flushing(spanA, 0)
            val state  = AtomicRef.Unsafe.init[WriteState](stored)

            // A new Flushing with the SAME fields is a different object: CAS fails.
            val freshInstance = WriteState.Flushing(spanA, 0)
            val wrongRef      = state.compareAndSet(freshInstance, WriteState.Idle)
            assert(!wrongRef, "CAS with a fresh Flushing instance (different reference) must fail")
            assert(state.get() eq stored, "state must be unchanged after wrong-reference CAS")

            // CAS with the STORED reference succeeds.
            val correctRef = state.compareAndSet(stored, WriteState.Idle)
            assert(correctRef, "CAS with the stored reference must succeed")
            assert(state.get() == WriteState.Idle, "state must be Idle after correct CAS")
            succeed
        }
    }

end WriteStateTest
