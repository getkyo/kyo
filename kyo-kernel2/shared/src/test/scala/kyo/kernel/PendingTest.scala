package kyo.kernel

import kyo.Const
import kyo.Kyo
import kyo.Maybe
import kyo.Tag
import kyo.kernel.internal.*
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec
import scala.compiletime.testing.typeCheckErrors

class PendingTest extends AnyFreeSpec:

    private val Period = 512

    "a settled value lifts and evaluates" in {
        val v: Int < Any = 42
        assert(v.eval == 42)
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

    "flatMap and andThen" in {
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
        assert(nested.flatten.eval == 41)
        assert(nested.unit.eval == ())
        assert(nested.evalNow.isDefined)
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
                ArrowEffect.handle(Tag[TestEffect1], v)([C] =>
                    (input, cont) =>
                        cont(s"Effect1:$input"))
        end TestEffect1

        sealed trait TestEffect2 extends ArrowEffect[Const[String], Const[Int]]
        object TestEffect2:
            def apply(s: String): Int < TestEffect2 =
                ArrowEffect.suspend[Any](Tag[TestEffect2], s)

            def run[A, S](v: A < (TestEffect2 & S)): A < S =
                ArrowEffect.handle(Tag[TestEffect2], v)([C] =>
                    (input, cont) =>
                        cont(input.length + 10))
        end TestEffect2

        // Parked with the removal of ContextEffect from kyo-kernel2. Restore against
        // the replacement design, together with "multiple operations" below.
        /*
        sealed trait TestEffect3 extends ContextEffect[Boolean]
        object TestEffect3:
            def apply(): Boolean < TestEffect3 =
                ContextEffect.suspend(Tag[TestEffect3])

            def run[A, S](value: Boolean)(v: A < (TestEffect3 & S)): A < S =
                ContextEffect.handle(Tag[TestEffect3], value)(v)
        end TestEffect3
         */

        "basic nesting operations" in {
            val nested: String < TestEffect1 < Any = Kyo.lift(TestEffect1(42))
            assert(TestEffect1.run(nested.flatten).eval == "Effect1:42")

            val result = Kyo.lift(TestEffect1(5)).map(_.map(s => TestEffect1(s.length)))
            assert(TestEffect1.run(result).eval == "Effect1:9")
        }

        "multiple effects" in {
            val comp: Int < TestEffect2 < TestEffect1 =
                Kyo.lift(TestEffect1(10)).map(_.map(s => Kyo.lift(TestEffect2(s))))

            val result = comp.map(_.handle(TestEffect2.run)).handle(TestEffect1.run)
            assert(result.eval == "Effect1:10".length + 10)

            val result2 = comp.flatten.handle(TestEffect2.run).handle(TestEffect1.run)
            assert(result2.eval == "Effect1:10".length + 10)
        }

        // A denied safepoint must defer the ORIGINAL wrapped value, never the unnested
        // payload: the resume path dispatches on the representation, so a deferred
        // computation-as-data would execute as a suspension and leak the inner effect.
        // The old kernel shipped exactly this bug (its nested-resumption fix deferred
        // over the original value); these pins port that regression coverage, forcing
        // the deferral deterministically by draining the budget.

        def drainedBudget[A](f: => A): A =
            val slot  = Safepoint.get()
            val saved = Safepoint.save(slot)
            while Safepoint.enter(slot) do ()
            try f
            finally Safepoint.restore(slot, saved)
        end drainedBudget

        "map over a nested value denied by the budget defers the wrapped value" in {
            val nested: Int < TestEffect2 < Any = Kyo.lift(TestEffect2("hello"))
            val deferred                        = drainedBudget(nested.map(_.handle(TestEffect2.run)))
            assert(deferred.eval == "hello".length + 10)
        }

        "flatMap over a nested value denied by the budget defers the wrapped value" in {
            val nested: Int < TestEffect2 < Any = Kyo.lift(TestEffect2("hello"))
            val deferred                        = drainedBudget(nested.flatMap(_.handle(TestEffect2.run)))
            assert(deferred.eval == "hello".length + 10)
        }

        "flatten over a doubly nested value denied by the budget strips exactly one level" in {
            val inner: Int < TestEffect2              = TestEffect2("hello")
            val nested: Int < TestEffect2 < Any < Any = Kyo.lift(Kyo.lift(inner): Int < TestEffect2 < Any)
            val deferred: Int < TestEffect2 < Any     = drainedBudget(nested.flatten)
            val data                                  = deferred.eval
            assert(TestEffect2.run(data).eval == "hello".length + 10)
        }

        "andThen over a nested value denied by the budget discards it unevaluated" in {
            val nested: Int < TestEffect2 < Any = Kyo.lift(TestEffect2("hello"))
            val deferred                        = drainedBudget(nested.andThen(99: Int < Any))
            assert(deferred.eval == 99)
        }

        "unit over a nested value denied by the budget discards it unevaluated" in {
            val nested: Int < TestEffect2 < Any = Kyo.lift(TestEffect2("hello"))
            val deferred                        = drainedBudget(nested.unit)
            assert(deferred.eval == ())
        }

        "map on nested" in {
            val nested: String < TestEffect1 < Any = Kyo.lift(TestEffect1(50))

            val mapped: Int < TestEffect1 = nested.map(_.map(_.length))
            assert(TestEffect1.run(mapped).eval == "Effect1:50".length)

            val mapped2 = nested.map(v => Kyo.lift(v.map(_.length))).map(_.handle(TestEffect1.run))
            assert(mapped2.eval == "Effect1:50".length)

            val mapped3 = nested.map(v => Kyo.lift(v.map(_.length))).map(v => Kyo.lift(v.handle(TestEffect1.run)))
            assert(mapped3.eval.eval == "Effect1:50".length)
        }

        "unit on nested" in {
            val comp = Kyo.lift(TestEffect1(60)).map(_.unit).handle(TestEffect1.run)
            assert(comp.eval == ())

            val comp2 = Kyo.lift(TestEffect1(60)).map(v => v.unit.handle(TestEffect1.run))
            assert(comp2.eval == ())

            val comp4 = Kyo.lift(TestEffect1(60)).map(v => Kyo.lift(v.unit)).map(v => Kyo.lift(v.handle(TestEffect1.run)))
            assert(comp4.eval.eval == ())
        }

        "andThen on nested" in {
            val comp = Kyo.lift(TestEffect1(60)).andThen(TestEffect1(70)).handle(TestEffect1.run)
            assert(comp.eval == "Effect1:70")

            val comp2 =
                Kyo.lift(TestEffect1(60)).andThen(Kyo.lift(TestEffect2("Effect")))
                    .handle(TestEffect1.run).map(_.handle(TestEffect2.run))
            assert(comp2.eval == 16)

            val comp3 =
                Kyo.lift(TestEffect1(60)).andThen(Kyo.lift(TestEffect2("Effect")))
                    .handle(TestEffect1.run).flatten.handle(TestEffect2.run)
            assert(comp3.eval == 16)
        }

        "handle on nested" in {
            val nested: String < TestEffect1 < Any = Kyo.lift(TestEffect1(80))

            val result = nested.handle(comp => TestEffect1.run(comp.flatten).map(_.length))
            assert(result.eval == 10)

            val result2 = nested.handle(
                comp => TestEffect1.run(comp.flatten),
                res => res.eval.length
            )
            assert(result2 == "Effect1:80".length)
        }

        /*
        "multiple operations" in {
            def processValue(v: Int): Int < TestEffect2 < TestEffect1 =
                TestEffect1(v).map(s => Kyo.lift(TestEffect2(s + "!")))

            val input = 100
            val result = processValue(input).flatten
                .map(n => n * 2)
                .flatMap(n => TestEffect3().map(_ => n))

            val finalResult = TestEffect1.run(
                TestEffect2.run(
                    TestEffect3.run(true)(result)
                )
            )

            assert(finalResult.eval == ("Effect1:100!".length + 10) * 2)
        }
         */

        "method returning nested computation" in {
            def compute(x: Int): String < TestEffect1 < TestEffect2 =
                TestEffect2(x.toString).map(n => Kyo.lift(TestEffect1(n)))

            val result      = compute(200).flatten
            val finalResult = TestEffect2.run(TestEffect1.run(result))

            assert(finalResult.eval == "Effect1:13")
        }

        "nested effect suspensions" in {
            val nested: Int < TestEffect2 < TestEffect1 =
                TestEffect1(1).map(_ => Kyo.lift(TestEffect2("hello")))

            val result = TestEffect1.run(nested.map(TestEffect2.run))
            assert(result.eval == 15)

            val result2 = TestEffect1.run(TestEffect2.run(nested.flatten))
            assert(result2.eval == 15)
        }

        "nested effect suspension lifted function" in {
            def f(str: String): Int < TestEffect2 = TestEffect2(str)

            def g[B](f: String => B): B < TestEffect1 =
                TestEffect1(1).map(f)

            val nested: Int < TestEffect2 < TestEffect1 = g(f)

            val result = TestEffect1.run(nested.map(TestEffect2.run))
            assert(result.eval == 19)

            val result2 = TestEffect1.run(TestEffect2.run(nested.flatten))
            assert(result2.eval == 19)
        }

        "nested effect suspension widened lifted function" in {
            def f(str: String): Int < TestEffect2 = TestEffect2(str)

            def g[B](f: String => B): B < TestEffect1 =
                val liftedF: String => B < Any = f
                TestEffect1(1).map(liftedF)

            val nested: Int < TestEffect2 < TestEffect1 = g(f)

            val result = TestEffect1.run(nested.map(TestEffect2.run))
            assert(result.eval == 19)

            val result2 = TestEffect1.run(TestEffect2.run(nested.flatten))
            assert(result2.eval == 19)
        }

        "generic lifted functions" in {
            def f(str: String): Int < TestEffect2 = TestEffect2(str)

            def g[B](f: String => B): B < TestEffect1 =
                TestEffect1(1).map(f).flatMap(_ => f("a")).andThen(f("b"))

            val nested: Int < TestEffect2 < TestEffect1 = g(f)

            val result = TestEffect1.run(nested.map(TestEffect2.run))
            assert(result.eval == 11)

            val result2 = TestEffect1.run(TestEffect2.run(nested.flatten))
            assert(result2.eval == 11)
        }

        "evalNow accepts nested computations" in {
            sealed trait Ask extends ArrowEffect[Const[Int], Const[Int]]
            def ask(i: Int): Int < Ask = ArrowEffect.suspend[Unit](Tag[Ask], i)
            def run[A, S](v: => A < (Ask & S)) =
                ArrowEffect.handle(Tag[Ask], v)([C] => (input, cont) => cont(input + 1))

            Kyo.lift(ask(1)).evalNow match
                case Maybe.Absent     => fail()
                case Maybe.Present(v) =>
                    // handle parks a region node, so a freshly handled
                    // computation is Absent for evalNow until it evaluates
                    // (the old kernel answered eagerly here)
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
