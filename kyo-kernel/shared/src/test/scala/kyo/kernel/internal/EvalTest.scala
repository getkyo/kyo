package kyo.kernel.internal

import kyo.Arrow
import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.discard
import kyo.kernel.*
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

class EvalTest extends kyo.test.Test[Any]:

    private val Period = Safepoint.period()

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    // the operation with its continuation fused into the node: the remainder is the node's own
    // arrow, not entries on the evaluator's stack
    inline def askWith[B, S](inline f: Int => B < S): B < (Ask & S) = ArrowEffect.suspendWith[Any](Tag[Ask], ())(f)

    sealed trait Say  extends ArrowEffect[Const[String], Const[Unit]]
    sealed trait VarE extends ArrowEffect[Const[Int => Int], Const[Int]]

    def say(s: String): Unit < Say       = ArrowEffect.suspend[Any](Tag[Say], s)
    def varOp(f: Int => Int): Int < VarE = ArrowEffect.suspend[Any](Tag[VarE], f)

    // row-generic, so a region can be handled while other effects stay open
    def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value), a => a)

    def recordSay[A, S](name: String, log: ListBuffer[String])(v: A < (Say & S)): A < S =
        ArrowEffect.handleLoop(Tag[Say], v)(
            [C] =>
                _ =>
                    log += name
                    Loop.continue(())
            ,
            a => a
        )

    def runVar[A, S](init: Int)(v: A < (VarE & S)): A < S =
        ArrowEffect.handleLoopState(Tag[VarE], init, v)(
            [C] =>
                (state, f) =>
                    val v2 = f(state)
                    Loop.continue(v2, v2)
            ,
            (_, a) => a
        )

    def box[A](v: A): A < Any = v

    "values and map" - {
        "a settled value evaluates to itself" in {
            assert(Eval(42: Int < Any) == 42)
        }

        "map runs strictly on a settled value" in {
            var ran = false
            val v = (1: Int < Any).map { n =>
                ran = true
                n + 1
            }
            assert(ran)
            assert(Eval(v) == 2)
        }

        "map composes" in {
            assert(Eval((1: Int < Any).map(_ + 1).map(_ * 10)) == 20)
        }

        "evaluates andThen and unit" in {
            assert(Eval((1: Int < Any).andThen(2: Int < Any)) == 2)
            assert(Eval((1: Int < Any).unit) == ())
        }

        "evalNow is present only for settled values" in {
            assert((1: Int < Any).evalNow.contains(1))
            assert(ask.evalNow.isEmpty)
        }

        "a long map tower evaluates in bounded stack" in {
            @tailrec def tower(v: Int < Any, n: Int): Int < Any =
                if n == 0 then v else tower(v.map(_ + 1), n - 1)
            assert(Eval(tower(0, 1000000)) == 1000000)
        }

        "a deep map tower over a suspension evaluates in bounded stack" in {
            @tailrec def tower(v: Int < Ask, n: Int): Int < Ask =
                if n == 0 then v else tower(v.map(_ + 1), n - 1)
            assert(Eval(answerAsk(1)(tower(ask, 100000))) == 100001)
        }

        "deep recursion through map pays rescues only" in {
            def loop(i: Int): Int < Any =
                if i == 0 then 0 else (0: Int < Any).map(_ => loop(i - 1))
            assert(Eval(loop(1000000)) == 0)
        }

        "a computation held as a value round trips through the box" in {
            val inner: Int < Ask         = ask.map(_ + 1)
            val outer: (Int < Ask) < Any = box(inner)
            assert(Eval(answerAsk(41)(Eval(outer))) == 42)
        }

        "double nesting round trips one level per eval" in {
            val inner: Int < Ask                 = ask.map(_ + 1)
            val twice: ((Int < Ask) < Any) < Any = box(box(inner))
            assert(Eval(answerAsk(41)(Eval(Eval(twice)))) == 42)
        }

        "a nested computation stays data until flattened" in {
            val nested: (Int < Ask) < Any = box(ask.map(_ + 1))
            assert(Eval(answerAsk(1)(nested.flatten)) == 2)
        }

        "a pending value does not lift into a nested computation implicitly" in {
            typeCheckFailure("val x: (Int < Any) < Any = (1: Int < Any).map(_ + 1)")("Required: Int < Any < Any")
        }

        "an eval inside a map evaluates its argument rather than nesting it" in {
            // with an unconditional lift, inference solved the unannotated form as
            // Eval[Int < Any](lift(ask)) and the suspension itself came back as the map's result;
            // with the lint on lift the unannotated form does not compile, and the annotated one evaluates
            typeCheckFailure("Eval(answerAsk(1)(ask.map(_ => Eval(ask.asInstanceOf[Int < Any]))))")(
                "Required: Any < (EvalTest.this.Ask & Nothing) < Nothing"
            )
            val ex = intercept[Throwable](Eval(answerAsk(1)(ask.map(_ => Eval[Int, Any](ask.asInstanceOf[Int < Any])))))
            assert(ex.getMessage.contains("Unexpected pending effect"))
        }

        "map receives a computation held as a value unopened" in {
            val inner: Int < Ask    = ask
            var received: Int < Ask = 0
            val r: Int < Any = box(inner).map { c =>
                received = c
                7
            }
            assert(Eval(r) == 7)
            assert(Eval(answerAsk(41)(received.map(_ + 1))) == 42)
        }
    }

    "handleCont" - {
        "answers with the continuation in hand" in {
            val body         = ask.map(a => ask.map(b => a + b))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => cont(10), a => a)
            assert(Eval(r) == 20)
        }

        "the captured continuation is multi-shot" in {
            val body = ask.map(_ * 2)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] => (_, cont) => cont(1).map(x => cont(2).map(y => x + y)),
                a => a
            )
            assert(Eval(r) == 6)
        }

        "can end the computation without resuming" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a + 1
            }
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
            assert(Eval(r) == -1)
            assert(!reached)
        }

        "a settled input applies done strictly" in {
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], 41: Int < Ask)([C] => (_, cont) => cont(0), a => a + 1)
            assert(Eval(r) == 42)
        }

        "done applies to the settled result" in {
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))([C] => (_, cont) => cont(41), a => a * 10)
            assert(Eval(r) == 420)
        }

        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], loop(100000))([C] => (_, cont) => cont(1), a => a)
            assert(Eval(r) == 0)
        }

        "a capture crossing an inner region resumes it without re-running its body" in {
            var runs = 0
            val inner: Int < Say = answerAsk(1)(ask.map { a =>
                runs += 1
                say("x").map(_ => a + 1)
            })
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)([C] => (_, cont) => cont(()), a => a)
            assert(Eval(r) == 2)
            assert(runs == 1)
        }

        "each shot of a multi-shot capture resumes from capture-time state" in {
            val inner: Int < Say = ArrowEffect.handleLoopState(
                Tag[Ask],
                0,
                ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b)))
            )([C] => (s, _) => Loop.continue(s + 1, s), (_, a) => a)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)(
                [C] => (_, cont) => cont(()).map(r1 => cont(()).map(r2 => r1 * 100 + r2)),
                a => a
            )
            assert(Eval(r) == 101)
        }
    }

    "handleLoop" - {
        "answers every operation in place" in {
            def loop(i: Int): Int < Ask =
                if i < 3 then ask.map(a => loop(i + a)) else i
            assert(Eval(answerAsk(1)(loop(0))) == 3)
        }

        "Loop.done stops the region and bypasses done" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a + 1
            }
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.done(-1), a => a * 10)
            assert(Eval(r) == -1)
            assert(!reached)
        }

        "done sees the settled result" in {
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.continue(41), a => a * 10)
            assert(Eval(r) == 420)
        }

        "a settled input applies done strictly" in {
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], 41: Int < Ask)([C] => _ => Loop.continue(0), a => a + 1)
            assert(Eval(r) == 42)
        }

        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            assert(Eval(answerAsk(1)(loop(100000))) == 0)
        }

        "the innermost region of a tag answers" in {
            val inner: Int < Any = answerAsk(1)(ask)
            val outer: Int < Any = answerAsk(2)(inner: Int < Ask)
            assert(Eval(outer) == 1)
        }

        "a map after the region applies to the result" in {
            assert(Eval(answerAsk(41)(ask.map(_ + 1)).map(_ * 10)) == 420)
        }

        "regions exit innermost first" in {
            val log   = ListBuffer[String]()
            val inner = answerAsk(41)(ask.map(_ + 1)).map(_ * 10)
            val outer = recordSay("s", log)(inner).map(_ + 1000)
            assert(Eval(outer) == 1420)
        }

        "a foreign operation crosses the region in place" in {
            val log                     = ListBuffer[String]()
            val body: Int < (Ask & Say) = say("a").map(_ => ask).map(_ + 1)
            assert(Eval(recordSay("outer", log)(answerAsk(41)(body))) == 42)
            assert(log.toList == List("outer"))
        }

        "an effectful answer built by a deferred block resolves on the settled outcome path" in {
            val r = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.continue(Effect.defer(7)))
            assert(Eval(r) == 8)
        }

        "a deferred clause outcome resolves before the region continues" in {
            def loop(i: Int): Int < Ask =
                if i < 3 then ask.map(a => loop(i + a)) else i
            val r = ArrowEffect.handleLoop(Tag[Ask], loop(0))([C] => _ => Effect.defer(Loop.continue(1)))
            assert(Eval(r) == 3)
        }

        "a clause that suspends before its outcome runs outside its region" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => say("c").map(_ => Loop.continue(41)),
                a => a
            )
            assert(Eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("inner", "outer"))
        }

        "a clause that suspends before a done runs outside its region" in {
            var reached = false
            val log     = ListBuffer[String]()
            val program: Int < Ask = ask.map { a =>
                reached = true
                a + 1
            }
            val askScope: Int < Say =
                ArrowEffect.handleLoop(Tag[Ask], program)([C] => _ => say("pre").map(_ => Loop.done(-1)), a => a)
            assert(Eval(recordSay("s", log)(askScope)) == -1)
            assert(!reached)
            assert(log.toList == List("s"))
        }

        "an effectful answer runs under this handler with the interior parked" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => Loop.continue(say("c").map(_ => 41)),
                a => a
            )
            assert(Eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("inner", "outer"))
        }

        "an effectful answer's own-tag re-raise is answered by the successor state" in {
            var clauseRuns = 0
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 0, ask.map(_ + 1))(
                [C] =>
                    (phase, _) =>
                        clauseRuns += 1
                        if phase == 0 then Loop.continue(1, ask.map(_ + 100)) else Loop.done(-2)
                ,
                (_, a) => a
            )
            assert(Eval(r) == -2)
            assert(clauseRuns == 2)
        }

        "a clause suspending and then answering effectfully runs the answer under this handler" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => say("pre").map(_ => Loop.continue(say("c").map(_ => 41))),
                a => a
            )
            assert(Eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("inner", "outer", "outer"))
        }

        "an effectful answer's own-tag re-raise is answered by this handler" in {
            var clauseRuns = 0
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [C] =>
                    _ =>
                        clauseRuns += 1
                        if clauseRuns == 1 then Loop.continue(ask.map(_ + 100)) else Loop.continue(1)
                ,
                a => a
            )
            assert(Eval(r) == 102)
            assert(clauseRuns == 2)
        }

        "a clause suspending and then answering with an own-tag re-raise is answered by this handler" in {
            var clauseRuns = 0
            val log        = ListBuffer[String]()
            val handled: Int < Say = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [C] =>
                    _ =>
                        clauseRuns += 1
                        if clauseRuns == 1 then say("pre").map(_ => Loop.continue(ask.map(_ + 100)))
                        else Loop.continue(1)
                ,
                a => a
            )
            assert(Eval(recordSay("outer", log)(handled)) == 102)
            assert(clauseRuns == 2)
            assert(log.toList == List("outer"))
        }

        // the operation was raised from inside the interior region, so its remainder belongs inside
        // that region: an effectful answer runs under this handler with the interior parked, and the
        // interior comes back around the remainder, not after it
        "an effectful answer's remainder runs inside the interior region" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = ask.map(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => Loop.continue(say("c").map(_ => 41)),
                a => a
            )
            assert(Eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("outer", "inner"))
        }

        "an effectful answer's remainder raises the interior's effect with no outer handler for it" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = ask.map(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            // a pending answer with nothing left in its row: a handled region is pending currency
            val pendingAnswer: Int < Any = answerAsk(0)(ask.map(_ => 41))
            val askScope: Int < Any = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => Loop.continue(pendingAnswer),
                a => a
            )
            assert(Eval(askScope) == 42)
            assert(log.toList == List("inner"))
        }

        "a clause suspending and then answering effectfully keeps the remainder inside the interior region" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = ask.map(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => say("pre").map(_ => Loop.continue(say("c").map(_ => 41))),
                a => a
            )
            assert(Eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("outer", "outer", "inner"))
        }

        // the same three shapes with the remainder fused into the operation's node: the interior is
        // parked around the answer either way, and it must come back around the remainder, whether
        // the remainder lives on the stack or in the node
        "an effectful answer's fused remainder runs inside the interior region" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = askWith(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => Loop.continue(say("c").map(_ => 41)),
                a => a
            )
            assert(Eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("outer", "inner"))
        }

        "an effectful answer's fused remainder raises the interior's effect with no outer handler for it" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = askWith(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val pendingAnswer: Int < Any   = answerAsk(0)(ask.map(_ => 41))
            val askScope: Int < Any = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => Loop.continue(pendingAnswer),
                a => a
            )
            assert(Eval(askScope) == 42)
            assert(log.toList == List("inner"))
        }

        "a clause suspending and then answering effectfully keeps the fused remainder inside the interior region" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = askWith(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => say("pre").map(_ => Loop.continue(say("c").map(_ => 41))),
                a => a
            )
            assert(Eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("outer", "outer", "inner"))
        }

        "a done climbs past an inner region without running its remainder" in {
            val log                        = ListBuffer[String]()
            var innerExit                  = false
            val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
            val mapped = recordSay("s", log)(program).map { v =>
                innerExit = true
                v
            }
            val r = ArrowEffect.handleLoop(Tag[Ask], mapped)([C] => _ => Loop.done(-1), a => a)
            assert(Eval(r) == -1)
            assert(!innerExit)
            assert(log.toList == List("s"))
        }

        "a done fired while a clause outcome settles climbs to its own region" in {
            var reached = false
            val program: Int < Ask = ask.map { a =>
                reached = true
                a + 1
            }
            val askScope =
                ArrowEffect.handleLoop(Tag[Ask], program)([C] => _ => say("pre").map(_ => Loop.continue(41)))
            val r = ArrowEffect.handleLoop(Tag[Say], askScope)([C] => _ => Loop.done(-9))
            assert(Eval(r) == -9)
            assert(!reached)
        }

        "a computation held as a value crosses a handler as a value" in {
            val payload: Int < Say   = say("p").map(_ => 7)
            val v: (Int < Say) < Ask = ask.map(_ => box(payload))
            val handled: (Int < Say) < Any =
                ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(0), a => box(a))
            var seen = ""
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], Eval(handled))(
                [C] =>
                    s =>
                        seen = s
                        Loop.continue(())
                ,
                a => a
            )
            assert(Eval(r) == 7)
            assert(seen == "p")
        }

        "an answer that is a computation held as a value stays a value" in {
            sealed trait Give extends ArrowEffect[Const[Unit], Const[Int < Ask]]
            val give: (Int < Ask) < Give = ArrowEffect.suspend[Any](Tag[Give], ())
            val inner: Int < Ask         = ask.map(_ + 1)
            val body: Int < (Give & Ask) = give.map(c => c)
            val r: Int < Any = answerAsk(41)(
                ArrowEffect.handleLoop(Tag[Give], body)([C] => _ => Loop.continue(box(inner)), a => a)
            )
            assert(Eval(r) == 42)
        }
    }

    "handleLoopState" - {
        "threads state through operations" in {
            val v = ask.map(a => ask.map(b => a * 10 + b))
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 1, v)(
                [C] => (s, _) => Loop.continue(s + 1, s),
                (_, a) => a
            )
            assert(Eval(r) == 12)
        }

        "done observes the final state" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r: (Int, Int) < Any = ArrowEffect.handleLoopState(Tag[Ask], 10, v)(
                [C] => (s, _) => Loop.continue(s + 1, s),
                (s, a) => (s, a)
            )
            assert(Eval(r) == (12, 21))
        }

        "Loop.done bypasses done" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r: String < Any = ArrowEffect.handleLoopState(Tag[Ask], 0, v)(
                [C] => (s, _) => if s == 1 then Loop.done("stopped") else Loop.continue(s + 1, 1),
                (s, a) => s"done $a"
            )
            assert(Eval(r) == "stopped")
        }

        "composes a state update with a done" in {
            def go(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => go(n - 1))
            val r = ArrowEffect.handleLoopState(Tag[Ask], 3, go(5))(
                [C] => (remaining, _) => if remaining > 0 then Loop.continue(remaining - 1, 1) else Loop.done(-1),
                (_, a) => a
            )
            assert(Eval(r) == -1)
        }

        "state survives a foreign crossing" in {
            val body: Int < (Ask & Say) = ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b)))
            val inner: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 1, body)(
                [C] => (s, _) => Loop.continue(s + 1, s),
                (_, a) => a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], inner)([C] => _ => Loop.continue(()), a => a)
            assert(Eval(r) == 12)
        }

        "state threads through updates" in {
            val program = varOp(_ => 10).map(_ => varOp(_ + 5)).map(a => varOp(identity).map(b => a + b))
            assert(Eval(runVar(0)(program)) == 30)
        }

        "state updates survive an inner region's exit" in {
            val log                       = ListBuffer[String]()
            val inner: Int < (VarE & Say) = varOp(_ => 7).map(_ => say("x")).map(_ => 1)
            val innerScope                = recordSay("s", log)(inner)
            val program                   = innerScope.map(_ => varOp(identity))
            assert(Eval(runVar(0)(program)) == 7)
            assert(log.toList == List("s"))
        }

        "a stateful clause that suspends threads its state through the park" in {
            val log = ListBuffer[String]()
            val v   = ask.map(a => ask.map(b => a * 10 + b))
            val handled: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 1, v)(
                [C] => (s, _) => say(s"state $s").map(_ => Loop.continue(s + 1, s)),
                (_, a) => a
            )
            assert(Eval(recordSay("outer", log)(handled)) == 12)
            assert(log.size == 2)
        }

        "a done fired while a stateful clause outcome settles climbs to its own region" in {
            var reached = false
            val program: Int < Ask = ask.map { a =>
                reached = true
                a + 1
            }
            val askScope = ArrowEffect.handleLoopState(Tag[Ask], 0, program)(
                [C] => (n, _) => say("pre").map(_ => Loop.continue(n + 1, n))
            )
            val r = ArrowEffect.handleLoop(Tag[Say], askScope)([C] => _ => Loop.done(-9))
            assert(Eval(r) == -9)
            assert(!reached)
        }

        "a stateful effectful answer's remainder runs inside the interior region" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = ask.map(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 40, sayInner)(
                [C] => (s, _) => Loop.continue(s + 1, say("c").map(_ => s + 1)),
                (_, a) => a
            )
            assert(Eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("outer", "inner"))
        }

        "a stateful effectful answer's fused remainder runs inside the interior region" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = askWith(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 40, sayInner)(
                [C] => (s, _) => Loop.continue(s + 1, say("c").map(_ => s + 1)),
                (_, a) => a
            )
            assert(Eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("outer", "inner"))
        }
    }

    // the clause-scope contract, across every clause kind and outcome shape. A handler's clause is
    // the handler's own code: its effects belong to the handlers OUTSIDE the region, never to the
    // ones the region's body installed inside it.
    "clause scope" - {

        val innerProgram: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)

        "a stateful clause's suspension is answered outside its scope" in {
            val log      = ListBuffer[String]()
            val sayInner = recordSay("inner", log)(innerProgram)
            val askScope = ArrowEffect.handleLoopState(Tag[Ask], 0, sayInner)(
                [C] => (n, _) => say("c").map(_ => Loop.continue(n + 1, 41))
            )
            val sayOuter = recordSay("outer", log)(askScope)
            assert(Eval(sayOuter) == 42)
            assert(log.toList == List("inner", "outer"))
        }

        "a handleCont clause's suspension is answered outside its scope" in {
            val log                         = ListBuffer[String]()
            val sayInner: Int < (Ask & Say) = recordSay("inner", log)(innerProgram)
            val askScope = ArrowEffect.handleCont(Tag[Ask], sayInner)(
                [C] => (_, cont) => say("c").map(_ => cont(41)),
                a => a
            )
            val sayOuter = recordSay("outer", log)(askScope)
            assert(Eval(sayOuter) == 42)
            assert(log.toList == List("inner", "outer"))
        }

        "a clause's suspension before done is answered outside its scope" in {
            val log      = ListBuffer[String]()
            val sayInner = recordSay("inner", log)(innerProgram)
            val askScope =
                ArrowEffect.handleLoop(Tag[Ask], sayInner)([C] => _ => say("c").map(_ => Loop.done(-1)), a => a)
            val sayOuter = recordSay("outer", log)(askScope)
            assert(Eval(sayOuter) == -1)
            assert(log.toList == List("inner", "outer"))
        }

        "a clause's effectful answer is answered outside its scope" in {
            // the clause returns an effectful VALUE as the answer, not a suspension before the outcome
            val log      = ListBuffer[String]()
            val sayInner = recordSay("inner", log)(innerProgram)
            val askScope =
                ArrowEffect.handleLoop(Tag[Ask], sayInner)([C] => _ => Loop.continue(say("c").map(_ => 41)))
            val sayOuter = recordSay("outer", log)(askScope)
            assert(Eval(sayOuter) == 42)
            assert(log.toList == List("inner", "outer"))
        }

        // The two own-tag shapes are not the same law, and the signature is what separates them.
        // A clause SUSPENDING on its own tag happens at row S, outside the region, so the eval has
        // popped this handler and the successor answers. A clause's ANSWER carries row E & S, which
        // is region currency, so it runs with this handler still installed and this handler answers
        // it. The answer shape is pinned under "handleLoop" above; this is the suspension shape.
        "a clause's own-tag suspension before its outcome is answered by the successor" in {
            // answered by this handler instead, the suspension would come back to the same clause,
            // which would suspend again: the failure mode is a livelock, so the counter bounds it
            // into a failure rather than a hang
            var clauseRuns = 0
            val askScope: Int < Ask = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [C] =>
                    _ =>
                        clauseRuns += 1
                        if clauseRuns > 3 then throw new IllegalStateException("clause answered its own suspension")
                        ask.map(x => Loop.continue(x + 100))
            )
            val outerAsk = ArrowEffect.handleLoop(Tag[Ask], askScope)([C] => _ => Loop.continue(5))
            // the clause suspends on ask, answered by outerAsk with 5, plus 100 -> 105, plus 1
            assert(Eval(outerAsk) == 106)
            assert(clauseRuns == 1)
        }

        "a clause does not see handlers inside its own scope" in {
            val log      = ListBuffer[String]()
            val sayInner = recordSay("inner", log)(innerProgram)
            val askScope =
                ArrowEffect.handleLoop(Tag[Ask], sayInner)([C] => _ => Loop.continue(say("c").map(_ => 41)), a => a)
            val ex = intercept[Throwable](Eval(askScope.asInstanceOf[Int < Any]))
            assert(ex.getMessage.contains("Unexpected pending effect"))
            assert(log.toList == List("inner"))
        }

        "a leaked clause effect cannot observe the region's inner state" in {
            // if the clause's varOp were answered by the runVar INSIDE the region it would read the
            // region's state; answered outside, there is no VarE handler and it must fail
            val program: Int < (Ask & VarE) = varOp(_ => 7).map(_ => ask)
            val varInner                    = runVar(0)(program)
            val askScope = ArrowEffect.handleLoop(Tag[Ask], varInner)(
                [C] => _ => varOp(identity).map(v => Loop.continue(v)),
                a => a
            )
            val ex = intercept[Throwable](Eval(askScope.asInstanceOf[Int < Any]))
            assert(ex.getMessage.contains("Unexpected pending effect"))
        }

        "the region body's handlers are intact after the clause returns" in {
            // the clause's say must go outside, and the body's later say must STILL find sayInner
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = ask.map(a => say("after").map(_ => a + 1))
            val sayInner                   = recordSay("inner", log)(program)
            val askScope =
                ArrowEffect.handleLoop(Tag[Ask], sayInner)([C] => _ => Loop.continue(say("c").map(_ => 41)))
            val sayOuter = recordSay("outer", log)(askScope)
            assert(Eval(sayOuter) == 42)
            assert(log.toList == List("outer", "inner"))
        }
    }

    // a continuation handed to a clause is a value the kernel produced: it carries no live stack, so
    // it means the same thing wherever and whenever it is applied, and applying it never disturbs
    // the evaluation that produced it
    "a captured continuation is a value" - {
        def stateful(body: Int < (Ask & Say)): Int < Say =
            ArrowEffect.handleLoopState(Tag[Ask], 0, body)([C] => (s, _) => Loop.continue(s + 1, s), (_, a) => a)

        def trailing(depth: Int, runs: Array[Int]): Int < Ask =
            var r: Int < Ask = ask
            for i <- 0 until depth do
                val j = i
                r = r.map { x =>
                    runs(j) += 1
                    x + 1
                }
            end for
            r
        end trailing

        "resumes after its region completed, in a fresh evaluation, each shot from capture-time state" in {
            var stored: Maybe[Arrow[Unit, Int, Say]] = Maybe.empty
            val inner: Int < Say                     = stateful(ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b))))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)(
                [C] =>
                    (_, cont) =>
                        stored = Maybe(cont)
                        -1
                ,
                a => a
            )
            assert(Eval(r) == -1)
            val k             = stored.get
            def resume(): Int = Eval(ArrowEffect.handleCont(Tag[Say], k(()))([C] => (_, cont) => cont(()), a => a))
            assert(resume() == 1)
            assert(resume() == 1)
        }

        // the cross-thread resume lives in the jvm-native EvalThreadingTest: it needs real threads

        "resumes under a later region of the same tag, which answers the remainder" in {
            var stored: Maybe[Arrow[Int, Int, Ask]] = Maybe.empty
            val body: Int < Ask                     = ask.map(a => ask.map(b => a * 10 + b))
            val first: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] =>
                    (_, cont) =>
                        stored = Maybe(cont)
                        -1
                ,
                a => a
            )
            assert(Eval(first) == -1)
            val k = stored.get
            assert(Eval(answerAsk(7)(k(1))) == 17)
            assert(Eval(answerAsk(8)(k(2))) == 28)
        }

        "a shot evaluated inside the clause leaves the region intact for the next" in {
            val inner: Int < Say = stateful(ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b))))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)(
                [C] =>
                    (_, cont) =>
                        val now = Eval(ArrowEffect.handleCont(Tag[Say], cont(()))([C] => (_, c2) => c2(()), a => a))
                        cont(()).map(later => now * 100 + later)
                ,
                a => a
            )
            assert(Eval(r) == 101)
        }

        "a continuation folded from the eval stack runs every pending map exactly once" in {
            for depth <- List(8, 64) do
                val runs = new Array[Int](depth)
                val r: Int < Any =
                    ArrowEffect.handleCont(Tag[Ask], trailing(depth, runs))([C] => (_, cont) => cont(0), a => a)
                assert(Eval(r) == depth)
                assert(runs.forall(_ == 1))
        }

        "a continuation applied twice replays trailing maps twice at any depth" in {
            for depth <- List(8, 64) do
                val runs = new Array[Int](depth)
                val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], trailing(depth, runs))(
                    [C] => (_, cont) => cont(0).map(a => cont(10).map(b => a + b)),
                    a => a
                )
                assert(Eval(r) == depth + (10 + depth))
                assert(runs.forall(_ == 2))
        }

        "stays valid after its eval completes, replaying the trailing maps once per shot" in {
            for depth <- List(8, 64) do
                val runs                                = new Array[Int](depth)
                var stored: Maybe[Arrow[Int, Int, Ask]] = Maybe.empty
                val first: Int < Any = ArrowEffect.handleCont(Tag[Ask], trailing(depth, runs))(
                    [C] =>
                        (_, cont) =>
                            stored = Maybe(cont)
                            -1
                    ,
                    a => a
                )
                assert(Eval(first) == -1)
                assert(runs.forall(_ == 0))
                val k = stored.get
                assert(Eval(answerAsk(0)(k(100))) == 100 + depth)
                assert(runs.forall(_ == 1))
                assert(Eval(answerAsk(0)(k(200))) == 200 + depth)
                assert(runs.forall(_ == 2))
        }
    }

    "an unhandled operation is a bug" in {
        val ex = intercept[Throwable](Eval(ask.asInstanceOf[Int < Any]))
        assert(ex.getMessage.contains("Unexpected pending effect"))
    }

    "an operation no region in the row handles is a bug" in {
        val program: Int < (Ask & Say) = say("x").map(_ => ask)
        val r                          = answerAsk(41)(program)
        val ex                         = intercept[Throwable](Eval(r.asInstanceOf[Int < Any]))
        assert(ex.getMessage.contains("Unexpected pending effect"))
    }

    "a nested eval shares the thread's stack and sees none of the outer regions" in {
        val inner = answerAsk(5)(ask)
        val outer = answerAsk(1)(ask.map(a => a + Eval(inner)))
        assert(Eval(outer) == 6)
        val leak = intercept[Throwable](Eval(answerAsk(1)(ask.map(_ => Eval[Int, Any](ask.asInstanceOf[Int < Any])))))
        assert(leak.getMessage.contains("Unexpected pending effect"))
        assert(Eval(answerAsk(41)(ask.map(_ + 1))) == 42)
    }

    "an eval cleans its stack after a throw" in {
        def boom: Int < Any = (0: Int < Any).map(_ => throw new RuntimeException("boom"))
        intercept[RuntimeException](Eval(answerAsk(1)(ask.map(_ => boom))))
        assert(Eval(answerAsk(41)(ask.map(_ + 1))) == 42)
    }

    "a throw inside a region leaves no findable handler behind" in {
        def stateful[A](v: A < Ask): A < Any =
            ArrowEffect.handleLoopState(Tag[Ask], 0, v)(
                [C] => (st, _) => Loop.continue(st + 1, st),
                (_, a) => a
            )
        intercept[RuntimeException](Eval(stateful(ask.map(_ => (throw new RuntimeException("boom")): Int))))
        val ex = intercept[Throwable](Eval(ask.asInstanceOf[Int < Any]))
        assert(ex.getMessage.contains("Unexpected pending effect"))
        assert(Eval(stateful(ask.map(a => ask.map(b => a * 10 + b)))) == 1)
    }

    // A throw escaping a root eval that skipped `Safepoint.exit` leaked one unit of depth per throw
    // on a slot that is per thread, so every later unrelated computation on that thread paid for it.
    // The symptom is not a wrong answer: `enter` returning false is the ordinary budget-exhausted
    // path, so an affected thread simply parks and allocates more, forever, with nothing to point
    // at. Measured before the fix at exactly one of 512 lost per throw.
    "a throw escaping a root eval leaves the safepoint depth unchanged" in {
        val slot = Safepoint.get()
        // `save` resets as it reads, so reading it back is a save/restore pair
        def depth() =
            val d = Safepoint.save(slot)
            Safepoint.restore(slot, d)
            d
        end depth
        val before = depth()
        var caught = 0
        var i      = 0
        while i < 50 do
            try discard(Eval(answerAsk(1)(ask.map(v => if v > 0 then throw new RuntimeException("boom") else v))))
            catch case _: RuntimeException => caught += 1
            i += 1
        end while
        assert(caught == 50)
        // `equals` rather than `==`: `State` is opaque and carries no `CanEqual`, and a cast to its
        // underlying Int would be a new cast for a test's convenience
        assert(depth().equals(before))
    }

    "the budget rescues rather than overflowing" in {
        def loop(n: Int): Int < Any =
            if n == 0 then 0 else (n: Int < Any).map(_ => loop(n - 1))
        assert(Eval(loop(Period * 4)) == 0)
    }

    "partial evaluation" - {

        // the stop-driven tests of this section (preemption reify/resume, between-slice
        // short-circuit, cross-thread parks, the outrun stop) live in the jvm-native
        // EvalThreadingTest: stops are the jvm-native preemption mechanism

        "partial completes when nothing stops" in {
            assert(Eval.partial(answerAsk(21)(ask.map(_ * 2))).evalNow == Maybe(42))
        }

        "a stop already pending ends the slice before it starts" in {
            // the claim comes first: a stop needs a slot to land on, and the input stays a pending
            // computation (a defer settles nothing at construction) so "the input comes straight
            // back" is observable
            kyo.discard(Safepoint.get())
            SafepointStop.request()
            val v      = Effect.defer(1).map(_ + 1)
            val parked = Eval.partial(v)
            assert(parked.evalNow.isEmpty)
            assert(Eval(parked) == 2)
        }

        "a parked slice re-enters and completes on the next slice" in {
            val v  = Effect.defer { SafepointStop.request(); 1 }.map(_ + 1)
            val p1 = Eval.partial(v)
            assert(p1.evalNow.isEmpty)
            assert(Eval.partial(p1).evalNow == Maybe(2))
        }

        "a computation held as a value passes through a parked slice intact" in {
            val payload: Int < Any = (3: Int < Any).map(_ + 4)
            val v: (Int < Any) < Any =
                Effect.defer {
                    SafepointStop.request()
                    ()
                }.map(_ => box(payload))
            val parked = Eval.partial(v)
            assert(parked.evalNow.isEmpty)
            val out = Eval(parked)
            assert(out.asInstanceOf[AnyRef] eq payload.asInstanceOf[AnyRef])
            assert(Eval(out) == 7)
        }

        // Two cases from an earlier design are gone rather than parked: a slice took a row that could still
        // name unhandled effects, so an operation without a handler ended the slice and something installed
        // later answered it. `partial` takes `A < Any` now, the same row a full evaluation takes, so an
        // operation with no handler has none anywhere and is a bug at both entry points.
    }

end EvalTest
