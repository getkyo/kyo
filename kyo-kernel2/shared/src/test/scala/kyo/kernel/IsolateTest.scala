package kyo.kernel

import kyo.Const
import kyo.Frame
import kyo.Kyo
import kyo.Maybe
import kyo.Maybe.*
import kyo.Tag
import kyo.kernel.internal.Eval
import org.scalatest.freespec.AnyFreeSpec
import scala.compiletime.testing.typeCheckErrors

class IsolateTest extends AnyFreeSpec:

    sealed trait TestEffect1      extends ContextEffect[Int]
    sealed trait TestEffect2      extends ContextEffect[String]
    sealed trait TestEffect3      extends ContextEffect[Boolean]
    sealed trait NotContextEffect extends ArrowEffect[Const[Int], Const[Int]]

    private inline def typeCheckFailure(inline code: String)(expected: String): org.scalatest.Assertion =
        val errors = typeCheckErrors(code)
        assert(errors.nonEmpty, "expected a type error, code compiled")
        assert(errors.exists(_.message.contains(expected)), errors.map(_.message).mkString("\n"))
    end typeCheckFailure

    // a Var-like effect pair: a read (Absent) answers the current value, a write (Present) installs a
    // new one and answers it. Stateful regions over them give the isolates below real state to manage
    sealed trait CellA extends ArrowEffect[Const[Maybe[Int]], Const[Int]]
    sealed trait CellB extends ArrowEffect[Const[Maybe[Int]], Const[Int]]

    def getA: Int < CellA         = ArrowEffect.suspend[Any](Tag[CellA], Maybe.empty[Int])
    def setA(n: Int): Int < CellA = ArrowEffect.suspend[Any](Tag[CellA], Maybe(n))
    def getB: Int < CellB         = ArrowEffect.suspend[Any](Tag[CellB], Maybe.empty[Int])
    def setB(n: Int): Int < CellB = ArrowEffect.suspend[Any](Tag[CellB], Maybe(n))

    def runA[A, S](init: Int)(v: A < (CellA & S)): (Int, A) < S =
        ArrowEffect.handleLoopState(Tag[CellA], init, v)(
            [C] =>
                (s, in) =>
                    in match
                        case Present(n) => Loop.continue(n, (n: Int < Any))
                        case Absent     => Loop.continue(s, (s: Int < Any)),
            (s, a) => (s, a)
        )

    def runB[A, S](init: Int)(v: A < (CellB & S)): (Int, A) < S =
        ArrowEffect.handleLoopState(Tag[CellB], init, v)(
            [C] =>
                (s, in) =>
                    in match
                        case Present(n) => Loop.continue(n, (n: Int < Any))
                        case Absent     => Loop.continue(s, (s: Int < Any)),
            (s, a) => (s, a)
        )

    // the update strategy: capture the enclosing value, run the isolation over a copy, install the
    // isolation's final value back into the enclosing region on restore
    val updateA: Isolate[CellA, Any, CellA] =
        new Isolate[CellA, Any, CellA]:
            type State        = Int
            type Transform[A] = (Int, A)
            def capture[A, S](f: Int => A < S)(using Frame) = getA.map(f)
            def isolate[A, S](state: Int, v: A < (S & CellA))(using Frame) =
                runA(state)(v)
            def restore[A, S](v: (Int, A) < S)(using Frame) =
                v.map(t => setA(t._1).map(_ => t._2))

    val updateB: Isolate[CellB, Any, CellB] =
        new Isolate[CellB, Any, CellB]:
            type State        = Int
            type Transform[A] = (Int, A)
            def capture[A, S](f: Int => A < S)(using Frame) = getB.map(f)
            def isolate[A, S](state: Int, v: A < (S & CellB))(using Frame) =
                runB(state)(v)
            def restore[A, S](v: (Int, A) < S)(using Frame) =
                v.map(t => setB(t._1).map(_ => t._2))

    // the local strategy: same capture and isolation, but changes never reach the enclosing region
    val localA: Isolate[CellA, Any, Any] =
        new Isolate[CellA, Any, Any]:
            type State        = Int
            type Transform[A] = A
            def capture[A, S](f: Int => A < S)(using Frame) = getA.map(f)
            def isolate[A, S](state: Int, v: A < (S & CellA))(using Frame) =
                runA(state)(v).map(t => t._2)
            def restore[A, S](v: A < S)(using Frame) = v

    "derive" - {
        "creates an isolate for context effects" in {
            val isolate                                         = Isolate.derive[TestEffect1 & TestEffect2, Any, Any]
            val _: Isolate[TestEffect1 & TestEffect2, Any, Any] = isolate
            succeed
        }

        val error = "This operation requires isolation for effects"

        "fails compilation for non-context effects" in {
            typeCheckFailure("Isolate.derive[Int, Any, Any]")(error)
            typeCheckFailure("Isolate.derive[String, Any, Any]")(error)
        }

        "fails compilation for non-context effect traits" in {
            typeCheckFailure("Isolate.derive[NotContextEffect, Any, Any]")(error)
        }

        "a derived isolate for context effects passes the computation through" in {
            val i = Isolate.derive[TestEffect1, Any, Any]
            val v = i.run(ContextEffect.suspend(Tag[TestEffect1]))
            assert(Eval(ContextEffect.handle(Tag[TestEffect1], 10)(v)) == 10)
        }

        "resolves implicitly for context effects" in {
            val i = summon[Isolate[TestEffect1, Any, TestEffect1]]
            val v = i.run(ContextEffect.suspend(Tag[TestEffect1]))
            assert(Eval(ContextEffect.handle(Tag[TestEffect1], 7)(v)) == 7)
        }
    }

    "run" - {
        "threads capture, isolation, and restore" in {
            val v = updateA.run(setA(5).map(_ => getA).map(_ + 1))
            // isolation: set 5, read 5, +1; restore installs 5 into the enclosing region
            assert(Eval(runA(0)(v)) == ((5, 6)))
        }

        "isolation starts from the captured enclosing state" in {
            val v = updateA.run(getA.map(_ + 100))
            assert(Eval(runA(7)(v)) == ((7, 107)))
        }

        "a local isolate keeps the enclosing state untouched" in {
            val v = localA.run(setA(100).map(_ => getA))
            assert(Eval(runA(7)(v)) == ((7, 100)))
        }
    }

    "andThen" - {
        "composes captures, isolations, and restores of both isolates" in {
            val both = updateA.andThen(updateB)
            val body: Int < (CellA & CellB) =
                setA(1).map(_ => setB(2)).map(_ => getA.map(a => getB.map(b => a * 10 + b)))
            val v = both.run(body)
            assert(Eval(runA(0)(runB(0)(v))) == ((1, (2, 12))))
        }

        "composing with Contextual leaves the other side's management untouched" in {
            // it is not dropped, since it manages the scope the effects are read in, but it manages no
            // effect of its own: what the other side does is what the composition does
            val before = Eval(runA(0)(updateA.run(setA(5).map(_ => getA))))
            assert(Eval(runA(0)(updateA.andThen(Isolate.internal.Contextual).run(setA(5).map(_ => getA)))) == before)
            assert(Eval(runA(0)(Isolate.internal.Contextual.andThen(updateA).run(setA(5).map(_ => getA)))) == before)
        }
    }

    "use" - {
        "provides the isolate as a given" in {
            def op(using i: Isolate[CellA, Any, CellA]): Int < CellA = i.run(setA(3).map(_ => getA))
            val r                                                    = updateA.use(op)
            assert(Eval(runA(0)(r)) == ((3, 3)))
        }
    }

    "Contextual" - {
        "passes the computation through untouched" in {
            val v: Int < Any = 42
            assert(Isolate.internal.Contextual.run(v).eval == 42)
        }

        // nesting bindings of one tag is the normal case: every Scope.run and every Local.let shares its
        // tag with the ones around it, and the inner entry shadows for its extent. A crossing must leave
        // that shadowing intact
        "nested bindings of one tag" - {
            "the inner binding still answers its own value after a crossing" in {
                val v =
                    ContextEffect.handle(Tag[TestEffect2], "outer") {
                        ContextEffect.handle(Tag[TestEffect2], "inner") {
                            Isolate.internal.Contextual.run(()).map(_ => ContextEffect.suspend(Tag[TestEffect2]))
                        }
                    }
                assert(v.eval == "inner")
            }

            "the inner binding still derives from the outer after a crossing" in {
                val v =
                    ContextEffect.handle(Tag[TestEffect1], 1) {
                        ContextEffect.handle(Tag[TestEffect1], 0, _ + 10) {
                            Isolate.internal.Contextual.run(()).map(_ => ContextEffect.suspend(Tag[TestEffect1]))
                        }
                    }
                assert(v.eval == 11)
            }

            "every layer of a three-deep nest survives a crossing" in {
                val v =
                    ContextEffect.handle(Tag[TestEffect2], "outer") {
                        ContextEffect.handle(Tag[TestEffect2], "middle") {
                            ContextEffect.handle(Tag[TestEffect2], "inner") {
                                Isolate.internal.Contextual.run(()).map(_ => ContextEffect.suspend(Tag[TestEffect2]))
                            }
                        }
                    }
                assert(v.eval == "inner")
            }

            "a join lands on the binding that owns it" in {
                val v =
                    ContextEffect.handle(Tag[TestEffect1], ifUndefined = 100, ifDefined = _ => 100, join = (h: Int, f: Int) => h + f) {
                        ContextEffect.handle(Tag[TestEffect1], ifUndefined = 5, ifDefined = _ => 5, join = (h: Int, f: Int) => h + f) {
                            Isolate.internal.Contextual.run(())
                        }.map(_ => ContextEffect.suspend(Tag[TestEffect1]))
                    }
                assert(v.eval == 200)
            }
        }
    }

    "variance" - {
        "Remove parameter (invariant)" - {
            "cannot accept supertypes" in {
                typeCheckFailure("""
                    val isolate: Isolate[TestEffect1, Any, Any] = Isolate.derive[TestEffect1, Any, Any]
                    val _: Isolate[Any, Any, Any] = isolate
                """)(
                    "Required: kyo.kernel.Isolate[Any, Any, Any]"
                )
            }

            "cannot accept subtypes" in {
                typeCheckFailure("""
                    val isolate: Isolate[Any, Any, Any] = Isolate.internal.Contextual
                    val _: Isolate[TestEffect1, Any, Any] = isolate
                """)(
                    "Required: kyo.kernel.Isolate[IsolateTest.this.TestEffect1, Any, Any]"
                )
            }
        }

        "Keep parameter (contravariant)" - {
            "accepts subtypes" in {
                val isolate: Isolate[TestEffect1, Any, Any]   = Isolate.derive[TestEffect1, Any, Any]
                val _: Isolate[TestEffect1, TestEffect2, Any] = isolate
                succeed
            }

            "does not accept supertypes" in {
                typeCheckFailure("""
                    val isolate: Isolate[TestEffect1, TestEffect2, Any] = Isolate.derive[TestEffect1, TestEffect2, Any]
                    val _: Isolate[TestEffect1, Any, Any] = isolate
                """)(
                    "Required: kyo.kernel.Isolate[IsolateTest.this.TestEffect1, Any, Any]"
                )
            }
        }

        "Restore parameter (covariant)" - {
            "accepts supertypes" in {
                val isolate: Isolate[TestEffect1, Any, Any]   = Isolate.derive[TestEffect1, Any, Any]
                val _: Isolate[TestEffect1, Any, TestEffect1] = isolate
                succeed
            }

            "does not accept subtypes" in {
                typeCheckFailure("""
                    val isolate: Isolate[TestEffect1, Any, TestEffect1] = Isolate.derive[TestEffect1, Any, TestEffect1]
                    val _: Isolate[TestEffect1, Any, Any] = isolate
                """)(
                    "Required: kyo.kernel.Isolate[IsolateTest.this.TestEffect1, Any, Any]"
                )
            }
        }

        "mixed variance scenarios" - {
            "contravariant Keep with covariant Restore" in {
                val isolate: Isolate[TestEffect1, Any, Any]           = Isolate.derive[TestEffect1, Any, Any]
                val _: Isolate[TestEffect1, TestEffect2, TestEffect1] = isolate
                succeed
            }

            "variance preserved through andThen" in {
                val isolate1: Isolate[TestEffect1, Any, Any] = Isolate.derive[TestEffect1, Any, Any]
                val isolate2: Isolate[TestEffect2, Any, Any] = Isolate.derive[TestEffect2, Any, Any]

                val composed                                                                      = isolate1.andThen(isolate2)
                val _: Isolate[TestEffect1 & TestEffect2, TestEffect3, TestEffect1 & TestEffect2] = composed
                succeed
            }

            "complex intersection types" in {
                val isolate: Isolate[TestEffect1 & TestEffect2, Any, Any] =
                    Isolate.derive[TestEffect1 & TestEffect2, Any, Any]
                val _: Isolate[TestEffect1 & TestEffect2, TestEffect3, TestEffect1] = isolate
                succeed
            }

            "all three variance interactions" in {
                val isolate: Isolate[TestEffect1, Any, Any]                                       = Isolate.derive[TestEffect1, Any, Any]
                val _: Isolate[TestEffect1, TestEffect2 & TestEffect3, TestEffect1 & TestEffect2] = isolate
                succeed
            }
        }
    }

    "nest" - {
        "tunnels effects through isolation" in {
            val isolate                   = Isolate.derive[TestEffect1, Any, TestEffect1]
            val effect: Int < TestEffect1 = ContextEffect.suspend(Tag[TestEffect1])

            val nested: Int < TestEffect1 < TestEffect1 = isolate.nest(effect)
            val flattened: Int < TestEffect1            = nested.flatten

            assert(Eval(ContextEffect.handle(Tag[TestEffect1], 42, _ + 1)(flattened)) == 42)
        }

        "allows effect handling between nest and flatten" in {
            val isolate = Isolate.derive[TestEffect1, TestEffect2, Any]
            val effect: Int < (TestEffect1 & TestEffect2) =
                ContextEffect.suspend(Tag[TestEffect1]).map(x =>
                    ContextEffect.suspend(Tag[TestEffect2]).map(y => x + y.length)
                )

            val nested = isolate.nest(effect)

            val handled   = ContextEffect.handle(Tag[TestEffect2], "hello", _.toUpperCase)(nested)
            val flattened = handled.flatten

            assert(Eval(ContextEffect.handle(Tag[TestEffect1], 10, _ + 1)(flattened)) == 15)
        }

        "transforms Remove to Restore in type signature" in {
            val isolate                   = Isolate.derive[TestEffect1, Any, TestEffect2]
            val effect: Int < TestEffect1 = ContextEffect.suspend(Tag[TestEffect1])

            val nested: Int < TestEffect2 < TestEffect1 = isolate.nest(effect)
            val _: Int < TestEffect2 < TestEffect1      = nested
            succeed
        }

        "a stateful isolate defers the restore to the nested layer" in {
            val nested = updateA.nest(setA(9).map(_ => getA))
            // capture and isolation run under the outer region; the restore is still pending inside
            // the nested value, so the outer region's state is untouched
            val (outerState, pendingRestore) = Eval(runA(0)(nested))
            assert(outerState == 0)
            // applying the nested layer installs the isolation's final state where it runs
            assert(Eval(runA(5)(pendingRestore)) == ((9, 9)))
        }
    }

    "apply" - {

        // the crossing with no handled effects in play, so what crosses is exactly what the bindings
        // decide. The tests below examine the crossed computation itself, so it is handed out as a value
        // rather than consumed in place
        def crossing[A, S](v: A < S)(using Frame): (A < S) < Any =
            Isolate.internal.Contextual(v)(Kyo.lift[A < S, Any](_))

        // reads with a default, so a crossed computation can run with nothing bound and say so
        def read1: Int < Any     = ContextEffect.suspend[Int, TestEffect1](Tag[TestEffect1], -1)
        def read2: String < Any  = ContextEffect.suspend[String, TestEffect2](Tag[TestEffect2], "none")
        def read3: Boolean < Any = ContextEffect.suspend[Boolean, TestEffect3](Tag[TestEffect3], false)

        def bind1[A, S](value: Int)(v: A < S)(using Frame): A < S =
            ContextEffect.handle(Tag[TestEffect1], value, (_: Int) => value)(v)

        "with nothing bound, the computation is unchanged" in {
            val crossed = Eval(Isolate.internal.Contextual(read1)(Kyo.lift[Int < Any, Any](_)))
            assert(Eval(crossed) == -1)
        }

        "a bound value crosses into the computation" in {
            // the crossing is prepared inside the binding's extent and evaluated outside it: what the
            // computation reads is what was bound where it was forked, not where it runs
            val crossed = Eval(bind1(42)(crossing(read1)))
            assert(Eval(crossed) == 42)
        }

        "a binding that refuses the crossing does not cross" in {
            val v = ContextEffect.handle(Tag[TestEffect1], 42, (_: Int) => 42, fork = (_: Int) => Maybe.empty[Int])(
                crossing(read1)
            )
            assert(Eval(Eval(v)) == -1)
        }

        "a binding crosses as what its strategy answers" in {
            val v = ContextEffect.handle(Tag[TestEffect1], 42, (_: Int) => 42, fork = (n: Int) => Maybe(n * 2))(
                crossing(read1)
            )
            assert(Eval(Eval(v)) == 84)
        }

        "every binding in scope is asked" in {
            val v =
                ContextEffect.handle(Tag[TestEffect1], 1, (_: Int) => 1)(
                    ContextEffect.handle(Tag[TestEffect2], "a", (_: String) => "a", fork = (_: String) => Maybe.empty[String])(
                        ContextEffect.handle(Tag[TestEffect3], true, (_: Boolean) => true)(
                            crossing(read1.map(a => read2.map(b => read3.map(c => (a, b, c)))))
                        )
                    )
                )
            // the first and third cross, the second refuses and reads its default
            assert(Eval(Eval(v)) == ((1, "none", true)))
        }

        "the innermost binding of a tag is what crosses" in {
            val v = bind1(1)(bind1(2)(crossing(read1)))
            assert(Eval(Eval(v)) == 2)
        }

        "the crossed value is complete: it runs more than once, anywhere" in {
            val crossed = Eval(bind1(7)(crossing(read1.map(_ + 1))))
            assert(Eval(crossed) == 8)
            assert(Eval(crossed) == 8)
            // and under a binding of its own, which it does not take: what crossed is frozen
            assert(Eval(bind1(99)(crossed)) == 8)
        }

        "the forking computation keeps what it had" in {
            val v = bind1(5)(crossing(read1).map(crossed => read1.map(mine => (mine, Eval(crossed)))))
            assert(Eval(v) == ((5, 5)))
        }

        "a resource does not cross" in {
            var released = false
            val v =
                Effect.bracket(1)(_ => released = true) { _ =>
                    crossing(read1)
                }
            val crossed = Eval(v)
            // the bracket ended with the forking computation, and the crossed value owes nothing:
            // running it releases nothing a second time
            assert(released)
            released = false
            assert(Eval(crossed) == -1)
            assert(!released)
        }

        "a crossing runs where it was defined" in {
            // the strategy reads an effect of its own, which the forking computation's handler answers
            var asked = 0
            val v =
                ContextEffect.handle(
                    Tag[TestEffect1],
                    3,
                    (_: Int) => 3,
                    fork = (n: Int) =>
                        asked += 1
                        read3.map(flag => if flag then Maybe(n * 10) else Maybe(n))
                )(
                    ContextEffect.handle(Tag[TestEffect3], true, (_: Boolean) => true)(crossing(read1))
                )
            assert(Eval(Eval(v)) == 30)
            assert(asked == 1)
        }

        "the fused form hands the crossing straight to its consumer" in {
            // no step between the crossing and what reads it, and the crossed computation is never a
            // value of its own, so nothing nests it
            val v = bind1(11)(Isolate.internal.Contextual(read1)(crossed => Eval(crossed) + 1))
            assert(Eval(v) == 12)
        }

        "what a fork ended holding is joined into what is bound here" in {
            // the strategy takes the fork's value; running the isolation in place is what a join needs,
            // since a fork nobody waits for has nothing to join into
            val counter =
                ContextEffect.handle(
                    Tag[TestEffect1],
                    1,
                    (_: Int) => 1,
                    join = (held: Int, forked: Int) => held + forked
                )(
                    Isolate.internal.Contextual.run(read1).map(_ => read1)
                )
            // the isolation read 1 and ended holding it, and the join added it to what is bound here
            assert(Eval(counter) == 2)
        }

        "a binding the fork did not carry is left alone" in {
            val v =
                ContextEffect.handle(
                    Tag[TestEffect1],
                    5,
                    (_: Int) => 5,
                    fork = (_: Int) => Maybe.empty[Int],
                    join = (held: Int, forked: Int) => held + forked
                )(
                    Isolate.internal.Contextual.run(read1).map(_ => read1)
                )
            // nothing crossed, so nothing came back to join with: the binding still holds its own
            assert(Eval(v) == 5)
        }

        "a composed isolate crosses the context too" in {
            // what is bound around a fork crosses it whatever else the fork handles, so composing the
            // context isolate with one that handles an effect must keep both halves
            val composed     = Isolate.internal.Contextual.andThen(localA)
            val (_, crossed) = Eval(runA(0)(bind1(42)(composed(read1)(Kyo.lift[Int < Any, Any](_)))))
            assert(Eval(crossed) == 42)
        }

        "a fork of a fork asks the same strategies" in {
            val v = ContextEffect.handle(Tag[TestEffect1], 2, (_: Int) => 2, fork = (n: Int) => Maybe(n + 1))(
                crossing(crossing(read1))
            )
            // the first crossing answers 3, and the crossed binding keeps the strategy, so the second
            // crossing asks it again and answers 4
            assert(Eval(Eval(Eval(v))) == 4)
        }
    }

end IsolateTest
