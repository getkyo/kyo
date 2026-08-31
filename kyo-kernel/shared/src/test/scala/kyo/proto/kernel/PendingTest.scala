package kyo.proto.kernel

import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.Kyo
import kyo.proto.Loop
import kyo.proto.kernel.internal.Eval
import kyo.proto.kernel.internal.Nested
import kyo.proto.kernel.internal.Pending
import kyo.proto.kernel.internal.Safepoint
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec
import scala.compiletime.testing.typeCheckErrors

class PendingTest extends AnyFreeSpec:

    private def eval[A](v: A < Any): A = v.eval

    private val Period = Safepoint.period()

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    sealed trait Give extends ArrowEffect[Const[Unit], Const[Int < Ask]]
    def give: (Int < Ask) < Give = ArrowEffect.suspend[Any](Tag[Give], ())

    def answerAsk[A](value: Int)(v: A < Ask): A < Any =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue((), value: Int < Any), a => a)

    def answerSay[A](v: A < Say): A < Any =
        ArrowEffect.handleLoop(Tag[Say], v)([C] => _ => Loop.continue((), (): Unit < Any), a => a)

    def settled[A](v: A): A < Any = v

    def after[A](v: A): A < Ask = ask.map(_ => v)

    "a settled payload evaluates to itself" in {
        val outer: (Int < Any) < Any = settled(42: Int < Any)
        assert(eval(eval(outer)) == 42)
    }

    "eval returns a pending payload without running it" in {
        val inner: Int < Ask   = ask.map(_ + 1)
        val payload: Int < Ask = eval(settled(inner))
        assert(eval(answerAsk(41)(payload)) == 42)
    }

    "map receives a pending payload as a value" in {
        val inner: Int < Ask    = ask
        var received: Int < Ask = 0
        val r: Int < Any = settled(inner).map { c =>
            received = c
            7
        }
        assert(eval(r) == 7)
        assert(eval(answerAsk(41)(received.map(_ + 1))) == 42)
    }

    "a map can return a computation as its value" in {
        val inner: Int < Ask     = ask.map(_ + 1)
        val r: (Int < Ask) < Ask = after(inner)
        val payload: Int < Ask   = eval(answerAsk(1)(r))
        assert(eval(answerAsk(41)(payload)) == 42)
    }

    "a payload returned past the budget stays a value" in {
        def loop(i: Int): (Int < Ask) < Any =
            if i == 0 then settled(ask)
            else (0: Int < Any).map(_ => loop(i - 1))
        val payload: Int < Ask = eval(loop(10000))
        assert(eval(answerAsk(42)(payload)) == 42)
    }

    "a handler applies done to a settled payload without driving it" in {
        val outer: (Unit < Say) < Ask = settled(say("x"): Unit < Say)
        val handled: (Unit < Say) < Any =
            ArrowEffect.handleLoop(Tag[Ask], outer)([C] => _ => Loop.continue((), 1: Int < Any), a => settled(a))
        val payload: Unit < Say = eval(handled)
        var seen                = ""
        val r: Unit < Any = ArrowEffect.handleLoop(Tag[Say], payload)(
            [C] =>
                s =>
                    seen = s
                    Loop.continue((), (): Unit < Any)
            ,
            a => a
        )
        assert(eval(r) == ())
        assert(seen == "x")
    }

    "a region returns a foreign payload untouched" in {
        val body: (Unit < Say) < Ask = after(say("y"): Unit < Say)
        val handled: (Unit < Say) < Any =
            ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => Loop.continue((), 1: Int < Any), a => settled(a))
        val payload: Unit < Say = eval(handled)
        var seen                = ""
        val r: Unit < Any = ArrowEffect.handleLoop(Tag[Say], payload)(
            [C] =>
                s =>
                    seen = s
                    Loop.continue((), (): Unit < Any)
            ,
            a => a
        )
        assert(eval(r) == ())
        assert(seen == "y")
    }

    "an answer can be a computation value" in {
        val inner: Int < Ask = ask
        val body: Int < Give = give.map(_ => 5)
        val r: Int < Any     = ArrowEffect.handleLoop(Tag[Give], body)([C] => _ => Loop.continue((), settled(inner)), a => a)
        assert(eval(r) == 5)
    }

    "a captured continuation accepts a computation answer" in {
        val inner: Int < Ask = ask
        val body: Int < Give = give.map(_ => 9)
        val r: Int < Any     = ArrowEffect.handleCont(Tag[Give], body)([C] => (_, cont) => cont(inner), a => a)
        assert(eval(r) == 9)
    }

    "mapping over a payload derives a new payload" in {
        val inner: Int < Ask           = ask
        val derived: (Int < Ask) < Any = settled(inner).map(c => settled(c.map(_ * 2)))
        val payload: Int < Ask         = eval(derived)
        assert(eval(answerAsk(21)(payload)) == 42)
    }

    "a payload handles inside map" in {
        def deliver[B](f: Int => B): B < Ask = ask.map(a => f(a))
        val comp: (Int < Say) < Ask          = deliver(a => say("s").map(_ => a + 1))
        val handled: (Int < Any) < Any       = answerAsk(10)(comp.map(c => settled(answerSay[Int](c))))
        assert(eval(eval(handled)) == 11)
    }

    "a generic function nests its result across effects" in {
        def f(a: Int): Int < Say       = say("x").map(_ => a + 5)
        def g[B](f: Int => B): B < Ask = ask.map(a => f(a))
        val nested: (Int < Say) < Ask  = g(f)
        val payload: Int < Say         = eval(answerAsk(1)(nested))
        assert(eval(answerSay(payload)) == 6)
    }

    "a pure function passes to map point-free" in {
        assertCompiles("""
            val f: Int => Int    = _ + 1
            val r: Int < Ask     = ask.map(f)
            ()
        """)
    }

    "a generic function passes to map point-free" in {
        assertCompiles("""
            def f(a: Int): Int < Say       = say("x").map(_ => a + 5)
            def g[B](f: Int => B): B < Ask = ask.map(f)
            val nested: (Int < Say) < Ask  = g(f)
            ()
        """)
    }

    "a loop can end its region with a computation result" in {
        val inner: Int < Ask = ask.map(_ + 1)
        val body: Int < Give = give.map(_ => 0)
        val r: (Int < Ask) < Any = ArrowEffect.handleLoop(Tag[Give], body)(
            [C] => _ => Loop.done(Kyo.lift(inner)),
            a => settled(inner)
        )
        val payload: Int < Ask = eval(r)
        assert(eval(answerAsk(41)(payload)) == 42)
    }

    "a fused continuation receives the answer payload" in {
        val inner: Int < Ask = ask
        var got: Int < Ask   = 0
        val body: Int < Give = ArrowEffect.suspendWith[Any](Tag[Give], ()) { c =>
            got = c
            3
        }
        val r: Int < Any = ArrowEffect.handleLoop(Tag[Give], body)([C] => _ => Loop.continue((), settled(inner)), a => a)
        assert(eval(r) == 3)
        assert(eval(answerAsk(41)(got.map(_ + 1))) == 42)
    }

    "double nesting round trips" in {
        val inner: Int < Ask                 = ask.map(_ + 1)
        val twice: ((Int < Ask) < Any) < Any = settled(settled(inner))
        val payload: Int < Ask               = eval(eval(twice))
        assert(eval(answerAsk(41)(payload)) == 42)
    }

    "a loop answer payload delivers unwrapped through a bare suspension" in {
        val inner: Int < Ask = ask.map(_ + 1)
        val r: (Int < Ask) < Any =
            ArrowEffect.handleLoop(Tag[Give], give)([C] => _ => Loop.continue((), settled(inner)), a => settled(a))
        val payload: Int < Ask = eval(r)
        assert(eval(answerAsk(41)(payload)) == 42)
    }

    "a suspended loop answer delivering a payload resumes unwrapped" in {
        val inner: Int < Ask = ask.map(_ + 1)
        val handled: (Int < Ask) < Ask =
            ArrowEffect.handleLoop(Tag[Give], give)([C] => _ => Loop.continue((), after(inner)), a => settled(a))
        val payload: Int < Ask = eval(answerAsk(0)(handled))
        assert(eval(answerAsk(41)(payload)) == 42)
    }

    "a stateful loop answer payload delivers unwrapped through a bare suspension" in {
        val inner: Int < Ask = ask.map(_ + 1)
        val r: (Int < Ask) < Any =
            ArrowEffect.handleLoopState(Tag[Give], 0, give)(
                [C] => (s, _) => Loop.continue(s + 1, settled(inner)),
                (_, a) => settled(a)
            )
        val payload: Int < Ask = eval(r)
        assert(eval(answerAsk(41)(payload)) == 42)
    }

    "a loop can end its region effectfully with a computation result" in {
        val inner: Int < Ask = ask.map(_ + 1)
        val r: (Int < Ask) < Ask = ArrowEffect.handleLoop(Tag[Give], give)(
            [C] => _ => after(0).map(_ => Loop.done(Kyo.lift(inner))),
            a => settled(a)
        )
        val payload: Int < Ask = eval(answerAsk(0)(r))
        assert(eval(answerAsk(41)(payload)) == 42)
    }

    "a fused handler continuation receives a payload as a value" in {
        val inner: Int < Ask = ask.map(_ + 1)
        var got: Int < Ask   = 0
        val r: Int < Any = ArrowEffect.handleLoopWith[Const[Unit], Const[Int < Ask], Give, Int < Ask, Int < Ask, Any, Any](Tag[Give], give)(
            [C] => _ => Loop.continue((), settled(inner)),
            a => settled(a)
        ) { b =>
            got = b
            9
        }
        assert(eval(r) == 9)
        assert(eval(answerAsk(41)(got)) == 42)
    }

    "flatMap chains a settled value into an effectful computation" in {
        val r: Int < Ask = (5: Int < Ask).flatMap(a => ask.map(_ + a))
        assert(eval(answerAsk(1)(r)) == 6)
    }

    "flatMap receives a pending payload as a value" in {
        val inner: Int < Ask    = ask
        var received: Int < Ask = 0
        val r: Int < Any = settled(inner).flatMap { c =>
            received = c
            7
        }
        assert(eval(r) == 7)
        assert(eval(answerAsk(41)(received.map(_ + 1))) == 42)
    }

    "andThen sequences effects and discards the value" in {
        var ran = false
        val r: Int < Ask = ask.andThen {
            ran = true
            ask.map(_ + 1)
        }
        assert(eval(answerAsk(41)(r)) == 42)
        assert(ran)
    }

    "andThen leaves a discarded payload untouched" in {
        val inner: Int < Ask = ask
        val r: Int < Any     = settled(inner).andThen(7)
        assert(eval(r) == 7)
    }

    "unit discards the result" in {
        assert(eval((42: Int < Any).unit) == ())
        assert(eval(answerAsk(1)(ask.unit)) == ())
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
        assert(eval(answerAsk(41)(payload)) == 42)
    }

    "flatten runs a nested payload" in {
        val inner: Int < Ask = ask.map(_ + 1)
        assert(eval(answerAsk(41)(settled(inner).flatten)) == 42)
    }

    "flatten merges the effects of both layers" in {
        val inner: Int < Ask          = ask.map(_ + 1)
        val nested: (Int < Ask) < Ask = after(inner)
        assert(eval(answerAsk(20)(nested.flatten)) == 21)
    }

    "handle applies transformations fluently" in {
        assert(ask.map(_ + 1).handle(v => answerAsk(41)(v)).handle(v => eval(v)) == 42)
        assert(ask.handle(v => answerAsk(1)(v), v => eval(v)) == 1)
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
            v => eval(v)
        )
        assert(ten == 9)
    }

    "map on a settled value runs eagerly" in {
        var ran = false
        val v = (1: Int < Any).map { n =>
            ran = true
            n + 1
        }
        assert(ran)
        assert(v.eval == 2)
    }

    "map composes" in {
        val v: Int < Any = (1: Int < Any).map(_ + 1).map(_ * 10)
        assert(v.eval == 20)
    }

    "flatMap and andThen compose" in {
        val v = (1: Int < Any).flatMap(n => (n + 1: Int < Any)).andThen(10: Int < Any)
        assert(v.eval == 10)
    }

    "a computation as a value round-trips through the nested box" in {
        def box[A](v: A): A < Any    = v
        val inner: Int < Any         = (1: Int < Any).map(_ + 1)
        val outer: (Int < Any) < Any = box(inner)
        assert(outer.eval.eval == 2)
    }

    "a computation as a value survives mapping" in {
        def box[A](v: A): A < Any = v
        val inner: Int < Any      = (1: Int < Any).map(_ + 1)
        val v: Int < Any          = box(inner).map(c => c.map(_ * 10))
        assert(v.eval == 20)
    }

    "the extension surface applies to a val of nested type" in {
        def box[A](v: A): A < Any     = v
        val nested: (Int < Any) < Any = box(box(41))
        assert(nested.map(c => c).eval == 41)
        assert(nested.map(c => c.map(_ + 1)).eval == 42)
        assert(nested.evalNow.isDefined)
        assert(nested.flatten.eval == 41)
        assert(nested.unit.eval == ())
    }

    "a pending value does not lift into a nested computation implicitly" in {
        assertTypeError("val x: (Int < Any) < Any = (1: Int < Any).map(_ + 1)")
    }

    "a kyo module does not lift into a computation" in {
        assertTypeError("val x: ArrowEffect.type < Any = ArrowEffect")
    }

    "deep map chains evaluate" in {
        def chain(n: Int, v: Int < Any): Int < Any =
            if n == 0 then v else chain(n - 1, v.map(_ + 1))
        assert(chain(10000, 0).eval == 10000)
    }

    "deep nested computations evaluate" in {
        def loop(n: Int): Int < Any =
            if n == 0 then 0 else (n: Int < Any).map(_ => loop(n - 1))
        assert(loop(100000).eval == 0)
    }

    "construction past the safepoint budget rescues instead of overflowing" in {
        def loop(n: Int): Int < Any =
            if n == 0 then 0 else (n: Int < Any).map(_ => loop(n - 1))
        val v = loop(Period * 4)
        assert(v.eval == 0)
    }

    "a long map tower on a rescued computation evaluates in bounded stack" in {
        def loop(n: Int): Int < Any =
            if n == 0 then 0 else (n: Int < Any).map(_ => loop(n - 1))
        @tailrec def tower(v: Int < Any, n: Int): Int < Any =
            if n == 0 then v else tower(v.map(_ + 1), n - 1)
        val v = tower(loop(Period * 4), 1000000)
        assert(v.eval == 1000000)
    }

    "eval does not compile for pending effects" in {
        val errors = typeCheckErrors(
            "sealed trait CustomEffect extends ArrowEffect[Const[Unit], Const[Unit]]; val x: Int < CustomEffect = 5; x.eval"
        )
        assert(errors.nonEmpty, "expected a type error, code compiled")
    }

    "nested computations" - {
        sealed trait TestEffect1 extends ArrowEffect[Const[Int], Const[String]]
        object TestEffect1:
            def apply(i: Int): String < TestEffect1 =
                ArrowEffect.suspend[Any](Tag[TestEffect1], i)

            def run[A, S](v: A < (TestEffect1 & S)): A < S =
                ArrowEffect.handleCont(Tag[TestEffect1], v)(
                    [C] => (input, cont) => cont(s"Effect1:$input"),
                    a => a
                )
        end TestEffect1

        sealed trait TestEffect2 extends ArrowEffect[Const[String], Const[Int]]
        object TestEffect2:
            def apply(s: String): Int < TestEffect2 =
                ArrowEffect.suspend[Any](Tag[TestEffect2], s)

            def run[A, S](v: A < (TestEffect2 & S)): A < S =
                ArrowEffect.handleCont(Tag[TestEffect2], v)(
                    [C] => (input, cont) => cont(input.length + 10),
                    a => a
                )
        end TestEffect2

        def lifted[A](v: A): A < Any = v

        "basic nesting operations" in {
            val nested: String < TestEffect1 < Any = lifted(TestEffect1(42))
            assert(TestEffect1.run(nested.map(c => c)).eval == "Effect1:42")

            val result = lifted(TestEffect1(5)).map(_.map(s => TestEffect1(s.length)))
            assert(TestEffect1.run(result).eval == "Effect1:9")
        }

        "multiple effects" in {
            val comp: Int < TestEffect2 < TestEffect1 =
                lifted(TestEffect1(10)).map(_.map(s => lifted(TestEffect2(s))))

            val result = TestEffect1.run(comp.map(c => TestEffect2.run(c)))
            assert(result.eval == "Effect1:10".length + 10)

        }

        def drainedBudget[A](f: => A): A =
            val slot  = Safepoint.get()
            val saved = Safepoint.save(slot)
            while Safepoint.enter(slot) do ()
            try f
            finally Safepoint.restore(slot, saved)
        end drainedBudget

        "map over a nested value denied by the budget defers the wrapped value" in {
            val nested: Int < TestEffect2 < Any = lifted(TestEffect2("hello"))
            val deferred                        = drainedBudget(nested.map(c => TestEffect2.run(c)))
            assert(deferred.eval == "hello".length + 10)
        }

        "flatMap over a nested value denied by the budget defers the wrapped value" in {
            val nested: Int < TestEffect2 < Any = lifted(TestEffect2("hello"))
            val deferred                        = drainedBudget(nested.flatMap(_.handle(TestEffect2.run)))
            assert(deferred.eval == "hello".length + 10)
        }

        "flatten over a doubly nested value denied by the budget strips exactly one level" in {
            val inner: Int < TestEffect2              = TestEffect2("hello")
            val nested: Int < TestEffect2 < Any < Any = lifted(lifted(inner): Int < TestEffect2 < Any)
            val deferred: Int < TestEffect2 < Any     = drainedBudget(nested.flatten)
            val data                                  = deferred.eval
            assert(TestEffect2.run(data).eval == "hello".length + 10)
        }

        "andThen over a nested value denied by the budget discards it unevaluated" in {
            val nested: Int < TestEffect2 < Any = lifted(TestEffect2("hello"))
            val deferred                        = drainedBudget(nested.andThen(99: Int < Any))
            assert(deferred.eval == 99)
        }

        "unit over a nested value denied by the budget discards it unevaluated" in {
            val nested: Int < TestEffect2 < Any = lifted(TestEffect2("hello"))
            val deferred                        = drainedBudget(nested.unit)
            assert(deferred.eval == ())
        }

        "a doubly nested value denied by the budget strips exactly one level under map" in {
            val inner: Int < TestEffect2              = TestEffect2("hello")
            val nested: Int < TestEffect2 < Any < Any = lifted(lifted(inner): Int < TestEffect2 < Any)
            val deferred: Int < TestEffect2 < Any     = drainedBudget(nested.map(c => c))
            val data                                  = deferred.eval
            assert(TestEffect2.run(data).eval == "hello".length + 10)
        }

        "map on nested" in {
            val nested: String < TestEffect1 < Any = lifted(TestEffect1(50))

            val mapped: Int < TestEffect1 = nested.map(_.map(_.length))
            assert(TestEffect1.run(mapped).eval == "Effect1:50".length)

            val mapped2 = nested.map(v => lifted(v.map(_.length))).map(c => TestEffect1.run(c))
            assert(mapped2.eval == "Effect1:50".length)

            val mapped3 = nested.map(v => lifted(v.map(_.length))).map(v => lifted(TestEffect1.run(v)))
            assert(mapped3.eval.eval == "Effect1:50".length)
        }

        "unit on nested" in {
            val comp = lifted(TestEffect1(60)).map(_.unit).handle(TestEffect1.run)
            assert(comp.eval == ())

            val comp2 = lifted(TestEffect1(60)).map(v => v.unit.handle(TestEffect1.run))
            assert(comp2.eval == ())

            val comp4 = lifted(TestEffect1(60)).map(v => lifted(v.unit)).map(v => lifted(v.handle(TestEffect1.run)))
            assert(comp4.eval.eval == ())
        }

        "andThen on nested" in {
            val comp = lifted(TestEffect1(60)).andThen(TestEffect1(70)).handle(TestEffect1.run)
            assert(comp.eval == "Effect1:70")

            val comp2 =
                lifted(TestEffect1(60)).andThen(lifted(TestEffect2("Effect")))
                    .handle(TestEffect1.run).map(_.handle(TestEffect2.run))
            assert(comp2.eval == 16)

            val comp3 =
                lifted(TestEffect1(60)).andThen(lifted(TestEffect2("Effect")))
                    .handle(TestEffect1.run).flatten.handle(TestEffect2.run)
            assert(comp3.eval == 16)
        }

        "handle on nested" in {
            val nested: String < TestEffect1 < Any = lifted(TestEffect1(80))

            val result = nested.handle(comp => TestEffect1.run(comp.flatten).map(_.length))
            assert(result.eval == 10)

            val result2 = nested.handle(
                comp => TestEffect1.run(comp.flatten),
                res => res.eval.length
            )
            assert(result2 == "Effect1:80".length)
        }

        "method returning nested computation" in {
            def compute(x: Int): String < TestEffect1 < TestEffect2 =
                TestEffect2(x.toString).map(n => lifted(TestEffect1(n)))

            val result = TestEffect2.run(compute(200).map(c => TestEffect1.run(c)))
            assert(result.eval == "Effect1:13")
        }

        "nested effect suspensions" in {
            val nested: Int < TestEffect2 < TestEffect1 =
                TestEffect1(1).map(_ => lifted(TestEffect2("hello")))

            val result = TestEffect1.run(nested.map(TestEffect2.run))
            assert(result.eval == 15)

        }

        "evalNow accepts nested computations" in {
            sealed trait Bump extends ArrowEffect[Const[Int], Const[Int]]
            def bump(i: Int): Int < Bump = ArrowEffect.suspend[Unit](Tag[Bump], i)
            def run[A, S](v: => A < (Bump & S)) =
                ArrowEffect.handleCont(Tag[Bump], v)([C] => (input, cont) => cont(input + 1), a => a)

            lifted(bump(1)).evalNow match
                case Maybe.Absent => fail()
                case Maybe.Present(v) =>
                    assert(run(v).evalNow == Maybe.Absent)
                    assert(run(v).eval == 2)
            end match
        }
    }

    "depth leaked by throwing maps resets at the eval loop" in {
        def loop(n: Int): Int < Any =
            if n == 0 then 0 else (n: Int < Any).map(_ => loop(n - 1))
        def leaky(): Unit =
            try
                val _ = (1: Int < Any).map(_ => (throw new IllegalStateException("leak")): Int)
                ()
            catch
                case _: IllegalStateException => ()
        val v =
            loop(Period * 2).map { z =>
                var i = 0
                while i < Period * 2 do
                    leaky()
                    i += 1
                z
            }.map(_ + 1)
        assert(v.eval == 1)
    }

end PendingTest
