package kyo.proto.kernel

import kyo.Const
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.*
import kyo.Tag
import kyo.Test
import kyo.discard
import kyo.proto.Arrow
import kyo.proto.Kyo
import kyo.proto.Loop
import kyo.proto.kernel.internal.Stack

class IsolateTest extends Test:

    sealed trait TestEffect1         extends ContextEffect[Int]
    sealed trait TestEffect2         extends ContextEffect[String]
    sealed trait TestEffect3         extends ContextEffect[Boolean]
    sealed trait NotContextEffect    extends ArrowEffect[Const[Int], Const[Int]]
    sealed trait NotContextEffectSub extends NotContextEffect

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
            assert(ContextEffect.handleInheritable(Tag[TestEffect1], 10)(v).eval == 10)
        }

        "resolves implicitly for context effects" in {
            val i = summon[Isolate[TestEffect1, Any, TestEffect1]]
            val v = i.run(ContextEffect.suspend(Tag[TestEffect1]))
            assert(ContextEffect.handleInheritable(Tag[TestEffect1], 7)(v).eval == 7)
        }
    }

    "run" - {
        "threads capture, isolation, and restore" in {
            val v = updateA.run(setA(5).map(_ => getA).map(_ + 1))

            assert(runA(0)(v).eval == ((5, 6)))
        }

        "isolation starts from the captured enclosing state" in {
            val v = updateA.run(getA.map(_ + 100))
            assert(runA(7)(v).eval == ((7, 107)))
        }

        "a local isolate keeps the enclosing state untouched" in {
            val v = localA.run(setA(100).map(_ => getA))
            assert(runA(7)(v).eval == ((7, 100)))
        }

        "a pending arrow effect crosses the boundary and is handled outside" in {
            def op(n: Int): Int < NotContextEffect = ArrowEffect.suspend[Any](Tag[NotContextEffect], n)

            val v = updateA.run(setA(5).map(_ => op(10)).map(_ + 1))
            val handled: Int < CellA =
                ArrowEffect.handleCont(Tag[NotContextEffect], v)([C] => (input, cont) => cont(input * 2), a => a)
            assert(runA(0)(handled).eval == ((5, 21)))
        }

        "an operation raised at a subtype tag crosses and is answered outside" in {
            def opSub(n: Int): Int < NotContextEffect =
                ArrowEffect.suspend[Any](Tag[NotContextEffectSub].asInstanceOf[Tag[NotContextEffect]], n)
            val v = updateA.run(setA(5).map(_ => opSub(10)).map(_ + 1))
            val handled: Int < CellA =
                ArrowEffect.handleCont(Tag[NotContextEffectSub], v)([C] => (input, cont) => cont(input * 2), a => a)
            assert(runA(0)(handled).eval == ((5, 21)))
        }
    }

    "andThen" - {
        "composes captures, isolations, and restores of both isolates" in {
            val both = updateA.andThen(updateB)
            val body: Int < (CellA & CellB) =
                setA(1).map(_ => setB(2)).map(_ => getA.map(a => getB.map(b => a * 10 + b)))
            val v = both.run(body)
            assert(runA(0)(runB(0)(v)).eval == ((1, (2, 12))))
        }

        "composing with Contextual leaves the other side's management untouched" in {
            val before = runA(0)(updateA.run(setA(5).map(_ => getA))).eval
            assert(runA(0)(updateA.andThen(Isolate.internal.Contextual).run(setA(5).map(_ => getA))).eval == before)
            assert(runA(0)(Isolate.internal.Contextual.andThen(updateA).run(setA(5).map(_ => getA))).eval == before)
        }
    }

    "use" - {
        "provides the isolate as a given" in {
            def op(using i: Isolate[CellA, Any, CellA]): Int < CellA = i.run(setA(3).map(_ => getA))
            val r                                                    = updateA.use(op)
            assert(runA(0)(r).eval == ((3, 3)))
        }
    }

    "apply" - {
        "crosses the state to the consumer" in {

            val v: Int < CellA = updateA(setA(5).map(_ => getA.map(_ + 1)))(crossed => crossed)
            assert(runA(0)(v).eval == ((5, 6)))
        }
    }

    "Contextual" - {

        "passes the computation through untouched" in {
            val v: Int < Any = 42
            assert(Isolate.internal.Contextual.run(v).eval == 42)
        }

        sealed trait Fork extends ArrowEffect[Const[Unit], Const[Int]]
        def forkHere: Int < Fork = ArrowEffect.suspend[Any](Tag[Fork], ())

        def continuationOf[A](v: A < Fork): Arrow[Int, A, Fork] =
            var out = Maybe.empty[Arrow[Int, A, Fork]]
            val r: Unit < Any = ArrowEffect.handleFirst(Tag[Fork], v)(
                [C] =>
                    (_, cont) =>
                        out = Maybe(cont)
                        ()
                ,
                _ => ()
            )
            discard(r.eval)
            out.get
        end continuationOf

        def runFork[A, S](v: A < (Fork & S)): A < S =
            ArrowEffect.handleCont(Tag[Fork], v)([C] => (_, cont) => cont(0), a => a)

        "a binding crossed at the suspension is present in a detached resume" in {
            val body: Int < (Fork & TestEffect1) = forkHere.map(n => ContextEffect.suspend(Tag[TestEffect1]).map(_ + n))
            val bound: Int < Fork                = ContextEffect.handleInheritable(Tag[TestEffect1], 10)(body)
            val cont                             = continuationOf(bound)

            assert(runFork(cont(5)).eval == 15)
        }

        "the continuation is a complete value: it resumes more than once, anywhere" in {
            val body  = forkHere.map(n => ContextEffect.suspend(Tag[TestEffect1]).map(_ * n))
            val bound = ContextEffect.handleInheritable(Tag[TestEffect1], 3)(body)
            val cont  = continuationOf(bound)
            assert(runFork(cont(2)).eval == 6)
            assert(runFork(cont(5)).eval == 15)
        }

        "the fork point splits: the boundary run stops there and the resume carries on" in {
            var past = 0
            val body = forkHere.map { n =>
                past += 1
                n
            }
            val cont = continuationOf(ContextEffect.handleInheritable(Tag[TestEffect1], 1)(body))
            assert(past == 0)
            assert(runFork(cont(7)).eval == 7)
            assert(past == 1)
        }

        "the inner binding of a tag still answers after the crossing" in {
            val body = forkHere.map(_ => ContextEffect.suspend(Tag[TestEffect2]))
            val bound =
                ContextEffect.handleInheritable(Tag[TestEffect2], "outer") {
                    ContextEffect.handleInheritable(Tag[TestEffect2], "inner")(body)
                }
            val cont = continuationOf(bound)
            assert(runFork(cont(0)).eval == "inner")
        }

        "derived layers reconstruct the fork point values on resume" in {

            val body = forkHere.map(_ => ContextEffect.suspend(Tag[TestEffect1]))
            val bound =
                ContextEffect.handleInheritable(Tag[TestEffect1], 1) {
                    ContextEffect.handleInheritable(Tag[TestEffect1], 0, _ + 10)(body)
                }
            val cont = continuationOf(bound)
            assert(runFork(cont(0)).eval == 11)
        }

        "a crossed binding resumes at its captured value" in {

            val body  = forkHere.map(_ => ContextEffect.suspend(Tag[TestEffect1]))
            val bound = ContextEffect.handleInheritable(Tag[TestEffect1], 0, _ + 10)(body)
            val cont  = continuationOf(bound)
            assert(runFork(cont(0)).eval == 0)
            assert(ContextEffect.handleInheritable(Tag[TestEffect1], 5)(runFork(cont(0))).eval == 0)
        }
    }

    "variance" - {
        "Remove parameter (invariant)" - {
            "cannot accept supertypes" in {
                typeCheckFailure("""
                    val isolate: Isolate[TestEffect1, Any, Any] = Isolate.derive[TestEffect1, Any, Any]
                    val _: Isolate[Any, Any, Any] = isolate
                """)(
                    "Required: kyo.proto.kernel.Isolate[Any, Any, Any]"
                )
            }

            "cannot accept subtypes" in {
                typeCheckFailure("""
                    val isolate: Isolate[Any, Any, Any] = Isolate.internal.Contextual
                    val _: Isolate[TestEffect1, Any, Any] = isolate
                """)(
                    "Required: kyo.proto.kernel.Isolate[IsolateTest.this.TestEffect1, Any, Any]"
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
                    "Required: kyo.proto.kernel.Isolate[IsolateTest.this.TestEffect1, Any, Any]"
                )
            }
        }

        "Restore parameter (contravariant)" - {
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
                    "Required: kyo.proto.kernel.Isolate[IsolateTest.this.TestEffect1, Any, Any]"
                )
            }
        }

        "mixed variance scenarios" - {
            "contravariant Keep with contravariant Restore" in {
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

            assert(ContextEffect.handleInheritable(Tag[TestEffect1], 42)(flattened).eval == 42)
        }

        "allows effect handling between nest and flatten" in {
            val isolate = Isolate.derive[TestEffect1, TestEffect2, Any]
            val effect: Int < (TestEffect1 & TestEffect2) =
                ContextEffect.suspend(Tag[TestEffect1]).map(x =>
                    ContextEffect.suspend(Tag[TestEffect2]).map(y => x + y.length)
                )

            val nested = isolate.nest(effect)

            val handled   = ContextEffect.handleInheritable(Tag[TestEffect2], "hello")(nested)
            val flattened = handled.flatten

            assert(ContextEffect.handleInheritable(Tag[TestEffect1], 10)(flattened).eval == 15)
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

            val (outerState, pendingRestore) = runA(0)(nested).eval
            assert(outerState == 0)

            assert(runA(5)(pendingRestore).eval == ((9, 9)))
        }
    }

    "the contextual isolate" - {
        val contextual = Isolate.internal.Contextual

        sealed trait Bind      extends ContextEffect[Int]
        sealed trait OuterBind extends ContextEffect[Int]

        def read: Int < Bind           = ContextEffect.suspend(Tag[Bind])
        def readOuter: Int < OuterBind = ContextEffect.suspend(Tag[OuterBind])

        "the child of an isolate reads the forked binding, the origin keeps its own" in {
            val prog: (Int, Int) < Bind =
                contextual.capture { st =>
                    contextual.restore(contextual.isolate(st, read)).map(child => read.map(origin => (child, origin)))
                }
            val r = ContextEffect.handle(Tag[Bind])(
                _.getOrElse(10),
                fork = (parent: Int) => parent * 2,
                join = (parent: Int, _: Int, _: Int) => parent
            )(prog)
            assert(r.eval == (20, 10))
        }

        "handleInheritable shares the binding and keeps the origin state" in {
            val prog: (Int, Int) < Bind =
                contextual.capture { st =>
                    contextual.restore(contextual.isolate(st, read)).map(child => read.map(origin => (child, origin)))
                }
            assert(ContextEffect.handleInheritable(Tag[Bind], 10)(prog).eval == (10, 10))
        }

        "join observes the parent's current state, the forked state, and the child's final state" in {
            var seen = List.empty[(Int, Int, Int)]
            val prog: Int < Bind =
                contextual.capture { st =>
                    contextual.restore(contextual.isolate(st, read))
                }
            val r = ContextEffect.handle(Tag[Bind])(
                _.getOrElse(10),
                fork = (parent: Int) => parent * 2,
                join = (parent: Int, forked: Int, child: Int) =>
                    seen = (parent, forked, child) :: seen
                    parent
            )(prog)
            assert(r.eval == 20)
            assert(seen == List((10, 20, 20)))
        }

        "the origin continues at the joined state" in {
            val prog: (Int, Int) < Bind =
                contextual.capture { st =>
                    contextual.restore(contextual.isolate(st, read)).map(child => read.map(after => (child, after)))
                }
            val r = ContextEffect.handle(Tag[Bind])(
                _.getOrElse(10),
                fork = (parent: Int) => parent * 2,
                join = (parent: Int, _: Int, child: Int) => parent + child
            )(prog)
            assert(r.eval == (20, 30))
        }

        "joins run for every region in scope, in entry order" in {
            var order = List.empty[String]
            val body: Int < (Bind & OuterBind) =
                contextual.capture { st =>
                    contextual.restore(contextual.isolate(st, read.map(a => readOuter.map(_ + a))))
                }
            val inner = ContextEffect.handle(Tag[Bind])(
                _.getOrElse(1),
                fork = (parent: Int) => parent,
                join = (parent: Int, _: Int, _: Int) =>
                    order = "bind" :: order
                    parent
            )(body)
            val r = ContextEffect.handle(Tag[OuterBind])(
                _.getOrElse(2),
                fork = (parent: Int) => parent,
                join = (parent: Int, _: Int, _: Int) =>
                    order = "outer" :: order
                    parent
            )(inner)
            assert(r.eval == 3)
            assert(order == List("bind", "outer"))
        }

        "a region exited before the merge is not joined" in {
            var joins = 0
            val captured: (Stack.Snapshot, Stack.Snapshot, Int) < Any =
                ContextEffect.handle(Tag[Bind])(
                    _.getOrElse(10),
                    fork = (parent: Int) => parent,
                    join = (parent: Int, _: Int, _: Int) =>
                        joins += 1
                        parent
                )(contextual.capture(st => contextual.isolate(st, read)))
            val r: Int < Any = contextual.restore(captured)
            assert(r.eval == 10)
            assert(joins == 0)
        }

        "with no region in scope the cycle is the identity" in {
            val prog: Int < Any =
                contextual.capture { st =>
                    contextual.restore(contextual.isolate(st, 42: Int < Any)).map(_ + 1)
                }
            assert(prog.eval == 43)
        }

        "an isolated computation replays at its forked state" in {
            var forks = 0
            val prog: (Int, Int) < Bind =
                contextual.capture { st =>
                    val iso = contextual.isolate(st, read)
                    contextual.restore(iso).map(a => contextual.restore(iso).map(b => (a, b)))
                }
            val r = ContextEffect.handle(Tag[Bind])(
                _.getOrElse(10),
                fork = (parent: Int) =>
                    forks += 1
                    parent + 100
                ,
                join = (parent: Int, _: Int, _: Int) => parent
            )(prog)
            assert(r.eval == (110, 110))
            assert(forks == 1)
        }
    }

    "ported crossings" - {
        def crossing[A, S](v: A < S)(using Frame): (A < S) < Any =
            Isolate.internal.Contextual(v)(Kyo.lift[A < S, Any](_))

        def read1: Int < Any     = ContextEffect.suspend[Int, TestEffect1](Tag[TestEffect1], -1)
        def read2: String < Any  = ContextEffect.suspend[String, TestEffect2](Tag[TestEffect2], "none")
        def read3: Boolean < Any = ContextEffect.suspend[Boolean, TestEffect3](Tag[TestEffect3], false)

        def bind1[A, S](value: Int)(v: A < S)(using Frame): A < S =
            ContextEffect.handleInheritable(Tag[TestEffect1], value, (_: Int) => value)(v)

        "every layer of a three-deep nest survives a crossing" in {
            val v =
                ContextEffect.handleInheritable(Tag[TestEffect2], "outer") {
                    ContextEffect.handleInheritable(Tag[TestEffect2], "middle") {
                        ContextEffect.handleInheritable(Tag[TestEffect2], "inner") {
                            Isolate.internal.Contextual.run(()).map(_ => ContextEffect.suspend(Tag[TestEffect2]))
                        }
                    }
                }
            assert(v.eval == "inner")
        }

        "a merging join updates the layer that was visible at the fork" in {
            val v =
                ContextEffect.handle(Tag[TestEffect1])(100, (_: Int) => 100, (s: Int) => s, (p: Int, f: Int, _: Int) => p + f) {
                    ContextEffect.handle(Tag[TestEffect1])(5, (_: Int) => 5, (s: Int) => s, (p: Int, f: Int, _: Int) => p + f) {
                        Isolate.internal.Contextual.run(()).map(_ => ContextEffect.suspend(Tag[TestEffect1]))
                    }
                }
            assert(v.eval == 10)
        }

        "a merging join outlives an arrow region that closes after the restore" in {
            val v =
                ContextEffect.handle(Tag[TestEffect1])(5, (_: Int) => 5, (s: Int) => s, (p: Int, f: Int, _: Int) => p + f) {
                    ArrowEffect.handleCont(Tag[NotContextEffect], Isolate.internal.Contextual.run(()))([C] => (_, cont) => cont(0), a => a)
                        .map(_ => ContextEffect.suspend(Tag[TestEffect1]))
                }
            assert(v.eval == 10)
        }

        "a restored crossing reads the binding it captured, not the scope it restores in" in {
            val v =
                ContextEffect.handle(Tag[TestEffect1])(1, (_: Int) => 1, (s: Int) => s, (p: Int, f: Int, _: Int) => p + f) {
                    ContextEffect.handle(Tag[TestEffect1])(2, (_: Int) => 2, (s: Int) => s, (p: Int, f: Int, _: Int) => p + f) {
                        Isolate.internal.Contextual.nest(())
                    }.map { nested =>
                        ContextEffect.handle(Tag[TestEffect1])(10, (_: Int) => 10, (s: Int) => s, (p: Int, f: Int, _: Int) => p + f) {
                            nested.map(_ => ContextEffect.suspend(Tag[TestEffect1]))
                        }
                    }
                }
            assert(v.eval == 2)
        }

        "every binding in scope is asked" in {
            val v =
                ContextEffect.handleInheritable(Tag[TestEffect1], 1, (_: Int) => 1)(
                    ContextEffect.handleInheritable(Tag[TestEffect2], "a", (_: String) => "a")(
                        ContextEffect.handleInheritable(Tag[TestEffect3], true, (_: Boolean) => true)(
                            crossing(read1.map(a => read2.map(b => read3.map(c => (a, b, c)))))
                        )
                    )
                )
            assert(v.eval.eval == ((1, "a", true)))
        }

        "an intervening map leaves the crossing resolving against the stack live at its point" in {
            val composed = crossing(read1).map(child => child.map(_ + 1))
            val child    = bind1(7)(composed).eval
            assert(child == 8)
        }

        "the fused form hands the crossing straight to its consumer" in {
            val v = bind1(11)(Isolate.internal.Contextual(read1)(crossed => crossed.eval + 1))
            assert(v.eval == 12)
        }

        "a composed isolate crosses the context too" in {
            val composed     = Isolate.internal.Contextual.andThen(updateA)
            val (_, crossed) = runA(0)(bind1(42)(composed(read1)(Kyo.lift[Int < (CellA & Any), Any](_)))).eval
            assert(runA(0)(crossed).map(_._2).eval == 42)
        }

        "a fork of a fork asks the same strategies" in {
            val v = ContextEffect.handle(Tag[TestEffect1])(2, (_: Int) => 2, (n: Int) => n + 1, (p: Int, _: Int, _: Int) => p)(
                crossing(crossing(read1))
            )
            assert(v.eval.eval.eval == 4)
        }
    }

end IsolateTest
