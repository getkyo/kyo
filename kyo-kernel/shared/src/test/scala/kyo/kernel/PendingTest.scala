package kyo.kernel

import kyo.*
import kyo.kernel.*
import kyo.kernel.internal.Safepoint
import scala.annotation.nowarn
import scala.annotation.tailrec

// Diverges from main: kyo-kernel does not depend on kyo-test here, so the suite extends this
// module's ScalaTest base (kyo.Test) instead of kyo.test.Test.
class PendingTest extends Test:

    private val Period = Safepoint.period()

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

    def after[A](v: A): A < Ask = ask.map(_ => v)

    "map" in {
        val x: Int < Any    = 5
        val y: String < Any = x.map(_.toString)
        assert(y.eval == "5")
    }

    "flatMap" in {
        val x: Int < Any    = 5
        val y: String < Any = x.flatMap(i => (i * 2).toString)
        assert(y.eval == "10")
    }

    "handle chaining with for-comprehension" in {
        val result =
            for
                x <- 5: Int < Any
                y <- 3: Int < Any
            yield x + y

        assert(result.eval == 8)
    }

    "flatten" in {
        val x: Int < Any < Any = Kyo.lift(10: Int < Any)
        val y: Int < Any       = x.flatten
        assert(y.eval == 10)
    }

    "unit" in {
        val x: Int < Any  = 5
        val y: Unit < Any = x.unit
        assert(y.eval == (()))
    }

    "andThen" in {
        val x: Unit < Any   = ()
        val y: String < Any = x.andThen("result")
        assert(y.eval == "result")
    }

    "eval" in {
        val x: Int < Any = 10
        assert(x.eval == 10)
    }

    "eval should not compile for pending effects" in {
        @nowarn("msg=unused") trait CustomEffect extends ArrowEffect[Const[Unit], Const[Unit]]
        typeCheckFailure("val x: Int < CustomEffect = 5; x.eval")("value eval is not a member of Int < CustomEffect")
    }

    "lift" - {

        "allows lifting pure values" in {
            val x: Int < Any = 5
            assert(x.eval == 5)
        }

        "nested computation" - {
            "generic method effect mismatch" in {
                @nowarn("msg=unused") def test1[A](v: A < Any) = v
                typeCheckFailure("test1(1: Int < TestEffect)")("Required: Any < Any")
            }
            "inference widening" in {
                typeCheckFailure("val _: Int < Any < Any = (1: Int < Any)")("Required: Int < Any < Any")
            }
        }

        "functions" - {
            "one param" in {
                val f: Int => String =
                    _.toString
                val lifted: Int => String < Any = f
                assert(lifted(42).eval == "42")
            }

            "two params" in {
                val f: (Int, Int) => String =
                    (a, b) => (a + b).toString
                val lifted: (Int, Int) => String < Any = f
                assert(lifted(20, 22).eval == "42")
            }

            "three params" in {
                val f: (Int, Int, Int) => String =
                    (a, b, c) => (a + b + c).toString
                val lifted: (Int, Int, Int) => String < Any = f
                assert(lifted(10, 20, 12).eval == "42")
            }

            "four params" in {
                val f: (Int, Int, Int, Int) => String =
                    (a, b, c, d) => (a + b + c + d).toString
                val lifted: (Int, Int, Int, Int) => String < Any = f
                assert(lifted(10, 20, 10, 2).eval == "42")
            }

            "doesn't lift nested computations" in {
                val f1: Int => String < Any                  = (_) => "test"
                val f2: (Int, Int) => String < Any           = (_, _) => "test"
                val f3: (Int, Int, Int) => String < Any      = (_, _, _) => "test"
                val f4: (Int, Int, Int, Int) => String < Any = (_, _, _, _) => "test"
                discard(f1, f2, f3, f4)
                typeCheckFailure("""
                    val _: Int => String < Any < Any = f1
                """)(
                    "Required: Int => String < Any < Any"
                )
                typeCheckFailure("""
                    val _: (Int, Int) => String < Any < Any = f2
                """)(
                    "Required: (Int, Int) => String < Any < Any"
                )
                typeCheckFailure("""
                    val _: (Int, Int, Int) => String < Any < Any = f3
                """)(
                    "Required: (Int, Int, Int) => String < Any < Any"
                )
                typeCheckFailure("""
                    val _: (Int, Int, Int, Int) => String < Any < Any = f4
                """)(
                    "Required: (Int, Int, Int, Int) => String < Any < Any"
                )
            }

            "five params" in {
                val f: (Int, Int, Int, Int, Int) => String            = (a, b, c, d, e) => (a + b + c + d + e).toString
                val lifted: (Int, Int, Int, Int, Int) => String < Any = f
                assert(lifted(10, 20, 10, 1, 1).eval == "42")
            }

            "six params" in {
                val f: (Int, Int, Int, Int, Int, Int) => String            = (a, b, c, d, e, g) => (a + b + c + d + e + g).toString
                val lifted: (Int, Int, Int, Int, Int, Int) => String < Any = f
                assert(lifted(10, 20, 10, 1, 0, 1).eval == "42")
            }
        }
    }

    sealed trait TestEffect extends ArrowEffect[Const[Int], Const[Int]]
    object TestEffect:
        def apply(i: Int): Int < TestEffect = ArrowEffect.suspend[Unit](Tag[TestEffect], i)
        def run[A, S](v: => A < (TestEffect & S)) =
            ArrowEffect.handleCont(Tag[TestEffect], v)(
                [C] => (input, cont) => cont(input + 1)
            )
    end TestEffect

    "evalNow" - {
        "returns Present for pure values" in {
            val x: Int < Any = 5
            assert(x.evalNow == Maybe(5))
        }

        "returns Absent for suspended computations" in {
            val x: Int < TestEffect = TestEffect(5)
            assert(x.evalNow == Maybe.empty)
        }

        // Diverges from main: Safepoint carries no interceptors here, so there is nothing to make
        // TestEffect.run's handling run inline. The handled computation is settled by running it,
        // and evalNow reports Absent until then.
        "accepts nested computations" in {
            Kyo.lift(TestEffect(1)).evalNow match
                case Absent => fail()
                case Present(v) =>
                    assert(TestEffect.run(v).evalNow == Absent)
                    assert(TestEffect.run(v).eval == 2)
            end match
        }

        "evalNow builds its receiver once" in {
            var runs = 0
            val r = (1: Int < Any).map { a =>
                runs += 1
                a + 1
            }.evalNow
            assert(r == Maybe(2))
            assert(runs == 1)
        }

        "evalNow returns a payload unwrapped" in {
            val inner: Int < Ask   = ask.map(_ + 1)
            val payload: Int < Ask = Kyo.lift(inner).evalNow.getOrElse(0)
            assert(answerAsk(41)(payload).eval == 42)
        }
    }

    "handle" - {
        "applies a function to a pure value" in {
            val result = (5: Int < Any).handle(_.map(_ + 1))
            assert(result.eval == 6)
        }

        "applies a function to an effectful value" in {
            val effect: Int < TestEffect = TestEffect(1)
            val result                   = effect.handle(v => TestEffect.run(v))
            assert(result.eval == 2)
        }

        "allows chaining of operations" in {
            val effect: Int < TestEffect = TestEffect(1)
            val result = effect
                .handle(v => v.map(_ * 2))
                .handle(v => TestEffect.run(v))
            assert(result.eval == 4)
        }

        "works with functions that return effects" in {
            val effect: Int < TestEffect = TestEffect(1)
            val result = effect.handle { v =>
                TestEffect.run(v).map { x =>
                    TestEffect.run(TestEffect(1))
                }
            }
            assert(result.eval == 2)
        }

        "works with identity function" in {
            val effect: Int < TestEffect = TestEffect(1)
            val result                   = effect.handle(identity)
            assert(TestEffect.run(result).eval == 2)
        }

        "can produce a value instead of a computation" in {
            val result: Int = TestEffect(1).handle(TestEffect.run).handle(_.eval)
            assert(result == 2)
        }

        "works with two functions" in {
            val result = (5: Int < Any).handle(
                _.map(_ + 1),
                _.map(_ * 2)
            )
            assert(result.eval == 12)
        }

        "works with three functions" in {
            val result = (5: Int < Any).handle(
                _.map(_ + 1),
                _.map(_ * 2),
                _.map(_.toString)
            )
            assert(result.eval == "12")
        }

        "works with four functions" in {
            val result = (5: Int < Any).handle(
                _.map(_ + 1),
                _.map(_ * 2),
                _.map(_.toString),
                _.map(_.length)
            )
            assert(result.eval == 2)
        }

        "works with five functions" in {
            val result = (5: Int < Any).handle(
                _.map(_ + 1),
                _.map(_ * 2),
                _.map(_.toString),
                _.map(_.length),
                _.map(_ > 1)
            )
            assert(result.eval == true)
        }

        "works with six functions" in {
            val result = (5: Int < Any).handle(
                _.map(_ + 1),
                _.map(_ * 2),
                _.map(_.toString),
                _.map(_.length),
                _.map(_ > 1),
                _.map(if _ then "Yes" else "No")
            )
            assert(result.eval == "Yes")
        }

        "works with seven functions" in {
            val result = (5: Int < Any).handle(
                _.map(_ + 1),
                _.map(_ * 2),
                _.map(_.toString),
                _.map(_.length),
                _.map(_ > 1),
                _.map(if _ then "Yes" else "No"),
                _.map(_.toLowerCase)
            )
            assert(result.eval == "yes")
        }

        "works with eight functions" in {
            val result = (5: Int < Any).handle(
                _.map(_ + 1),
                _.map(_ * 2),
                _.map(_.toString),
                _.map(_.length),
                _.map(_ > 1),
                _.map(if _ then "Yes" else "No"),
                _.map(_.toLowerCase),
                _.map(_.length)
            )
            assert(result.eval == 3)
        }

        "works with nine functions" in {
            val result = (5: Int < Any).handle(
                _.map(_ + 1),
                _.map(_ * 2),
                _.map(_.toString),
                _.map(_.length),
                _.map(_ > 1),
                _.map(if _ then "Yes" else "No"),
                _.map(_.toLowerCase),
                _.map(_.length),
                _.map(_ * 2)
            )
            assert(result.eval == 6)
        }

        "works with ten functions" in {
            val result = (5: Int < Any).handle(
                _.map(_ + 1),
                _.map(_ * 2),
                _.map(_.toString),
                _.map(_.length),
                _.map(_ > 1),
                _.map(if _ then "Yes" else "No"),
                _.map(_.toLowerCase),
                _.map(_.length),
                _.map(_ * 2),
                _.map(_ > 5)
            )
            assert(result.eval == true)
        }

        // Diverges from main: every stage of handle is by name here; main's strict f1 on the longer overloads
        // lets the exception escape.
        "a by-name stage sees an exception thrown while the receiver is built" in {
            def catching[A](v: => A < Any): Maybe[A] =
                try Maybe(v.eval)
                catch case _: IllegalStateException => Maybe.empty
            def boom: Int < Any = throw new IllegalStateException("boom")
            assert(boom.handle(catching) == Maybe.empty)
            assert(boom.handle(catching, _.map(_ + 1)) == Maybe.empty)
            assert(boom.handle(catching, _.map(_ + 1), _.map(_ * 2)) == Maybe.empty)
        }

        "handle applies transformations fluently" in {
            assert(ask.map(_ + 1).handle(v => answerAsk(41)(v)).handle(v => v.eval) == 42)
            assert(ask.handle(v => answerAsk(1)(v), v => v.eval) == 1)
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
                v => v.eval
            )
            assert(ten == 9)
        }
    }

    "nested computations" - {
        sealed trait TestEffect1 extends ArrowEffect[Const[Int], Const[String]]
        object TestEffect1:
            def apply(i: Int): String < TestEffect1 =
                ArrowEffect.suspend[Any](Tag[TestEffect1], i)

            def run[A, S](v: A < (TestEffect1 & S)): A < S =
                ArrowEffect.handleCont(Tag[TestEffect1], v)([C] =>
                    (input, cont) =>
                        cont(s"Effect1:$input"))
        end TestEffect1

        sealed trait TestEffect2 extends ArrowEffect[Const[String], Const[Int]]
        object TestEffect2:
            def apply(s: String): Int < TestEffect2 =
                ArrowEffect.suspend[Any](Tag[TestEffect2], s)

            def run[A, S](v: A < (TestEffect2 & S)): A < S =
                ArrowEffect.handleCont(Tag[TestEffect2], v)([C] =>
                    (input, cont) =>
                        cont(input.length + 10))
        end TestEffect2

        sealed trait TestEffect3 extends ContextEffect[Boolean]
        object TestEffect3:
            def apply(): Boolean < TestEffect3 =
                ContextEffect.suspend(Tag[TestEffect3])

            def run[A, S](value: Boolean)(v: A < (TestEffect3 & S)): A < S =
                ContextEffect.handleInheritable(Tag[TestEffect3], value)(v)
        end TestEffect3

        // Diverges from main: Safepoint has no interceptors here, so the deferral is forced by
        // draining the safepoint budget. Every entry inside the block is denied, which is what a
        // preempting fiber does to the resumption over a nested `A < S` value.
        def drainedBudget[A](f: => A): A =
            val slot  = Safepoint.get()
            val saved = Safepoint.save(slot)
            while Safepoint.enter(slot) do ()
            try f
            finally Safepoint.restore(slot, saved)
        end drainedBudget

        def nestedScenario: Int < TestEffect2 < TestEffect1 =
            Kyo.lift(TestEffect1(10)).map(_.map(s => Kyo.lift(TestEffect2(s))))

        "basic nesting operations" in {
            val nested: String < TestEffect1 < Any = Kyo.lift(TestEffect1(42))
            assert(TestEffect1.run(nested.flatten).eval == "Effect1:42")

            val result = Kyo.lift(TestEffect1(5)).map(_.map(s => TestEffect1(s.length)))
            assert(TestEffect1.run(result).eval == "Effect1:9")

            assert(TestEffect1.run(nested.map(c => c)).eval == "Effect1:42")
        }

        "a nested computation with a Nothing result" in {
            // `Nothing < S` is `Pending[Nothing, S]` alone, so a position holding a nested one must
            // still admit the lift's wrapper
            val inner: Nothing < TestEffect1          = TestEffect1(7).map(_ => (throw new IllegalStateException("unreached")): Nothing)
            val nested: (Nothing < TestEffect1) < Any = Kyo.lift(inner)
            val stopped: String < Any =
                ArrowEffect.handleLoop(Tag[TestEffect1], nested.flatten)([C] => input => Loop.done(s"stopped at $input"))
            assert(stopped.eval == "stopped at 7")
        }

        "multiple effects" in {
            val comp: Int < TestEffect2 < TestEffect1 =
                Kyo.lift(TestEffect1(10)).map(_.map(s => Kyo.lift(TestEffect2(s))))

            val result = comp.map(_.handle(TestEffect2.run)).handle(TestEffect1.run)
            assert(result.eval == "Effect1:10".length + 10)

            val result2 = comp.flatten.handle(TestEffect2.run).handle(TestEffect1.run)
            assert(result2.eval == "Effect1:10".length + 10)
        }

        // When the safepoint denies entry (as it does under fiber preemption), map/flatMap/andThen defer the
        // resumption over a nested `A < S` value; each must still apply its continuation so the inner effect stays
        // handled and does not leak past its handler. `drainedBudget` forces that deferral deterministically.
        // One leaf per operation.

        "multiple effects with the nested map deferred at a denied safepoint" in {
            assert(nestedScenario.map(_.handle(TestEffect2.run)).handle(TestEffect1.run).eval == "Effect1:10".length + 10)
            val deferred =
                drainedBudget {
                    nestedScenario.map(_.handle(TestEffect2.run)).handle(TestEffect1.run)
                }
            assert(deferred.eval == "Effect1:10".length + 10)
        }

        "multiple effects with the nested flatMap deferred at a denied safepoint" in {
            assert(nestedScenario.flatMap(_.handle(TestEffect2.run)).handle(TestEffect1.run).eval == "Effect1:10".length + 10)
            val deferred =
                drainedBudget {
                    nestedScenario.flatMap(_.handle(TestEffect2.run)).handle(TestEffect1.run)
                }
            assert(deferred.eval == "Effect1:10".length + 10)
        }

        "discarding a nested computation with andThen deferred at a denied safepoint" in {
            // andThen discards the inner `Int < TestEffect2`; the deferred resumption must drop the whole nested
            // value rather than re-evaluate it at the wrong nesting.
            assert(nestedScenario.andThen(99).handle(TestEffect1.run).eval == 99)
            val deferred =
                drainedBudget {
                    nestedScenario.andThen(99).handle(TestEffect1.run)
                }
            assert(deferred.eval == 99)
        }

        "map" in {
            val nested: String < TestEffect1 < Any = Kyo.lift(TestEffect1(50))

            val mapped: Int < TestEffect1 = nested.map(_.map(_.length))
            assert(TestEffect1.run(mapped).eval == "Effect1:50".length)

            val mapped2 = nested.map(v => Kyo.lift(v.map(_.length))).map(_.handle(TestEffect1.run))
            assert(mapped2.eval == "Effect1:50".length)

            val mapped3 = nested.map(v => Kyo.lift(v.map(_.length))).map(v => Kyo.lift(v.handle(TestEffect1.run)))
            assert(mapped3.eval.eval == "Effect1:50".length)
        }

        "unit" in {
            val comp = Kyo.lift(TestEffect1(60)).map(_.unit).handle(TestEffect1.run)
            assert(comp.eval == ())

            val comp2 = Kyo.lift(TestEffect1(60)).map(v => v.unit.handle(TestEffect1.run))
            assert(comp2.eval == ())

            val comp3 = Kyo.lift(TestEffect1(60)).map(v => Kyo.lift(v.unit)).map(_.handle(TestEffect1.run))
            assert(comp2.eval == ())

            val comp4 = Kyo.lift(TestEffect1(60)).map(v => Kyo.lift(v.unit)).map(v => Kyo.lift(v.handle(TestEffect1.run)))
            assert(comp4.eval.eval == ())
        }

        "andThen" in {
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

        "handle" in {
            val nested: String < TestEffect1 < Any = Kyo.lift(TestEffect1(80))

            val result = nested.handle(comp =>
                TestEffect1.run(comp.flatten).map(_.length)
            )

            assert(result.eval == 10)

            val result2 = nested.handle(
                comp => TestEffect1.run(comp.flatten),
                res => res.eval.length
            )

            assert(result2 == "Effect1:80".length)
        }

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

        "method returning nested computation" in {
            def compute(x: Int): String < TestEffect1 < TestEffect2 =
                TestEffect2(x.toString).map(n => Kyo.lift(TestEffect1(n)))

            val result      = compute(200).flatten
            val finalResult = TestEffect2.run(TestEffect1.run(result))

            assert(finalResult.eval == "Effect1:13")

            val mapped = TestEffect2.run(compute(200).map(c => TestEffect1.run(c)))
            assert(mapped.eval == "Effect1:13")
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

        "map over a nested value denied by the budget defers the wrapped value" in {
            val nested: Int < TestEffect2 < Any = Kyo.lift(TestEffect2("hello"))
            val deferred                        = drainedBudget(nested.map(c => TestEffect2.run(c)))
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

        "a doubly nested value denied by the budget strips exactly one level under map" in {
            val inner: Int < TestEffect2              = TestEffect2("hello")
            val nested: Int < TestEffect2 < Any < Any = Kyo.lift(Kyo.lift(inner): Int < TestEffect2 < Any)
            val deferred: Int < TestEffect2 < Any     = drainedBudget(nested.map(c => c))
            val data                                  = deferred.eval
            assert(TestEffect2.run(data).eval == "hello".length + 10)
        }
    }

    "show" - {
        "should display pure vals wrapped with inner types displayed using show" in {
            val i: Result[String, Int] < Any         = Result.succeed(23)
            val r: Render[Result[String, Int] < Any] = Render.apply
            assert(r.asString(i) == "Kyo(Success(23))")
            assert(render"$i" == "Kyo(Success(23))")
        }
    }

    "a settled payload evaluates to itself" in {
        val outer: (Int < Any) < Any = Kyo.lift(42: Int < Any)
        assert(outer.eval.eval == 42)
    }

    "eval returns a pending payload without running it" in {
        val inner: Int < Ask   = ask.map(_ + 1)
        val payload: Int < Ask = Kyo.lift(inner).eval
        assert(answerAsk(41)(payload).eval == 42)
    }

    "map receives a pending payload as a value" in {
        val inner: Int < Ask    = ask
        var received: Int < Ask = 0
        val r: Int < Any = Kyo.lift(inner).map { c =>
            received = c
            7
        }
        assert(r.eval == 7)
        assert(answerAsk(41)(received.map(_ + 1)).eval == 42)
    }

    "a map can return a computation as its value" in {
        val inner: Int < Ask     = ask.map(_ + 1)
        val r: (Int < Ask) < Ask = after(inner)
        val payload: Int < Ask   = answerAsk(1)(r).eval
        assert(answerAsk(41)(payload).eval == 42)
    }

    "a payload returned past the budget stays a value" in {
        def loop(i: Int): (Int < Ask) < Any =
            if i == 0 then Kyo.lift(ask)
            else (0: Int < Any).map(_ => loop(i - 1))
        val payload: Int < Ask = loop(10000).eval
        assert(answerAsk(42)(payload).eval == 42)
    }

    "a handler applies done to a settled payload without driving it" in {
        val outer: (Unit < Say) < Ask = Kyo.lift(say("x"): Unit < Say)
        val handled: (Unit < Say) < Any =
            ArrowEffect.handleLoop(Tag[Ask], outer)([C] => _ => Loop.continue(1), a => Kyo.lift(a))
        val payload: Unit < Say = handled.eval
        var seen                = ""
        val r: Unit < Any = ArrowEffect.handleLoop(Tag[Say], payload)(
            [C] =>
                s =>
                    seen = s
                    Loop.continue(())
            ,
            a => a
        )
        assert(r.eval == ())
        assert(seen == "x")
    }

    "a region returns a foreign payload untouched" in {
        val body: (Unit < Say) < Ask = after(say("y"): Unit < Say)
        val handled: (Unit < Say) < Any =
            ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => Loop.continue(1), a => Kyo.lift(a))
        val payload: Unit < Say = handled.eval
        var seen                = ""
        val r: Unit < Any = ArrowEffect.handleLoop(Tag[Say], payload)(
            [C] =>
                s =>
                    seen = s
                    Loop.continue(())
            ,
            a => a
        )
        assert(r.eval == ())
        assert(seen == "y")
    }

    "an answer can be a computation value" in {
        val inner: Int < Ask = ask
        val body: Int < Give = give.map(_ => 5)
        val r: Int < Any     = ArrowEffect.handleLoop(Tag[Give], body)([C] => _ => Loop.continue(Kyo.lift(inner)), a => a)
        assert(r.eval == 5)
    }

    "a captured continuation accepts a computation answer" in {
        val inner: Int < Ask = ask
        val body: Int < Give = give.map(_ => 9)
        val r: Int < Any     = ArrowEffect.handleCont(Tag[Give], body)([C] => (_, cont) => cont(inner), a => a)
        assert(r.eval == 9)
    }

    "mapping over a payload derives a new payload" in {
        val inner: Int < Ask           = ask
        val derived: (Int < Ask) < Any = Kyo.lift(inner).map(c => Kyo.lift(c.map(_ * 2)))
        val payload: Int < Ask         = derived.eval
        assert(answerAsk(21)(payload).eval == 42)
    }

    "a payload handles inside map" in {
        def deliver[B](f: Int => B): B < Ask = ask.map(a => f(a))
        val comp: (Int < Say) < Ask          = deliver(a => say("s").map(_ => a + 1))
        val handled: (Int < Any) < Any       = answerAsk(10)(comp.map(c => Kyo.lift(answerSay[Int](c))))
        assert(handled.eval.eval == 11)
    }

    "a generic function nests its result across effects" in {
        def f(a: Int): Int < Say       = say("x").map(_ => a + 5)
        def g[B](f: Int => B): B < Ask = ask.map(a => f(a))
        val nested: (Int < Say) < Ask  = g(f)
        val payload: Int < Say         = answerAsk(1)(nested).eval
        assert(answerSay(payload).eval == 6)
    }

    "a pure function passes to map point-free" in {
        val f: Int => Int = _ + 1
        val r: Int < Ask  = ask.map(f)
        assert(answerAsk(41)(r).eval == 42)
    }

    "a generic function passes to map point-free" in {
        def f(a: Int): Int < Say       = say("x").map(_ => a + 5)
        def g[B](f: Int => B): B < Ask = ask.map(f)
        val nested: (Int < Say) < Ask  = g(f)
        assert(answerSay(answerAsk(1)(nested).eval).eval == 6)
    }

    "a loop can end its region with a computation result" in {
        val inner: Int < Ask = ask.map(_ + 1)
        val body: Int < Give = give.map(_ => 0)
        val r: (Int < Ask) < Any = ArrowEffect.handleLoop(Tag[Give], body)(
            [C] => _ => Loop.done(Kyo.lift(inner)),
            a => Kyo.lift(inner)
        )
        val payload: Int < Ask = r.eval
        assert(answerAsk(41)(payload).eval == 42)
    }

    "a fused continuation receives the answer payload" in {
        val inner: Int < Ask = ask
        var got: Int < Ask   = 0
        val body: Int < Give = ArrowEffect.suspendWith[Any](Tag[Give], ()) { c =>
            got = c
            3
        }
        val r: Int < Any = ArrowEffect.handleLoop(Tag[Give], body)([C] => _ => Loop.continue(Kyo.lift(inner)), a => a)
        assert(r.eval == 3)
        assert(answerAsk(41)(got.map(_ + 1)).eval == 42)
    }

    "double nesting round trips" in {
        val inner: Int < Ask                 = ask.map(_ + 1)
        val twice: ((Int < Ask) < Any) < Any = Kyo.lift(Kyo.lift(inner))
        val payload: Int < Ask               = twice.eval.eval
        assert(answerAsk(41)(payload).eval == 42)
    }

    "a loop answer payload delivers unwrapped through a bare suspension" in {
        val inner: Int < Ask = ask.map(_ + 1)
        val r: (Int < Ask) < Any =
            ArrowEffect.handleLoop(Tag[Give], give)([C] => _ => Loop.continue(Kyo.lift(inner)), a => Kyo.lift(a))
        val payload: Int < Ask = r.eval
        assert(answerAsk(41)(payload).eval == 42)
    }

    "a suspended loop answer delivering a payload resumes unwrapped" in {
        val inner: Int < Ask = ask.map(_ + 1)
        val handled: (Int < Ask) < Ask =
            ArrowEffect.handleLoop(Tag[Give], give)([C] => _ => Loop.continue(after(inner)), a => Kyo.lift(a))
        val payload: Int < Ask = answerAsk(0)(handled).eval
        assert(answerAsk(41)(payload).eval == 42)
    }

    "a stateful loop answer payload delivers unwrapped through a bare suspension" in {
        val inner: Int < Ask = ask.map(_ + 1)
        val r: (Int < Ask) < Any =
            ArrowEffect.handleLoopState(Tag[Give], 0, give)(
                [C] => (s, _) => Loop.continue(s + 1, Kyo.lift(inner)),
                (_, a) => Kyo.lift(a)
            )
        val payload: Int < Ask = r.eval
        assert(answerAsk(41)(payload).eval == 42)
    }

    "a loop can end its region effectfully with a computation result" in {
        val inner: Int < Ask = ask.map(_ + 1)
        val r: (Int < Ask) < Ask = ArrowEffect.handleLoop(Tag[Give], give)(
            [C] => _ => after(0).map(_ => Loop.done(Kyo.lift(inner))),
            a => Kyo.lift(a)
        )
        val payload: Int < Ask = answerAsk(0)(r).eval
        assert(answerAsk(41)(payload).eval == 42)
    }

    "a fused handler continuation receives a payload as a value" in {
        val inner: Int < Ask = ask.map(_ + 1)
        var got: Int < Ask   = 0
        val r: Int < Any = ArrowEffect.handleLoopWith[Const[Unit], Const[Int < Ask], Give, Int < Ask, Int < Ask, Any, Any](Tag[Give], give)(
            [C] => _ => Loop.continue(Kyo.lift(inner)),
            a => Kyo.lift(a)
        ) { b =>
            got = b
            9
        }
        assert(r.eval == 9)
        assert(answerAsk(41)(got).eval == 42)
    }

    "flatMap chains a settled value into an effectful computation" in {
        val r: Int < Ask = (5: Int < Ask).flatMap(a => ask.map(_ + a))
        assert(answerAsk(1)(r).eval == 6)
    }

    "flatMap receives a pending payload as a value" in {
        val inner: Int < Ask    = ask
        var received: Int < Ask = 0
        val r: Int < Any = Kyo.lift(inner).flatMap { c =>
            received = c
            7
        }
        assert(r.eval == 7)
        assert(answerAsk(41)(received.map(_ + 1)).eval == 42)
    }

    "andThen sequences effects and discards the value" in {
        var ran = false
        val r: Int < Ask = ask.andThen {
            ran = true
            ask.map(_ + 1)
        }
        assert(answerAsk(41)(r).eval == 42)
        assert(ran)
    }

    "andThen leaves a discarded payload untouched" in {
        val inner: Int < Ask = ask
        val r: Int < Any     = Kyo.lift(inner).andThen(7)
        assert(r.eval == 7)
    }

    "unit discards the result" in {
        assert((42: Int < Any).unit.eval == ())
        assert(answerAsk(1)(ask.unit).eval == ())
    }

    "eval returns the settled result" in {
        assert((42: Int < Any).eval == 42)
        assert((1: Int < Any).map(_ + 1).eval == 2)
    }

    "flatten runs a nested payload" in {
        val inner: Int < Ask = ask.map(_ + 1)
        assert(answerAsk(41)(Kyo.lift(inner).flatten).eval == 42)
    }

    "flatten merges the effects of both layers" in {
        val inner: Int < Ask          = ask.map(_ + 1)
        val nested: (Int < Ask) < Ask = after(inner)
        assert(answerAsk(20)(nested.flatten).eval == 21)
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
        val inner: Int < Any         = (1: Int < Any).map(_ + 1)
        val outer: (Int < Any) < Any = Kyo.lift(inner)
        assert(outer.eval.eval == 2)
    }

    "a computation as a value survives mapping" in {
        val inner: Int < Any = (1: Int < Any).map(_ + 1)
        val v: Int < Any     = Kyo.lift(inner).map(c => c.map(_ * 10))
        assert(v.eval == 20)
    }

    "the extension surface applies to a val of nested type" in {
        val nested: (Int < Any) < Any = Kyo.lift(Kyo.lift(41))
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
