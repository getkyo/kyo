package kyo.proto.kernel.internal

import java.util.concurrent.atomic.AtomicInteger
import kyo.Const
import kyo.Tag
import kyo.discard
import kyo.proto.Loop
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import org.scalatest.freespec.AnyFreeSpec

class EffectTraceThreadingTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue((), value: Int < Any), a => a)

    class Boom extends RuntimeException("boom")

    "concurrent attach on a shared exception instance stays bounded and non-throwing" in {
        var round = 0
        while round < 200 do
            val shared           = new Boom
            val gate             = new AtomicInteger(0)
            @volatile var failed = false
            def runner(): Thread = new Thread(() =>
                discard(gate.incrementAndGet())
                while gate.get() < 2 do ()
                var i = 0
                while i < 3 do
                    try discard(answerAsk(1)(ask.map(_ => (throw shared): Int)).eval)
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

    "a shared exception keeps its construction site's physical frames across a splice on another thread" in {
        val shared = new Boom
        def spliceHere(): Unit =
            try discard(answerAsk(1)(ask.map(_ => (throw shared): Int)).eval)
            catch case _: Boom => ()
        def physical(): List[StackTraceElement] =
            shared.getStackTrace.toList.filterNot(_.getClassName.startsWith("kyo.proto."))
        spliceHere()
        val afterFirst = physical()
        assert(afterFirst.nonEmpty)
        @volatile var afterSecond = List.empty[StackTraceElement]
        val t = new Thread(
            () =>
                spliceHere()
                afterSecond = physical()
            ,
            "second-splicer"
        )
        t.start()
        t.join(10000)
        assert(!t.isAlive)
        assert(afterSecond == afterFirst)
        assert(shared.getSuppressed.count(_.isInstanceOf[EffectTrace]) == 1)
    }

end EffectTraceThreadingTest
