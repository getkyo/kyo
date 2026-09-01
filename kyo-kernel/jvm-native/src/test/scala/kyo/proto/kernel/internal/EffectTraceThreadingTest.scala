package kyo.proto.kernel.internal

import kyo.Const
import kyo.Tag
import kyo.discard
import kyo.proto.Loop
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import org.scalatest.freespec.AnyFreeSpec

class EffectTraceThreadingTest extends AnyFreeSpec:

    private def eval[A](v: A < Any): A = v.eval

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue((), value: Int < Any), a => a)

    class Boom extends RuntimeException("boom")

    "concurrent attach on a shared exception instance stays bounded and non-throwing" in {
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
                    try discard(eval(answerAsk(1)(ask.map(_ => (throw shared): Int))))
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
