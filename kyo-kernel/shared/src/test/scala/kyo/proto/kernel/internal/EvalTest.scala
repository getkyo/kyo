package kyo.proto.kernel.internal

import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.Loop
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import kyo.proto.kernel.Effect
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec

class EvalTest extends AnyFreeSpec:
    // the eval's result as a raw value: unnesting delivers a payload as the computation it holds,
    // and an unanswered suspension surfaces through the failing assertion that compares it
    private def eval[A, S](v: A < S): A =
        Nested.unnest[A](Eval(v))

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    // the operation with its continuation fused into the node: the remainder is the node's own
    // arrow, not entries on the evaluator's stack
    inline def askWith[B, S](inline f: Int => B < S): B < (Ask & S) = ArrowEffect.suspendWith[Any](Tag[Ask], ())(f)

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue((), value: Int < Any), a => a)

    def recordSay[A, S](name: String, log: collection.mutable.ListBuffer[String])(v: A < (Say & S)): A < S =
        ArrowEffect.handleLoop(Tag[Say], v)(
            [C] =>
                _ =>
                    log += name
                    Loop.continue((), (): Unit < Any)
            ,
            a => a
        )

    def box[A](v: A): A < Any = v

    "values and map" - {
        "a settled value evaluates to itself" in {
            assert(eval(42: Int < Any) == 42)
        }

        "map runs strictly on a settled value" in {
            var ran = false
            val v = (1: Int < Any).map { n =>
                ran = true
                n + 1
            }
            assert(ran)
            assert(eval(v) == 2)
        }

        "map composes" in {
            assert(eval((1: Int < Any).map(_ + 1).map(_ * 10)) == 20)
        }

        "a long map tower evaluates in bounded stack" in {
            @tailrec def tower(v: Int < Any, n: Int): Int < Any =
                if n == 0 then v else tower(v.map(_ + 1), n - 1)
            assert(eval(tower(0, 1000000)) == 1000000)
        }

        "deep recursion through map pays rescues only" in {
            def loop(i: Int): Int < Any =
                if i == 0 then 0 else (0: Int < Any).map(_ => loop(i - 1))
            assert(eval(loop(1000000)) == 0)
        }

        "a computation held as a value round trips through the box" in {
            val inner: Int < Ask         = ask.map(_ + 1)
            val outer: (Int < Ask) < Any = box(inner)
            assert(eval(answerAsk(41)(eval(outer))) == 42)
        }

        "double nesting round trips one level per eval" in {
            val inner: Int < Ask                 = ask.map(_ + 1)
            val twice: ((Int < Ask) < Any) < Any = box(box(inner))
            assert(eval(answerAsk(41)(eval(eval(twice)))) == 42)
        }

        "a pending value does not lift into a nested computation implicitly" in {
            assertTypeError("val x: (Int < Any) < Any = (1: Int < Any).map(_ + 1)")
        }

        "an eval inside a map evaluates its argument rather than nesting it" in {
            // with an unconditional lift, inference solved the unannotated form as
            // Eval[Int < Any](lift(ask)) and the suspension itself came back as the map's result;
            // with the lint on lift the unannotated form does not compile, and the annotated one evaluates
            assertTypeError("eval(answerAsk(1)(ask.map(_ => eval(ask.asInstanceOf[Int < Any]))))")
            val ex = intercept[Throwable](eval(answerAsk(1)(ask.map(_ => Eval[Int, Any](ask.asInstanceOf[Int < Any])))))
            assert(ex.getMessage.contains("unhandled suspension"))
        }

        "map receives a computation held as a value unopened" in {
            val inner: Int < Ask    = ask
            var received: Int < Ask = 0
            val r: Int < Any = box(inner).map { c =>
                received = c
                7
            }
            assert(eval(r) == 7)
            assert(eval(answerAsk(41)(received.map(_ + 1))) == 42)
        }
    }

    "handleCont" - {
        "answers with the continuation in hand" in {
            val body         = ask.map(a => ask.map(b => a + b))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => cont(10), a => a)
            assert(eval(r) == 20)
        }

        "the captured continuation is multi-shot" in {
            val body = ask.map(_ * 2)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] => (_, cont) => cont(1).map(x => cont(2).map(y => x + y)),
                a => a
            )
            assert(eval(r) == 6)
        }

        "can end the computation without resuming" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a + 1
            }
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
            assert(eval(r) == -1)
            assert(!reached)
        }

        "a settled input applies done strictly" in {
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], 41: Int < Ask)([C] => (_, cont) => cont(0), a => a + 1)
            assert(eval(r) == 42)
        }

        "done applies to the settled result" in {
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))([C] => (_, cont) => cont(41), a => a * 10)
            assert(eval(r) == 420)
        }

        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], loop(100000))([C] => (_, cont) => cont(1), a => a)
            assert(eval(r) == 0)
        }

        "a capture crossing an inner region resumes it without re-running its body" in {
            var runs = 0
            val inner: Int < Say = answerAsk(1)(ask.map { a =>
                runs += 1
                say("x").map(_ => a + 1)
            })
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)([C] => (_, cont) => cont(()), a => a)
            assert(eval(r) == 2)
            assert(runs == 1)
        }

        "each shot of a multi-shot capture resumes from capture-time state" in {
            val inner: Int < Say = ArrowEffect.handleLoopState(
                Tag[Ask],
                0,
                ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b)))
            )([C] => (s, _) => Loop.continue(s + 1, s: Int < Any), (_, a) => a)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)(
                [C] => (_, cont) => cont(()).map(r1 => cont(()).map(r2 => r1 * 100 + r2)),
                a => a
            )
            assert(eval(r) == 101)
        }
    }

    "handleLoop" - {
        "answers every operation in place" in {
            def loop(i: Int): Int < Ask =
                if i < 3 then ask.map(a => loop(i + a)) else i
            assert(eval(answerAsk(1)(loop(0))) == 3)
        }

        "Loop.done stops the region and bypasses done" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a + 1
            }
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.done(-1), a => a * 10)
            assert(eval(r) == -1)
            assert(!reached)
        }

        "done sees the settled result" in {
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.continue((), 41: Int < Any), a => a * 10)
            assert(eval(r) == 420)
        }

        "a settled input applies done strictly" in {
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], 41: Int < Ask)([C] => _ => Loop.continue((), 0: Int < Any), a => a + 1)
            assert(eval(r) == 42)
        }

        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            assert(eval(answerAsk(1)(loop(100000))) == 0)
        }

        "the innermost region of a tag answers" in {
            val inner: Int < Any = answerAsk(1)(ask)
            val outer: Int < Any = answerAsk(2)(inner: Int < Ask)
            assert(eval(outer) == 1)
        }

        "a map after the region applies to the result" in {
            assert(eval(answerAsk(41)(ask.map(_ + 1)).map(_ * 10)) == 420)
        }

        "a foreign operation crosses the region in place" in {
            val log                     = collection.mutable.ListBuffer[String]()
            val body: Int < (Ask & Say) = say("a").map(_ => ask).map(_ + 1)
            assert(eval(recordSay("outer", log)(answerAsk(41)(body))) == 42)
            assert(log.toList == List("outer"))
        }

        "a clause that suspends before its outcome runs outside its region" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => say("c").map(_ => Loop.continue((), 41: Int < Any)),
                a => a
            )
            assert(eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("inner", "outer"))
        }

        "an effectful answer runs under this handler with the interior parked" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => Loop.continue((), say("c").map(_ => 41)),
                a => a
            )
            assert(eval(recordSay("outer", log)(askScope)) == 42)
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
            assert(eval(r) == -2)
            assert(clauseRuns == 2)
        }

        "a clause suspending and then answering effectfully runs the answer under this handler" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => say("pre").map(_ => Loop.continue((), say("c").map(_ => 41))),
                a => a
            )
            assert(eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("inner", "outer", "outer"))
        }

        "an effectful answer's own-tag re-raise is answered by this handler" in {
            var clauseRuns = 0
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [C] =>
                    _ =>
                        clauseRuns += 1
                        if clauseRuns == 1 then Loop.continue((), ask.map(_ + 100)) else Loop.continue((), 1: Int < Any)
                ,
                a => a
            )
            assert(eval(r) == 102)
            assert(clauseRuns == 2)
        }

        "a clause suspending and then answering with an own-tag re-raise is answered by this handler" in {
            var clauseRuns = 0
            val log        = collection.mutable.ListBuffer[String]()
            val handled: Int < Say = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [C] =>
                    _ =>
                        clauseRuns += 1
                        if clauseRuns == 1 then say("pre").map(_ => Loop.continue((), ask.map(_ + 100)))
                        else Loop.continue((), 1: Int < Any)
                ,
                a => a
            )
            assert(eval(recordSay("outer", log)(handled)) == 102)
            assert(clauseRuns == 2)
            assert(log.toList == List("outer"))
        }

        // the operation was raised from inside the interior region, so its remainder belongs inside
        // that region: an effectful answer runs under this handler with the interior parked, and the
        // interior comes back around the remainder, not after it
        "an effectful answer's remainder runs inside the interior region" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = ask.map(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => Loop.continue((), say("c").map(_ => 41)),
                a => a
            )
            assert(eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("outer", "inner"))
        }

        "an effectful answer's remainder raises the interior's effect with no outer handler for it" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = ask.map(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            // a pending answer with nothing left in its row: a handled region is pending currency
            val pendingAnswer: Int < Any = answerAsk(0)(ask.map(_ => 41))
            val askScope: Int < Any = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => Loop.continue((), pendingAnswer),
                a => a
            )
            assert(eval(askScope) == 42)
            assert(log.toList == List("inner"))
        }

        "a clause suspending and then answering effectfully keeps the remainder inside the interior region" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = ask.map(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => say("pre").map(_ => Loop.continue((), say("c").map(_ => 41))),
                a => a
            )
            assert(eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("outer", "outer", "inner"))
        }

        // the same three shapes with the remainder fused into the operation's node: the interior is
        // parked around the answer either way, and it must come back around the remainder, whether
        // the remainder lives on the stack or in the node
        "an effectful answer's fused remainder runs inside the interior region" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = askWith(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => Loop.continue((), say("c").map(_ => 41)),
                a => a
            )
            assert(eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("outer", "inner"))
        }

        "an effectful answer's fused remainder raises the interior's effect with no outer handler for it" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = askWith(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val pendingAnswer: Int < Any   = answerAsk(0)(ask.map(_ => 41))
            val askScope: Int < Any = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => Loop.continue((), pendingAnswer),
                a => a
            )
            assert(eval(askScope) == 42)
            assert(log.toList == List("inner"))
        }

        "a clause suspending and then answering effectfully keeps the fused remainder inside the interior region" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = askWith(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => say("pre").map(_ => Loop.continue((), say("c").map(_ => 41))),
                a => a
            )
            assert(eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("outer", "outer", "inner"))
        }

        "a computation held as a value crosses a handler as a value" in {
            val payload: Int < Say   = say("p").map(_ => 7)
            val v: (Int < Say) < Ask = ask.map(_ => box(payload))
            val handled: (Int < Say) < Any =
                ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue((), 0: Int < Any), a => box(a))
            var seen = ""
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], eval(handled))(
                [C] =>
                    s =>
                        seen = s
                        Loop.continue((), (): Unit < Any)
                ,
                a => a
            )
            assert(eval(r) == 7)
            assert(seen == "p")
        }

        "an answer that is a computation held as a value stays a value" in {
            sealed trait Give extends ArrowEffect[Const[Unit], Const[Int < Ask]]
            val give: (Int < Ask) < Give = ArrowEffect.suspend[Any](Tag[Give], ())
            val inner: Int < Ask         = ask.map(_ + 1)
            val body: Int < (Give & Ask) = give.map(c => c)
            val r: Int < Any = answerAsk(41)(
                ArrowEffect.handleLoop(Tag[Give], body)([C] => _ => Loop.continue((), box(inner)), a => a)
            )
            assert(eval(r) == 42)
        }
    }

    "handleLoopState" - {
        "threads state through operations" in {
            val v = ask.map(a => ask.map(b => a * 10 + b))
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 1, v)(
                [C] => (s, _) => Loop.continue(s + 1, s: Int < Any),
                (_, a) => a
            )
            assert(eval(r) == 12)
        }

        "done observes the final state" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r: (Int, Int) < Any = ArrowEffect.handleLoopState(Tag[Ask], 10, v)(
                [C] => (s, _) => Loop.continue(s + 1, s: Int < Any),
                (s, a) => (s, a)
            )
            assert(eval(r) == (12, 21))
        }

        "Loop.done bypasses done" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r: String < Any = ArrowEffect.handleLoopState(Tag[Ask], 0, v)(
                [C] => (s, _) => if s == 1 then Loop.done("stopped") else Loop.continue(s + 1, 1: Int < Any),
                (s, a) => s"done $a"
            )
            assert(eval(r) == "stopped")
        }

        "state survives a foreign crossing" in {
            val body: Int < (Ask & Say) = ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b)))
            val inner: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 1, body)(
                [C] => (s, _) => Loop.continue(s + 1, s: Int < Any),
                (_, a) => a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], inner)([C] => _ => Loop.continue((), (): Unit < Any), a => a)
            assert(eval(r) == 12)
        }

        "a stateful clause that suspends threads its state through the park" in {
            val log = collection.mutable.ListBuffer[String]()
            val v   = ask.map(a => ask.map(b => a * 10 + b))
            val handled: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 1, v)(
                [C] => (s, _) => say(s"state $s").map(_ => Loop.continue(s + 1, s: Int < Any)),
                (_, a) => a
            )
            assert(eval(recordSay("outer", log)(handled)) == 12)
            assert(log.size == 2)
        }

        "a stateful effectful answer's remainder runs inside the interior region" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = ask.map(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 40, sayInner)(
                [C] => (s, _) => Loop.continue(s + 1, say("c").map(_ => s + 1)),
                (_, a) => a
            )
            assert(eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("outer", "inner"))
        }

        "a stateful effectful answer's fused remainder runs inside the interior region" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = askWith(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 40, sayInner)(
                [C] => (s, _) => Loop.continue(s + 1, say("c").map(_ => s + 1)),
                (_, a) => a
            )
            assert(eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("outer", "inner"))
        }
    }

    // a continuation handed to a clause is a value the kernel produced: it carries no live stack, so
    // it means the same thing wherever and whenever it is applied, and applying it never disturbs
    // the evaluation that produced it
    "a captured continuation is a value" - {
        def stateful(body: Int < (Ask & Say)): Int < Say =
            ArrowEffect.handleLoopState(Tag[Ask], 0, body)([C] => (s, _) => Loop.continue(s + 1, s: Int < Any), (_, a) => a)

        "resumes after its region completed, in a fresh evaluation, each shot from capture-time state" in {
            var stored: Maybe[Unit => Int < Say] = Maybe.empty
            val inner: Int < Say                 = stateful(ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b))))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)(
                [C] =>
                    (_, cont) =>
                        stored = Maybe(cont(_))
                        -1
                ,
                a => a
            )
            assert(eval(r) == -1)
            val k             = stored.get
            def resume(): Int = eval(ArrowEffect.handleCont(Tag[Say], k(()))([C] => (_, cont) => cont(()), a => a))
            assert(resume() == 1)
            assert(resume() == 1)
        }

        "resumes on another thread" in {
            var stored: Maybe[Unit => Int < Say] = Maybe.empty
            val inner: Int < Say                 = stateful(ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b))))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)(
                [C] =>
                    (_, cont) =>
                        stored = Maybe(cont(_))
                        -1
                ,
                a => a
            )
            assert(eval(r) == -1)
            val k             = stored.get
            def resume(): Int = eval(ArrowEffect.handleCont(Tag[Say], k(()))([C] => (_, cont) => cont(()), a => a))
            val results       = new java.util.concurrent.ConcurrentLinkedQueue[Int]()
            val threads = (1 to 4).map(_ =>
                new Thread(() =>
                    results.add(resume()); ()
                )
            )
            threads.foreach(_.start())
            threads.foreach(_.join())
            assert(List.fill(4)(results.poll()) == List(1, 1, 1, 1))
            assert(results.isEmpty)
        }

        "resumes under a later region of the same tag, which answers the remainder" in {
            var stored: Maybe[Int => Int < Ask] = Maybe.empty
            val body: Int < Ask                 = ask.map(a => ask.map(b => a * 10 + b))
            val first: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] =>
                    (_, cont) =>
                        stored = Maybe(cont(_))
                        -1
                ,
                a => a
            )
            assert(eval(first) == -1)
            val k = stored.get
            assert(eval(answerAsk(7)(k(1))) == 17)
            assert(eval(answerAsk(8)(k(2))) == 28)
        }

        "a shot evaluated inside the clause leaves the region intact for the next" in {
            val inner: Int < Say = stateful(ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b))))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)(
                [C] =>
                    (_, cont) =>
                        val now = eval(ArrowEffect.handleCont(Tag[Say], cont(()))([C] => (_, c2) => c2(()), a => a))
                        cont(()).map(later => now * 100 + later)
                ,
                a => a
            )
            assert(eval(r) == 101)
        }
    }

    "an unhandled operation is a bug" in {
        val ex = intercept[Throwable](eval(ask.asInstanceOf[Int < Any]))
        assert(ex.getMessage.contains("unhandled suspension"))
    }

    "a nested eval shares the thread's stack and sees none of the outer regions" in {
        val inner = answerAsk(5)(ask)
        val outer = answerAsk(1)(ask.map(a => a + eval(inner)))
        assert(eval(outer) == 6)
        val leak = intercept[Throwable](eval(answerAsk(1)(ask.map(_ => Eval[Int, Any](ask.asInstanceOf[Int < Any])))))
        assert(leak.getMessage.contains("unhandled suspension"))
        assert(eval(answerAsk(41)(ask.map(_ + 1))) == 42)
    }

    "an eval cleans its stack after a throw" in {
        def boom: Int < Any = (0: Int < Any).map(_ => throw new RuntimeException("boom"))
        intercept[RuntimeException](eval(answerAsk(1)(ask.map(_ => boom))))
        assert(eval(answerAsk(41)(ask.map(_ + 1))) == 42)
    }

    // stackless and shared: the test is about the eval's stack, and filling a trace ten thousand
    // times measures the JVM's exception construction instead
    private object Boom extends RuntimeException("boom", null, false, false)

    // A context read rebinds "the updated value for the rest of that region's extent", and an
    // answered operation is inside that extent. The public surface cannot show this: every
    // ContextEffect.suspend carries an identity update, so the node is built here directly.
    "a context update outlives an operation answered after it" in {
        sealed trait Count extends kyo.proto.kernel.ContextEffect[Int]
        def bump: Int < Count =
            new Kyo.SuspendContext[Int, Count, Int, Count]:
                def tag            = Tag[Count]
                def update(v: Int) = v + 1
                def cont           = Arrow.id
        // bound at 10: the first read rebinds 11, the Ask region answers, the second read sees 11
        // and rebinds 12. Resuming an answered operation with the context the region was installed
        // with instead would lose the first update and give 11
        val body: Int < (Count & Ask) = bump.map(_ => ask.map(_ => bump))
        val r: Int < Count            = answerAsk(0)(body)
        assert(eval(kyo.proto.kernel.ContextEffect.handle(Tag[Count], 10)(r)) == 12)
    }

    // the eval holds no frame per open region, and it must hold none per recovered one either: a
    // recovery that resumed by evaluating the rest of the eval from inside its own guard would make
    // this grow with the number of throws rather than stay flat
    "regions that fail and recover in sequence cost no stack" in {
        def recovering(to: Int): Int < Any =
            val h = new Handler.HandlerCont[Const[Unit], Const[Int], Ask, Int, Int, Any]:
                def tag                                          = Tag[Ask]
                override def recover(state: Unit, ex: Throwable) = Maybe(to)
                def done(state: Unit, v: Int)                    = v
                def answer[X](input: Unit, next: Arrow[Int, Int, Ask]): Int < Ask =
                    next(0, Arrow.id)
            val body: Int < Ask = ask.map(_ => (throw Boom): Int)
            Kyo.handle[Ask, Int, Int, Any, Unit](body, h, ())
        end recovering
        def go(i: Int): Int < Any =
            if i == 0 then 0
            else recovering(i).map(_ => go(i - 1))
        assert(eval(go(10000)) == 0)
    }

    // The budget counts the strict applications standing on the Java stack, and a throw unwinds them
    // without their matching exits. The eval's guard repairs that where it catches, but a foreign
    // crossing carries its own recover, and a throw recovered there left the count short by every
    // application it unwound. A drained count is a fixed point rather than a slow path, so enough of
    // these livelock; the count is sampled directly here, which fails while the scenario still ends.
    "a region recovering across a foreign crossing leaves the budget where it found it" in {
        val samples = collection.mutable.ListBuffer.empty[Safepoint.State]
        def sample(): Unit =
            val slot = Safepoint.get()
            // `save` installs a fresh budget as it reads, so reading it back is a save/restore pair
            val d = Safepoint.save(slot)
            Safepoint.restore(slot, d)
            samples += d
        end sample

        // the Say region is foreign to the Ask its body suspends, so answering that Ask resumes
        // through the rebuilt node, and the throw that follows lands in the crossing's own recover
        def crossing(to: Int): Int < Ask =
            val h = new Handler.HandlerCont[Const[String], Const[Unit], Say, Int, Int, Ask]:
                def tag                                          = Tag[Say]
                override def recover(state: Unit, ex: Throwable) = Maybe(to)
                def done(state: Unit, v: Int)                    = v
                def answer[X](input: String, next: Arrow[Unit, Int, Say & Ask]): Int < (Say & Ask) =
                    next((), Arrow.id)
            val body: Int < (Say & Ask) = ask.map(_ => (throw Boom): Int)
            Kyo.handle[Say, Int, Int, Ask, Unit](body, h, ())
        end crossing

        // fewer cycles than the budget has entries, so a leaking eval still terminates and the
        // samples say so, rather than the suite hanging on the fixed point
        def go(i: Int): Int < Ask =
            if i == 0 then (0: Int < Ask)
            else
                crossing(i).map { _ =>
                    sample()
                    go(i - 1)
                }
        assert(eval(answerAsk(0)(go(100))) == 0)
        assert(samples.size == 100)
        // `equals` rather than `==`: `State` is opaque and carries no `CanEqual`, and a cast to its
        // underlying Int would be a new cast for a test's convenience
        assert(samples.forall(_.equals(samples.head)))
    }

    // `recover` is consulted with its region still installed, and one that fails itself is the
    // failure the regions outside it then see. The baseline got that free from nested tries; the
    // unwind is its own control flow now, and has been rewritten twice, so it is pinned rather than
    // stated. No test threw from a `recover` before this one.
    "a recover that fails itself is the failure the enclosing region sees" in {
        object Inner extends RuntimeException("inner", null, false, false)
        var seen = Maybe.empty[Throwable]

        val innerHandler = new Handler.HandlerCont[Const[String], Const[Unit], Say, Int, Int, Ask]:
            def tag                                          = Tag[Say]
            override def recover(state: Unit, ex: Throwable) = throw Inner
            def done(state: Unit, v: Int)                    = v
            def answer[X](input: String, next: Arrow[Unit, Int, Say & Ask]): Int < (Say & Ask) =
                next((), Arrow.id)

        val outerHandler = new Handler.HandlerCont[Const[Unit], Const[Int], Ask, Int, Int, Any]:
            def tag = Tag[Ask]
            override def recover(state: Unit, ex: Throwable) =
                seen = Maybe(ex)
                Maybe(7)
            def done(state: Unit, v: Int) = v
            def answer[X](input: Unit, next: Arrow[Int, Int, Ask]): Int < Ask =
                next(0, Arrow.id)

        // deferred, so the throw lands while the loop is inside the region rather than while the
        // computation is being built
        val body: Int < (Say & Ask) = Effect.defer((throw Boom): Int < (Say & Ask))
        val inner: Int < Ask        = Kyo.handle[Say, Int, Int, Ask, Unit](body, innerHandler, ())
        val outer: Int < Any        = Kyo.handle[Ask, Int, Int, Any, Unit](inner, outerHandler, ())
        assert(eval(outer) == 7)
        // the second failure, not the one the inner region declined by throwing
        assert(seen.exists(_ eq Inner))
    }

end EvalTest
