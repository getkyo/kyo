package kyo.kernel

import kyo.Const
import kyo.Frame
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

        "Identity is a neutral element" in {
            assert(updateA.andThen(Isolate.internal.Identity) eq updateA)
            assert(Isolate.internal.Identity.andThen(updateA) eq updateA)
        }
    }

    "use" - {
        "provides the isolate as a given" in {
            def op(using i: Isolate[CellA, Any, CellA]): Int < CellA = i.run(setA(3).map(_ => getA))
            val r                                                    = updateA.use(op)
            assert(Eval(runA(0)(r)) == ((3, 3)))
        }
    }

    "Identity" - {
        "passes the computation through untouched" in {
            val v: Int < Any = 42
            assert(Isolate.internal.Identity.run(v).eval == 42)
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
                    val isolate: Isolate[Any, Any, Any] = Isolate.internal.Identity
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

end IsolateTest
