package kyo.proto.kernel.internal

import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.proto.Loop
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import org.scalatest.freespec.AnyFreeSpec

/** The eval pins that spawn real threads, jvm only for that reason: everything else about the eval is
  * shared, and this is the one genuine platform split, the way `SafepointConcurrencyTest` is for the
  * reference kernel.
  */
class EvalConcurrencyTest extends AnyFreeSpec:
    private def eval[A, S](v: A < S): A =
        Nested.unnest[A](Eval(v))

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    def stateful(body: Int < (Ask & Say)): Int < Say =
        ArrowEffect.handleLoopState(Tag[Ask], 0, body)([C] => (s, _) => Loop.continue(s + 1, s: Int < Any), (_, a) => a)

    "a captured continuation resumes on other threads, each shot independent" in {
        var stored: Maybe[Unit => Int < Say] = Maybe.empty
        val inner: Int < Say                 = stateful(ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b))))
        val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)(
            [C] =>
                (_, cont) =>
                    stored = Maybe(cont(_))
                    -1
            ,
            a => a
        )
        assert(eval(r) == -1)
        val k             = stored.get
        def resume(): Int = eval(ArrowEffect.handleCont(Tag[Say], k(()))([C] => (_, cont) => cont(()), a => a))
        val results       = new java.util.concurrent.ConcurrentLinkedQueue[Int]()
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
