package kyo.kernel.internal

import kyo.Const
import kyo.Tag
import kyo.kernel.*
import org.scalatest.freespec.AnyFreeSpec

class EvalSmokeTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    "settled value" in {
        assert((42: Int < Any).eval == 42)
    }

    "map chain" in {
        assert((1: Int < Any).map(_ + 1).map(_ * 3).eval == 6)
    }

    "andThen and unit" in {
        assert((1: Int < Any).andThen(2: Int < Any).eval == 2)
        assert((1: Int < Any).unit.eval == ())
    }

    "evalNow" in {
        assert((1: Int < Any).evalNow.contains(1))
        assert(ask.evalNow.isEmpty)
    }

    "handleLoop answers every operation in place" in {
        def loop(i: Int): Int < Ask =
            if i < 3 then ask.map(a => loop(i + a)) else i
        val r = ArrowEffect.handleLoop(Tag[Ask], loop(0))([C] => _ => Loop.continue(1))
        assert(r.eval == 3)
    }

    "Loop.done stops the region" in {
        val r = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 100))([C] => _ => Loop.done(42))
        assert(r.eval == 42)
    }

    "effectful answer on the settled outcome path" in {
        val r = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.continue(Effect.defer(7)))
        assert(r.eval == 8)
    }

    "pending clause outcome" in {
        def loop(i: Int): Int < Ask =
            if i < 3 then ask.map(a => loop(i + a)) else i
        val r = ArrowEffect.handleLoop(Tag[Ask], loop(0))([C] => _ => Effect.defer(Loop.continue(1)))
        assert(r.eval == 3)
    }

    "handle answers every operation with the continuation in hand" in {
        val body = ask.map(a => ask.map(b => a + b))
        val r    = ArrowEffect.handle(Tag[Ask], body)([C] => (_, cont) => cont(10))
        assert(r.eval == 20)
    }

    "handle passes a settled body through" in {
        val r = ArrowEffect.handle(Tag[Ask], 5: Int < Ask)([C] => (_, cont) => cont(-1))
        assert(r.eval == 5)
    }

    "the captured continuation is multi-shot" in {
        val body = ask.map(_ * 2)
        val r    = ArrowEffect.handle(Tag[Ask], body)([C] => (_, cont) => cont(1).map(x => cont(2).map(y => x + y)))
        assert(r.eval == 6)
    }

    "a first-operation peel is handleLoop with Loop.done carrying the continuation" in {
        val body = ask.map(a => ask.map(b => a + b))
        def peel(v: Int < Ask): (Int => Int < Ask) < Any =
            ArrowEffect.handleLoop(Tag[Ask], (), v)(
                handle = [C] => (_, _, cont) => Loop.done(cont),
                done = (_, a) => (_: Int) => (a: Int < Ask)
            )
        val k = peel(body).eval
        val r = ArrowEffect.handleLoop(Tag[Ask], k(10))([C] => _ => Loop.continue(1))
        assert(r.eval == 11)
    }

    "stateful handleLoop threads state at the edge" in {
        def loop(i: Int, acc: List[Int]): List[Int] < Ask =
            if i < 3 then ask.map(a => loop(i + 1, a :: acc)) else acc.reverse
        val r = ArrowEffect.handleLoop(Tag[Ask], 10, loop(0, Nil))(
            handle = [C] => (_, state, cont) => Loop.continue(state + 1, cont(state)),
            done = (state, a) => (state, a)
        )
        assert(r.eval == (13, List(10, 11, 12)))
    }

    "stateful handleLoop without done discards the state" in {
        val r = ArrowEffect.handleLoop(Tag[Ask], 5, ask.map(_ + 1))(
            handle = [C] => (_, state, cont) => Loop.continue(state + 1, cont(state))
        )
        assert(r.eval == 6)
    }

    "foreign operations cross an inner region" in {
        val body  = say("a").andThen(ask).map(i => say(s"b$i").andThen(i + 1))
        val inner = ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => Loop.continue(7))
        val r = ArrowEffect.handleLoop(Tag[Say], List.empty[String], inner)(
            handle = [C] => (s, acc, cont) => Loop.continue(s :: acc, cont(())),
            done = (acc, a) => (acc.reverse, a)
        )
        assert(r.eval == (List("a", "b7"), 8))
    }

    "the innermost region of a tag answers" in {
        val inner = ArrowEffect.handleLoop(Tag[Ask], ask)([C] => _ => Loop.continue(1))
        val r     = ArrowEffect.handleLoop(Tag[Ask], inner)([C] => _ => Loop.continue(2))
        assert(r.eval == 1)
    }

    "a nested eval shares the stack safely" in {
        val inner = ArrowEffect.handleLoop(Tag[Ask], ask)([C] => _ => Loop.continue(5))
        val r     = ArrowEffect.handleLoop(Tag[Ask], ask.map(a => a + inner.eval))([C] => _ => Loop.continue(1))
        assert(r.eval == 6)
    }

    "a nested computation stays data until flattened" in {
        val nested: (Int < Ask) < Any = kyo.Kyo.lift(ask.map(_ + 1))
        val r                         = ArrowEffect.handleLoop(Tag[Ask], nested.flatten)([C] => _ => Loop.continue(1))
        assert(r.eval == 2)
    }

    "catching recovers a thrown step" in {
        val r = Effect.catching(Effect.defer((throw new RuntimeException("boom")): Int < Any))(_ => 42)
        assert(r.eval == 42)
    }

    "catching passes through success" in {
        val r = Effect.catching((1: Int < Any).map(_ + 1))(_ => -1)
        assert(r.eval == 2)
    }

    "unhandled suspension reports a bug" in {
        val ex = intercept[Throwable](ask.asInstanceOf[Int < Any].eval)
        assert(ex.getMessage.contains("unhandled suspension"))
    }

end EvalSmokeTest
