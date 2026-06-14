package kyo.ffi.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kyo.discard
import kyo.ffi.Ffi
import kyo.ffi.Test

/** Native-only tests for `Ffi.Guard.close()` racing concurrent retained-callback invocations.
  *
  * The concrete Native risk is: thread A invokes a retained trampoline (reading `CallbackRegistry.retainedSlots_$shape(i)`); thread B
  * closes the owning guard, which runs `releaseRetainedSlot_*` and nulls the slot. Without the `GuardCore` state machine + inFlight drain,
  * thread A may race past a null check and dereference freed state.
  *
  * Tests target the `GuardCore` state machine directly (the code that actually fixes the race) plus an end-to-end check that the
  * bookkeeping is wired through `NativeGuard.unsafeRetainRetainedSlot` → `CallbackRegistry.bindSlotToGuard`.
  */
class GuardCloseRaceTest extends Test:

    // Touches process-global state (global stderr/system property, or the shared CallbackRegistry pool/hooks) and so
    // must run alone: under the default parallel leaf execution a sibling leaf observes or mutates the same global.
    override def config = super.config.sequential

    private def isParked(t: Thread): Boolean =
        val s = t.getState
        (s eq Thread.State.WAITING) || (s eq Thread.State.TIMED_WAITING)

    "GuardCore state machine (#6)" - {

        "beginCallback returns true while open" in {
            val core = new GuardCore(() => (), () => ())
            assert(core.beginCallback() == true)
            core.endCallback()
        }

        "beginCallback returns false after close() has transitioned state" in {
            val core = new GuardCore(() => (), () => ())
            assert(core.close() == kyo.ffi.Ffi.CloseOutcome.Clean)
            assert(core.beginCallback() == false)
        }

        "close() drains in-flight callbacks before returning" in {
            val core = new GuardCore(() => (), () => ())
            assert(core.beginCallback() == true)

            val closeStarted  = new CountDownLatch(1)
            val closeReturned = new AtomicBoolean(false)
            val r: Runnable = () =>
                closeStarted.countDown()
                discard(core.close())
                closeReturned.set(true)
            val closer = new Thread(r, "spec-closer")
            closer.setDaemon(true)
            closer.start()
            closeStarted.await()
            val deadline = System.nanoTime() + 2_000_000_000L
            while !isParked(closer) && !closeReturned.get() && System.nanoTime() < deadline do
                Thread.onSpinWait()
            end while
            assert(closeReturned.get() == false)
            core.endCallback()
            closer.join(3000)
            assert(closeReturned.get() == true)
        }

        "close() is idempotent, second call returns AlreadyClosed and does not re-run teardown" in {
            val teardowns = new AtomicInteger(0)
            val core = new GuardCore(
                () => discard(teardowns.incrementAndGet()),
                () => ()
            )
            assert(core.close() == kyo.ffi.Ffi.CloseOutcome.Clean)
            assert(core.close() == kyo.ffi.Ffi.CloseOutcome.AlreadyClosed)
            assert(core.close() == kyo.ffi.Ffi.CloseOutcome.AlreadyClosed)
            assert(teardowns.get() == 1)
        }

        "double-check: beginCallback retracts its increment if close CASes concurrently" in {
            val core    = new GuardCore(() => (), () => ())
            val threads = 8
            val start   = new CountDownLatch(1)
            val done    = new CountDownLatch(threads)
            val opened  = new AtomicInteger(0)

            var i = 0
            while i < threads do
                val r: Runnable = () =>
                    try
                        start.await()
                        var j = 0
                        while j < 1000 do
                            if core.beginCallback() then
                                discard(opened.incrementAndGet())
                                core.endCallback()
                            end if
                            j += 1
                        end while
                    catch case _: Throwable => ()
                    finally done.countDown()
                val t = new Thread(r, s"race-$i")
                t.setDaemon(true)
                t.start()
                i += 1
            end while
            start.countDown()
            val deadline2 = System.nanoTime() + 2_000_000_000L
            while opened.get() < 1 && System.nanoTime() < deadline2 do
                Thread.onSpinWait()
            end while
            discard(core.close())
            assert(done.await(10, TimeUnit.SECONDS) == true)
            assert(core.inFlight.get() == 0)
            assert(core.state.get() == GuardCore.StateClosed)
        }
    }

    "NativeGuard retained-slot binding (#6)" - {

        "unsafeRetainRetainedSlot sets the guardCore back-reference on the TaggedCallback" in {
            Ffi.Guard.use { g =>
                val native = g.asInstanceOf[NativeGuard]
                val (slot, _) = CallbackRegistry.claimRetainedSlot_I_I(
                    native.guardToken,
                    "kyo.ffi.internal.Spec",
                    "binding",
                    (n: Int) => n + 1
                )
                try
                    native.unsafeRetainRetainedSlot("I_I", slot)
                    val tc = CallbackRegistry.retainedSlots_I_I(slot).asInstanceOf[TaggedCallback]
                    assert(tc.guardCore ne null)
                finally
                    CallbackRegistry.releaseRetainedSlot_I_I(slot)
                end try
            }
        }

        "after close, the bound guardCore reports callbacks as refused" in {
            val g      = Ffi.Guard.open()
            val native = g.asInstanceOf[NativeGuard]
            val (slot, _) = CallbackRegistry.claimRetainedSlot_I_I(
                native.guardToken,
                "kyo.ffi.internal.Spec",
                "afterClose",
                (n: Int) => n + 1
            )
            native.unsafeRetainRetainedSlot("I_I", slot)
            val tc   = CallbackRegistry.retainedSlots_I_I(slot).asInstanceOf[TaggedCallback]
            val core = tc.guardCore
            assert(core ne null)
            g.close()
            // After close, beginCallback via the captured core must refuse, this is exactly what the generated trampoline observes
            // before reading its slot.
            assert(core.nn.beginCallback() == false)
        }
    }
end GuardCloseRaceTest
