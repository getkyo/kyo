package kyo.kernel

import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.kernel.internal.Eval
import org.scalatest.freespec.AnyFreeSpec

class PendingTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    sealed trait Give extends ArrowEffect[Const[Unit], Const[Int < Ask]]
    def give: (Int < Ask) < Give = ArrowEffect.suspend[Any](Tag[Give], ())

    def answerAsk[A](value: Int)(v: A < Ask): A < Any =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value), a => a)

    def answerSay[A](v: A < Say): A < Any =
        ArrowEffect.handleLoop(Tag[Say], v)([C] => _ => Loop.continue(()), a => a)

    def settled[A](v: A): A < Any = v

    def after[A](v: A): A < Ask = ask.map(_ => v)

    "a settled payload evaluates to itself" in {
        val outer: (Int < Any) < Any = settled(42: Int < Any)
        assert(Eval(Eval(outer)) == 42)
    }

    "eval returns a pending payload without running it" in {
        val inner: Int < Ask   = ask.map(_ + 1)
        val payload: Int < Ask = Eval(settled(inner))
        assert(Eval(answerAsk(41)(payload)) == 42)
    }

    "map receives a pending payload as a value" in {
        val inner: Int < Ask    = ask
        var received: Int < Ask = 0
        val r: Int < Any = settled(inner).map { c =>
            received = c
            7
        }
        assert(Eval(r) == 7)
        assert(Eval(answerAsk(41)(received.map(_ + 1))) == 42)
    }

    "a map can return a computation as its value" in {
        val inner: Int < Ask     = ask.map(_ + 1)
        val r: (Int < Ask) < Ask = after(inner)
        val payload: Int < Ask   = Eval(answerAsk(1)(r))
        assert(Eval(answerAsk(41)(payload)) == 42)
    }

    "a payload returned past the budget stays a value" in {
        def loop(i: Int): (Int < Ask) < Any =
            if i == 0 then settled(ask)
            else (0: Int < Any).map(_ => loop(i - 1))
        val payload: Int < Ask = Eval(loop(10000))
        assert(Eval(answerAsk(42)(payload)) == 42)
    }

    "a handler applies done to a settled payload without driving it" in {
        val outer: (Unit < Say) < Ask = settled(say("x"): Unit < Say)
        val handled: (Unit < Say) < Any =
            ArrowEffect.handleLoop(Tag[Ask], outer)([C] => _ => Loop.continue(1), a => settled(a))
        val payload: Unit < Say = Eval(handled)
        var seen                = ""
        val r: Unit < Any = ArrowEffect.handleLoop(Tag[Say], payload)(
            [C] =>
                s =>
                    seen = s
                    Loop.continue(())
            ,
            a => a
        )
        assert(Eval(r) == ())
        assert(seen == "x")
    }

    "a region returns a foreign payload untouched" in {
        val body: (Unit < Say) < Ask = after(say("y"): Unit < Say)
        val handled: (Unit < Say) < Any =
            ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => Loop.continue(1), a => settled(a))
        val payload: Unit < Say = Eval(handled)
        var seen                = ""
        val r: Unit < Any = ArrowEffect.handleLoop(Tag[Say], payload)(
            [C] =>
                s =>
                    seen = s
                    Loop.continue(())
            ,
            a => a
        )
        assert(Eval(r) == ())
        assert(seen == "y")
    }

    "an answer can be a computation value" in {
        val inner: Int < Ask = ask
        val body: Int < Give = give.map(_ => 5)
        val r: Int < Any     = ArrowEffect.handleLoop(Tag[Give], body)([C] => _ => Loop.continue(settled(inner)), a => a)
        assert(Eval(r) == 5)
    }

    "a captured continuation accepts a computation answer" in {
        val inner: Int < Ask = ask
        val body: Int < Give = give.map(_ => 9)
        val r: Int < Any     = ArrowEffect.handleCont(Tag[Give], body)([C] => (_, cont) => cont(inner), a => a)
        assert(Eval(r) == 9)
    }

    "mapping over a payload derives a new payload" in {
        val inner: Int < Ask           = ask
        val derived: (Int < Ask) < Any = settled(inner).map(c => settled(c.map(_ * 2)))
        val payload: Int < Ask         = Eval(derived)
        assert(Eval(answerAsk(21)(payload)) == 42)
    }

    "a payload handles inside map" in {
        def deliver[B](f: Int => B): B < Ask = ask.map(a => f(a))
        val comp: (Int < Say) < Ask          = deliver(a => say("s").map(_ => a + 1))
        val handled: (Int < Any) < Any       = answerAsk(10)(comp.map(c => settled(answerSay[Int](c))))
        assert(Eval(Eval(handled)) == 11)
    }

    "a generic function nests its result across effects" in {
        def f(a: Int): Int < Say       = say("x").map(_ => a + 5)
        def g[B](f: Int => B): B < Ask = ask.map(a => f(a))
        val nested: (Int < Say) < Ask  = g(f)
        val payload: Int < Say         = Eval(answerAsk(1)(nested))
        assert(Eval(answerSay(payload)) == 6)
    }

    "a loop can end its region with a computation result" in {
        val inner: Int < Ask = ask.map(_ + 1)
        val body: Int < Give = give.map(_ => 0)
        val r: (Int < Ask) < Any = ArrowEffect.handleLoop(Tag[Give], body)(
            [C] => _ => Loop.done(inner),
            a => settled(inner)
        )
        val payload: Int < Ask = Eval(r)
        assert(Eval(answerAsk(41)(payload)) == 42)
    }

    "a fused continuation receives the answer payload" in {
        val inner: Int < Ask = ask
        var got: Int < Ask   = 0
        val body: Int < Give = ArrowEffect.suspendWith[Any](Tag[Give], ()) { c =>
            got = c
            3
        }
        val r: Int < Any = ArrowEffect.handleLoop(Tag[Give], body)([C] => _ => Loop.continue(settled(inner)), a => a)
        assert(Eval(r) == 3)
        assert(Eval(answerAsk(41)(got.map(_ + 1))) == 42)
    }

    "double nesting round trips" in {
        val inner: Int < Ask                 = ask.map(_ + 1)
        val twice: ((Int < Ask) < Any) < Any = settled(settled(inner))
        val payload: Int < Ask               = Eval(Eval(twice))
        assert(Eval(answerAsk(41)(payload)) == 42)
    }

    "a loop answer payload delivers unwrapped through a bare suspension" in {
        val inner: Int < Ask = ask.map(_ + 1)
        val r: (Int < Ask) < Any =
            ArrowEffect.handleLoop(Tag[Give], give)([C] => _ => Loop.continue(settled(inner)), a => settled(a))
        val payload: Int < Ask = Eval(r)
        assert(Eval(answerAsk(41)(payload)) == 42)
    }

    "a suspended loop answer delivering a payload resumes unwrapped" in {
        val inner: Int < Ask = ask.map(_ + 1)
        val handled: (Int < Ask) < Ask =
            ArrowEffect.handleLoop(Tag[Give], give)([C] => _ => Loop.continue(after(inner)), a => settled(a))
        val payload: Int < Ask = Eval(answerAsk(0)(handled))
        assert(Eval(answerAsk(41)(payload)) == 42)
    }

    "a stateful loop answer payload delivers unwrapped through a bare suspension" in {
        val inner: Int < Ask = ask.map(_ + 1)
        val r: (Int < Ask) < Any =
            ArrowEffect.handleLoopState(Tag[Give], 0, give)(
                [C] => (s, _) => Loop.continue(s + 1, settled(inner)),
                (_, a) => settled(a)
            )
        val payload: Int < Ask = Eval(r)
        assert(Eval(answerAsk(41)(payload)) == 42)
    }

    "a loop can end its region effectfully with a computation result" in {
        val inner: Int < Ask = ask.map(_ + 1)
        val r: (Int < Ask) < Ask = ArrowEffect.handleLoop(Tag[Give], give)(
            [C] => _ => after(0).map(_ => Loop.done(inner)),
            a => settled(a)
        )
        val payload: Int < Ask = Eval(answerAsk(0)(r))
        assert(Eval(answerAsk(41)(payload)) == 42)
    }

    "a fused handler continuation receives a payload as a value" in {
        val inner: Int < Ask = ask.map(_ + 1)
        var got: Int < Ask   = 0
        val r: Int < Any = ArrowEffect.handleLoopWith(Tag[Give], give)(
            [C] => _ => Loop.continue(settled(inner)),
            a => settled(a)
        ) { b =>
            got = b
            9
        }
        assert(Eval(r) == 9)
        assert(Eval(answerAsk(41)(got)) == 42)
    }

    "flatMap chains a settled value into an effectful computation" in {
        val r: Int < Ask = (5: Int < Ask).flatMap(a => ask.map(_ + a))
        assert(Eval(answerAsk(1)(r)) == 6)
    }

    "flatMap receives a pending payload as a value" in {
        val inner: Int < Ask    = ask
        var received: Int < Ask = 0
        val r: Int < Any = settled(inner).flatMap { c =>
            received = c
            7
        }
        assert(Eval(r) == 7)
        assert(Eval(answerAsk(41)(received.map(_ + 1))) == 42)
    }

    "andThen sequences effects and discards the value" in {
        var ran = false
        val r: Int < Ask = ask.andThen {
            ran = true
            ask.map(_ + 1)
        }
        assert(Eval(answerAsk(41)(r)) == 42)
        assert(ran)
    }

    "andThen leaves a discarded payload untouched" in {
        val inner: Int < Ask = ask
        val r: Int < Any     = settled(inner).andThen(7)
        assert(Eval(r) == 7)
    }

    "unit discards the result" in {
        assert(Eval((42: Int < Any).unit) == ())
        assert(Eval(answerAsk(1)(ask.unit)) == ())
    }

    "eval returns the settled result" in {
        assert((42: Int < Any).eval == 42)
        assert((1: Int < Any).map(_ + 1).eval == 2)
    }

    "evalNow returns a settled value" in {
        assert((42: Int < Any).evalNow == Maybe(42))
    }

    "evalNow is absent for a suspended computation" in {
        assert(ask.evalNow == Maybe.Absent)
    }

    "evalNow returns a payload unwrapped" in {
        val inner: Int < Ask   = ask.map(_ + 1)
        val payload: Int < Ask = settled(inner).evalNow.getOrElse(0)
        assert(Eval(answerAsk(41)(payload)) == 42)
    }

    "flatten runs a nested payload" in {
        val inner: Int < Ask = ask.map(_ + 1)
        assert(Eval(answerAsk(41)(settled(inner).flatten)) == 42)
    }

    "flatten merges the effects of both layers" in {
        val inner: Int < Ask          = ask.map(_ + 1)
        val nested: (Int < Ask) < Ask = after(inner)
        assert(Eval(answerAsk(20)(nested.flatten)) == 21)
    }

    "handle applies transformations fluently" in {
        assert(ask.map(_ + 1).handle(v => answerAsk(41)(v)).handle(v => Eval(v)) == 42)
        assert(ask.handle(v => answerAsk(1)(v), v => Eval(v)) == 1)
        val ten: Int = (0: Int < Any).handle(
            v => v.map(_ + 1),
            v => v.map(_ + 1),
            v => v.map(_ + 1),
            v => v.map(_ + 1),
            v => v.map(_ + 1),
            v => v.map(_ + 1),
            v => v.map(_ + 1),
            v => v.map(_ + 1),
            v => v.map(_ + 1),
            v => Eval(v)
        )
        assert(ten == 9)
    }

end PendingTest
