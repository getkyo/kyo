package kyo

import AllowUnsafe.embrace.danger
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** JVM-only Gate tests that need genuine OS-thread parallelism.
  *
  * The `passAt` lost-wakeup is a multi-threaded compare-and-swap race: it needs one thread inside `passAt` reading the phase while another
  * thread's pass advances the phase and swaps the pass-promise. It cannot manifest under the single-threaded JS/Native runtimes, and the
  * fiber scheduler's per-task overhead makes the few-instruction race window effectively unhittable from cooperative fibers, so the
  * reproduction is driven directly on `java.lang.Thread`.
  */
class GateJvmTest extends kyo.test.Test[Any]:

    "passAt" - {

        // Regression for the passAt TOCTOU lost-wakeup. The unfixed passAt read
        // the phase (A) before the pass-promise (B). The boundary-crossing pass
        // advances the phase AND swaps in a fresh promise between A and B, so
        // passAt registered its continuation on the swapped-in promise that no
        // later pass completes, and the waiter was never woken even though the
        // phase had already advanced past the target.
        //
        // Each round arrives at phase `target` on a fresh single-party gate, then
        // releases one passer thread and several waiter threads together: the
        // passer runs the single boundary pass that crosses target -> target+1,
        // each waiter runs passAt(target). A lost wakeup is observed directly (no
        // hang needed): once the boundary pass has advanced the phase, a correct
        // passAt has completed and fired its onComplete callback; a waiter whose
        // callback never fires lost its wakeup. Several waiters per round sample
        // the few-instruction window independently, so the race is hit reliably
        // and the buggy code fails on essentially every run, not just on average.
        // The threads are long-lived and coordinated by per-round barriers so the
        // release is near-simultaneous.
        "boundary pass must not lose the passAt wakeup (multi-threaded race)".timeout(120.seconds) in {
            Sync.defer {
                val target  = 2
                val waiters = 3
                val rounds  = 300000
                // (1 main + 1 passer + `waiters`)-party barriers. Crossing
                // `startGate` publishes the round's gate/done refs to every worker
                // with a happens-before edge; crossing `roundDone` republishes the
                // workers' results back to main. No data race on the shared refs.
                val parties    = waiters + 2
                val startGate  = new CyclicBarrier(parties)
                val roundDone  = new CyclicBarrier(parties)
                val lostWakeup = new AtomicInteger(0)

                var gate: Gate.Unsafe          = null
                var done: Array[AtomicBoolean] = null

                val passer = new Thread(() =>
                    var r = 0
                    while r < rounds do
                        startGate.await()
                        discard(gate.pass()) // boundary pass: target -> target + 1
                        roundDone.await()
                        r += 1
                    end while
                )
                passer.setDaemon(true)
                passer.start()

                val waiterThreads = (0 until waiters).map { w =>
                    val t = new Thread(() =>
                        var r = 0
                        while r < rounds do
                            startGate.await()
                            val f = gate.passAt(target)
                            f.onComplete(_ => done(w).set(true))
                            roundDone.await()
                            r += 1
                        end while
                    )
                    t.setDaemon(true)
                    t.start()
                    t
                }
                discard(waiterThreads)

                var r = 0
                while r < rounds do
                    val g = Gate.Unsafe.init(1)
                    var i = 0
                    while i < target do
                        discard(g.pass()) // advance to phase == target
                        i += 1
                    gate = g
                    done = Array.fill(waiters)(new AtomicBoolean(false))
                    startGate.await() // release passer + waiters onto this round's gate
                    roundDone.await() // all workers finished this round
                    // Phase is now target + 1 (boundary pass ran). Each waiter's
                    // correct passAt has completed and fired its callback. When a
                    // waiter registered just before the boundary pass, its callback
                    // fires from the passer's promise completion and can land just
                    // after roundDone, so settle each unfired flag with a bounded
                    // spin: the fixed code flips it within a few iterations; a lost
                    // wakeup never flips it.
                    if g.passCount() == target + 1 then
                        var w = 0
                        while w < waiters do
                            if !done(w).get() then
                                var spins = 0
                                while !done(w).get() && spins < 1_000_000 do
                                    spins += 1
                                if !done(w).get() then
                                    discard(lostWakeup.incrementAndGet())
                            end if
                            w += 1
                        end while
                    end if
                    r += 1
                end while

                assert(
                    lostWakeup.get() == 0,
                    s"passAt lost ${lostWakeup.get()} wakeup(s) across $rounds boundary races ($waiters waiters each)"
                )
            }
        }
    }
end GateJvmTest
