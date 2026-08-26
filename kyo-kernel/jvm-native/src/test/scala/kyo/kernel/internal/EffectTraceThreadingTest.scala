package kyo.kernel.internal

import kyo.Const
import kyo.Tag
import kyo.discard
import kyo.kernel.*

/** The trace-attach race needs real threads; the cross-platform trace coverage stays in the shared
  * EffectTraceTest.
  */
class EffectTraceThreadingTest extends kyo.Test:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value))

    class Boom extends RuntimeException("boom")

    "concurrent attach on a shared exception instance stays bounded and non-throwing" in {
        // two threads throw the SAME pre-allocated exception through separate evals with distinct
        // region stacks; the trace machinery must never throw itself, and the shared carrier must
        // stay bounded: exactly one EffectTrace suppressed, last-writer-wins, never torn.
        // The only real race window on an instance is its FIRST attach: once a carrier is
        // installed, find returns it forever after. So the scenario loops over fresh instances,
        // with a spin gate releasing both threads into the window together, rather than hammering
        // one instance whose window passed on the first iteration
        var round = 0
        while round < 200 do
            val shared           = new Boom
            val gate             = new java.util.concurrent.atomic.AtomicInteger(0)
            @volatile var failed = false
            def runner(): Thread = new Thread(() =>
                discard(gate.incrementAndGet())
                while gate.get() < 2 do ()
                var i = 0
                while i < 3 do
                    try discard(Eval(answerAsk(1)(ask.map(_ => (throw shared): Int))))
                    catch
                        case b: Boom      => ()
                        case _: Throwable => failed = true
                    end try
                    i += 1
                end while
            )
            val t1 = runner()
            val t2 = runner()
            t1.start(); t2.start()
            t1.join(10000); t2.join(10000)
            assert(!t1.isAlive && !t2.isAlive)
            assert(!failed, s"round=$round")
            assert(shared.getSuppressed.count(_.isInstanceOf[EffectTrace]) == 1, s"round=$round")
            round += 1
        end while
    }

end EffectTraceThreadingTest
