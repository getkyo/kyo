package kyo.kernel.internal

import java.util.concurrent.ConcurrentLinkedQueue
import kyo.Const
import kyo.Loop
import kyo.Maybe
import kyo.Tag
import kyo.kernel.<
import kyo.kernel.ArrowEffect
import kyo.kernel.Region
import org.scalatest.freespec.AnyFreeSpec

class EvalConcurrencyTest extends AnyFreeSpec:
    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    def stateful(body: Int < (Ask & Say)): Int < Say =
        ArrowEffect.handleLoopState(Tag[Ask], 0, body)([C] => (s, _) => Loop.continue(s + 1, s), (_, a) => a)

    "a captured continuation resumes on other threads, each shot independent" in {
        var stored: Maybe[Unit => Int < Say] = Maybe.empty
        val inner: Int < Say                 = stateful(ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b))))
        val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)(
            [C] =>
                (_, cont) =>
                    stored = Maybe(Region.leak(cont)(_))
                    -1
            ,
            a => a
        )
        assert(r.eval == -1)
        val k             = stored.get
        def resume(): Int = ArrowEffect.handleCont(Tag[Say], k(()))([C] => (_, cont) => cont(()), a => a).eval
        val results       = new ConcurrentLinkedQueue[Int]()
        val threads = (1 to 4).map(_ =>
            new Thread(() =>
                results.add(resume()); ()
            )
        )
        threads.foreach(_.start())
        threads.foreach(_.join())
        assert(List.fill(4)(results.poll()) == List(1, 1, 1, 1))
        assert(results.isEmpty)
    }
end EvalConcurrencyTest
