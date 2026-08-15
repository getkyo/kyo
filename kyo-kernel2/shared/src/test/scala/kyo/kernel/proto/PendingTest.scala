package kyo.kernel.proto

import kyo.Const
import kyo.Loop
import kyo.Loop.Outcome
import kyo.Tag
import org.scalatest.freespec.AnyFreeSpec

class PendingTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    sealed trait Give extends ArrowEffect[Const[Unit], Const[Int < Ask]]
    def give: (Int < Ask) < Give = ArrowEffect.suspend[Any](Tag[Give], ())

    def continue[A, O](v: A): Outcome[A, O] < Any =
        Loop.continue[A, O, Any](v).asInstanceOf[Outcome[A, O] < Any]

    def answerAsk[A](value: Int)(v: A < Ask): A < Any =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => continue(value), a => a)

    def answerSay[A](v: A < Say): A < Any =
        ArrowEffect.handleLoop(Tag[Say], v)([C] => _ => continue(()), a => a)

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
            ArrowEffect.handleLoop(Tag[Ask], outer)([C] => _ => continue(1), a => settled(a))
        val payload: Unit < Say = Eval(handled)
        var seen                = ""
        val r: Unit < Any = ArrowEffect.handleLoop(Tag[Say], payload)(
            [C] =>
                s =>
                    seen = s
                    continue(())
            ,
            a => a
        )
        assert(Eval(r) == ())
        assert(seen == "x")
    }

    "a region returns a foreign payload untouched" in {
        val body: (Unit < Say) < Ask = after(say("y"): Unit < Say)
        val handled: (Unit < Say) < Any =
            ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => continue(1), a => settled(a))
        val payload: Unit < Say = Eval(handled)
        var seen                = ""
        val r: Unit < Any = ArrowEffect.handleLoop(Tag[Say], payload)(
            [C] =>
                s =>
                    seen = s
                    continue(())
            ,
            a => a
        )
        assert(Eval(r) == ())
        assert(seen == "y")
    }

    "an answer can be a computation value" in {
        val inner: Int < Ask = ask
        val body: Int < Give = give.map(_ => 5)
        val r: Int < Any     = ArrowEffect.handleLoop(Tag[Give], body)([C] => _ => continue(settled(inner)), a => a)
        assert(Eval(r) == 5)
    }

    "a captured continuation accepts a computation answer" in {
        val inner: Int < Ask = ask
        val body: Int < Give = give.map(_ => 9)
        val r: Int < Any     = ArrowEffect.handle(Tag[Give], body)([C] => (_, cont) => cont(inner), a => a)
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

    "double nesting round trips" in {
        val inner: Int < Ask                 = ask.map(_ + 1)
        val twice: ((Int < Ask) < Any) < Any = settled(settled(inner))
        val payload: Int < Ask               = Eval(Eval(twice))
        assert(Eval(answerAsk(41)(payload)) == 42)
    }

end PendingTest
